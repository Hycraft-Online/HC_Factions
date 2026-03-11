package com.hcfactions.database;

import com.hccore.api.HC_CoreAPI;
import com.hypixel.hytale.logger.HytaleLogger;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;

/**
 * Manages database schema for HC_Factions tables.
 * Connection pool is provided by HC_Core's shared pool.
 */
public class DatabaseManager {

    private static final HytaleLogger LOGGER = HytaleLogger.getLogger().getSubLogger("FactionGuilds-DB");

    /**
     * Creates a new DatabaseManager using HC_Core's shared connection pool.
     * Initializes schema on construction.
     */
    public DatabaseManager() {
        LOGGER.at(Level.INFO).log("Using HC_Core shared connection pool");

        // Test connection
        try (Connection conn = getConnection()) {
            if (conn.isValid(2)) {
                LOGGER.at(Level.INFO).log("Database connection test successful");
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Database connection test failed: " + e.getMessage());
            throw new RuntimeException("Failed to connect to database via HC_Core", e);
        }

        // Initialize schema
        initializeSchema();
    }

    /**
     * Creates the necessary database tables if they don't exist.
     * Tables are prefixed with fg_ to avoid conflicts with other mods.
     */
    private void initializeSchema() {
        LOGGER.at(Level.INFO).log("Initializing database schema...");

        // Player data table
        String createPlayerDataTable = """
            CREATE TABLE IF NOT EXISTS fg_player_data (
                player_uuid UUID PRIMARY KEY,
                player_name VARCHAR(64),
                faction_id VARCHAR(64),
                guild_id UUID,
                guild_role VARCHAR(32),
                guild_joined_at TIMESTAMP,
                has_chosen_faction BOOLEAN DEFAULT FALSE,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """;

        // Guilds table
        String createGuildsTable = """
            CREATE TABLE IF NOT EXISTS fg_guilds (
                guild_id UUID PRIMARY KEY,
                name VARCHAR(64) NOT NULL UNIQUE,
                faction_id VARCHAR(64) NOT NULL,
                leader_uuid UUID NOT NULL,
                power INTEGER DEFAULT 10,
                max_power INTEGER DEFAULT 10,
                bank_balance DOUBLE PRECISION DEFAULT 0,
                home_world VARCHAR(64),
                home_x DOUBLE PRECISION,
                home_y DOUBLE PRECISION,
                home_z DOUBLE PRECISION,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """;

        String createGuildFactionIndex = """
            CREATE INDEX IF NOT EXISTS idx_fg_guilds_faction ON fg_guilds(faction_id)
            """;

        // Claims table - guild_id can be NULL for faction-level claims
        String createClaimsTable = """
            CREATE TABLE IF NOT EXISTS fg_claims (
                claim_id SERIAL PRIMARY KEY,
                chunk_x INTEGER NOT NULL,
                chunk_z INTEGER NOT NULL,
                world VARCHAR(64) NOT NULL,
                guild_id UUID,
                faction_id VARCHAR(64) NOT NULL,
                suppressor_uuid UUID,
                claimed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                UNIQUE(chunk_x, chunk_z, world)
            )
            """;

        // Migration: Allow NULL in guild_id for existing tables (faction claims)
        String alterClaimsGuildIdNullable = """
            ALTER TABLE fg_claims ALTER COLUMN guild_id DROP NOT NULL
            """;

        // Migration: Add suppressor_uuid column for spawn suppression
        String addSuppressorUuidColumn = """
            ALTER TABLE fg_claims ADD COLUMN IF NOT EXISTS suppressor_uuid UUID
            """;

        // Migration: Add home location columns to player data
        String addPlayerHomeColumns = """
            ALTER TABLE fg_player_data
            ADD COLUMN IF NOT EXISTS home_world VARCHAR(64),
            ADD COLUMN IF NOT EXISTS home_x DOUBLE PRECISION,
            ADD COLUMN IF NOT EXISTS home_y DOUBLE PRECISION,
            ADD COLUMN IF NOT EXISTS home_z DOUBLE PRECISION
            """;

        // Migration: Add player_owner_id column for solo player claims
        String addPlayerOwnerIdColumn = """
            ALTER TABLE fg_claims ADD COLUMN IF NOT EXISTS player_owner_id UUID
            """;

        String createClaimPlayerIndex = """
            CREATE INDEX IF NOT EXISTS idx_fg_claims_player ON fg_claims(player_owner_id) WHERE player_owner_id IS NOT NULL
            """;

        // Migration: Add tag column for custom guild tags (1-4 letters)
        String addGuildTagColumn = """
            ALTER TABLE fg_guilds ADD COLUMN IF NOT EXISTS tag VARCHAR(4)
            """;

        // Migration: Add RTP spawn columns to factions (where players spawn after starter area)
        String addRtpSpawnColumns = """
            ALTER TABLE fg_factions
            ADD COLUMN IF NOT EXISTS rtp_spawn_x DOUBLE PRECISION,
            ADD COLUMN IF NOT EXISTS rtp_spawn_y DOUBLE PRECISION,
            ADD COLUMN IF NOT EXISTS rtp_spawn_z DOUBLE PRECISION
            """;

        String createClaimGuildIndex = """
            CREATE INDEX IF NOT EXISTS idx_fg_claims_guild ON fg_claims(guild_id)
            """;

        String createClaimFactionIndex = """
            CREATE INDEX IF NOT EXISTS idx_fg_claims_faction ON fg_claims(faction_id)
            """;

        String createClaimLocationIndex = """
            CREATE INDEX IF NOT EXISTS idx_fg_claims_location ON fg_claims(world, chunk_x, chunk_z)
            """;

        // Migration: Ensure claim_id sequence matches table data (handles DB restores/imports)
        String syncClaimIdSequence = """
            SELECT setval(
                pg_get_serial_sequence('fg_claims', 'claim_id'),
                GREATEST(COALESCE((SELECT MAX(claim_id) FROM fg_claims), 0) + 1, 1),
                false
            )
            """;

        // Per-member guild chunk access grants
        String createGuildChunkAccessTable = """
            CREATE TABLE IF NOT EXISTS fg_guild_chunk_access (
                guild_id UUID NOT NULL,
                member_uuid UUID NOT NULL,
                world VARCHAR(64) NOT NULL,
                chunk_x INTEGER NOT NULL,
                chunk_z INTEGER NOT NULL,
                can_edit BOOLEAN NOT NULL DEFAULT TRUE,
                can_chest BOOLEAN NOT NULL DEFAULT TRUE,
                granted_by UUID,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY(guild_id, member_uuid, world, chunk_x, chunk_z)
            )
            """;

        String createGuildChunkAccessGuildChunkIndex = """
            CREATE INDEX IF NOT EXISTS idx_fg_chunk_access_guild_chunk
            ON fg_guild_chunk_access(guild_id, world, chunk_x, chunk_z)
            """;

        String createGuildChunkAccessMemberIndex = """
            CREATE INDEX IF NOT EXISTS idx_fg_chunk_access_member
            ON fg_guild_chunk_access(member_uuid, guild_id)
            """;

        // Per-chunk role-based guild access requirements
        String createGuildChunkRoleAccessTable = """
            CREATE TABLE IF NOT EXISTS fg_guild_chunk_role_access (
                guild_id UUID NOT NULL,
                world VARCHAR(64) NOT NULL,
                chunk_x INTEGER NOT NULL,
                chunk_z INTEGER NOT NULL,
                min_edit_role VARCHAR(32),
                min_chest_role VARCHAR(32),
                updated_by UUID,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY(guild_id, world, chunk_x, chunk_z)
            )
            """;

        String createGuildChunkRoleAccessGuildChunkIndex = """
            CREATE INDEX IF NOT EXISTS idx_fg_chunk_role_access_guild_chunk
            ON fg_guild_chunk_role_access(guild_id, world, chunk_x, chunk_z)
            """;

        // Invitations table
        String createInvitationsTable = """
            CREATE TABLE IF NOT EXISTS fg_invitations (
                guild_id UUID NOT NULL,
                player_uuid UUID NOT NULL,
                invited_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY(guild_id, player_uuid)
            )
            """;

        // Join requests table (players requesting to join guilds)
        String createJoinRequestsTable = """
            CREATE TABLE IF NOT EXISTS fg_join_requests (
                guild_id UUID NOT NULL,
                player_uuid UUID NOT NULL,
                requested_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY(guild_id, player_uuid)
            )
            """;

        // fg_config table removed — settings now live in mod_settings (via HC_CoreAPI)

        // Factions table - admin-defined major factions
        String createFactionsTable = """
            CREATE TABLE IF NOT EXISTS fg_factions (
                faction_id VARCHAR(64) PRIMARY KEY,
                display_name VARCHAR(128) NOT NULL,
                color VARCHAR(16) NOT NULL,
                spawn_world VARCHAR(64) NOT NULL,
                spawn_x DOUBLE PRECISION NOT NULL,
                spawn_y DOUBLE PRECISION NOT NULL,
                spawn_z DOUBLE PRECISION NOT NULL,
                rtp_spawn_x DOUBLE PRECISION,
                rtp_spawn_y DOUBLE PRECISION,
                rtp_spawn_z DOUBLE PRECISION,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """;

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute(createPlayerDataTable);
            LOGGER.at(Level.INFO).log("Created/verified fg_player_data table");

            stmt.execute(createGuildsTable);
            LOGGER.at(Level.INFO).log("Created/verified fg_guilds table");

            stmt.execute(createGuildFactionIndex);
            stmt.execute(createClaimsTable);

            try { stmt.execute(syncClaimIdSequence); }
            catch (SQLException e) { LOGGER.at(Level.WARNING).log("Failed to sync claim_id sequence: " + e.getMessage()); }

            try { stmt.execute(alterClaimsGuildIdNullable); }
            catch (SQLException e) { /* already nullable */ }

            try { stmt.execute(addSuppressorUuidColumn); } catch (SQLException e) { /* exists */ }
            try { stmt.execute(addPlayerHomeColumns); } catch (SQLException e) { /* exists */ }
            try { stmt.execute(addPlayerOwnerIdColumn); } catch (SQLException e) { /* exists */ }
            try { stmt.execute(createClaimPlayerIndex); } catch (SQLException e) { /* exists */ }
            try { stmt.execute(addGuildTagColumn); } catch (SQLException e) { /* exists */ }

            stmt.execute(createClaimGuildIndex);
            stmt.execute(createClaimFactionIndex);
            stmt.execute(createClaimLocationIndex);

            stmt.execute(createGuildChunkAccessTable);
            stmt.execute(createGuildChunkAccessGuildChunkIndex);
            stmt.execute(createGuildChunkAccessMemberIndex);

            stmt.execute(createGuildChunkRoleAccessTable);
            stmt.execute(createGuildChunkRoleAccessGuildChunkIndex);

            // Migration: Expand role access from 2 columns to 9 granular permission columns
            String addGranularRoleColumns = """
                ALTER TABLE fg_guild_chunk_role_access
                ADD COLUMN IF NOT EXISTS min_break_role VARCHAR(32),
                ADD COLUMN IF NOT EXISTS min_place_role VARCHAR(32),
                ADD COLUMN IF NOT EXISTS min_interact_role VARCHAR(32),
                ADD COLUMN IF NOT EXISTS min_doors_role VARCHAR(32),
                ADD COLUMN IF NOT EXISTS min_chests_role VARCHAR(32),
                ADD COLUMN IF NOT EXISTS min_benches_role VARCHAR(32),
                ADD COLUMN IF NOT EXISTS min_processing_role VARCHAR(32),
                ADD COLUMN IF NOT EXISTS min_seats_role VARCHAR(32),
                ADD COLUMN IF NOT EXISTS min_transport_role VARCHAR(32)
                """;
            try { stmt.execute(addGranularRoleColumns); } catch (SQLException e) { /* exists */ }

            // Migration: Migrate old min_edit_role/min_chest_role data into new granular columns
            String migrateOldRoleData = """
                UPDATE fg_guild_chunk_role_access SET
                  min_break_role = COALESCE(min_break_role, min_edit_role),
                  min_place_role = COALESCE(min_place_role, min_edit_role),
                  min_interact_role = COALESCE(min_interact_role, min_chest_role),
                  min_doors_role = COALESCE(min_doors_role, min_chest_role),
                  min_chests_role = COALESCE(min_chests_role, min_chest_role),
                  min_benches_role = COALESCE(min_benches_role, min_chest_role),
                  min_processing_role = COALESCE(min_processing_role, min_chest_role),
                  min_seats_role = COALESCE(min_seats_role, min_chest_role),
                  min_transport_role = COALESCE(min_transport_role, min_chest_role)
                WHERE min_break_role IS NULL AND min_edit_role IS NOT NULL
                """;
            try { stmt.execute(migrateOldRoleData); } catch (SQLException e) { /* no-op */ }

            stmt.execute(createInvitationsTable);
            stmt.execute(createJoinRequestsTable);
            stmt.execute(createFactionsTable);

            try { stmt.execute(addRtpSpawnColumns); } catch (SQLException e) { /* exists */ }

            // Migration: Add last_online column for claim decay tracking
            String addLastOnlineColumn = """
                ALTER TABLE fg_player_data ADD COLUMN IF NOT EXISTS last_online TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                """;
            try { stmt.execute(addLastOnlineColumn); } catch (SQLException e) { /* exists */ }

            // Migration: Backfill last_online from updated_at for existing rows
            String backfillLastOnline = """
                UPDATE fg_player_data SET last_online = updated_at WHERE last_online IS NULL
                """;
            try { stmt.execute(backfillLastOnline); } catch (SQLException e) { /* no-op */ }

            // Claim decay log table
            String createClaimDecayLogTable = """
                CREATE TABLE IF NOT EXISTS fg_claim_decay_log (
                    id SERIAL PRIMARY KEY,
                    guild_id UUID NOT NULL,
                    guild_name VARCHAR(64),
                    chunk_x INTEGER NOT NULL,
                    chunk_z INTEGER NOT NULL,
                    world VARCHAR(64) NOT NULL,
                    decayed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    days_inactive INTEGER NOT NULL
                )
                """;
            stmt.execute(createClaimDecayLogTable);

            String createDecayLogGuildIndex = """
                CREATE INDEX IF NOT EXISTS idx_fg_claim_decay_log_guild ON fg_claim_decay_log(guild_id)
                """;
            stmt.execute(createDecayLogGuildIndex);

            String createDecayLogTimeIndex = """
                CREATE INDEX IF NOT EXISTS idx_fg_claim_decay_log_decayed_at ON fg_claim_decay_log(decayed_at)
                """;
            stmt.execute(createDecayLogTimeIndex);

            LOGGER.at(Level.INFO).log("Database schema initialization complete");

        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Failed to initialize database schema: " + e.getMessage());
            throw new RuntimeException("Failed to initialize database schema", e);
        }
    }

    /**
     * Gets a connection from HC_Core's shared pool.
     */
    public Connection getConnection() throws SQLException {
        return HC_CoreAPI.getConnection();
    }

    /**
     * No-op — HC_Core manages the connection pool lifecycle.
     */
    public void close() {
        LOGGER.at(Level.INFO).log("DatabaseManager.close() — pool managed by HC_Core");
    }
}
