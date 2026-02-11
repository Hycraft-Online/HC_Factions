package com.hcfactions.database;

import com.hypixel.hytale.logger.HytaleLogger;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;

/**
 * Manages the database connection pool using HikariCP.
 * Handles schema initialization for FactionGuilds tables.
 */
public class DatabaseManager {

    private static final HytaleLogger LOGGER = HytaleLogger.getLogger().getSubLogger("FactionGuilds-DB");

    private final HikariDataSource dataSource;

    /**
     * Creates a new DatabaseManager with the specified connection parameters.
     *
     * @param jdbcUrl     JDBC connection URL
     * @param username    Database username
     * @param password    Database password
     * @param maxPoolSize Maximum connection pool size
     */
    public DatabaseManager(String jdbcUrl, String username, String password, int maxPoolSize) {
        LOGGER.at(Level.INFO).log("Initializing database connection pool...");

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(1);
        config.setDriverClassName("org.postgresql.Driver");

        // Connection pool settings
        config.setConnectionTimeout(10000);
        config.setValidationTimeout(5000);
        config.setInitializationFailTimeout(10000);

        // Pool name for logging
        config.setPoolName("FactionGuilds-DB-Pool");

        try {
            this.dataSource = new HikariDataSource(config);
            LOGGER.at(Level.INFO).log("Database connection pool initialized successfully");

            // Test connection
            try (Connection conn = dataSource.getConnection()) {
                if (conn.isValid(2)) {
                    LOGGER.at(Level.INFO).log("Database connection test successful");
                }
            }

            // Initialize schema
            initializeSchema();

        } catch (Exception e) {
            LOGGER.at(Level.SEVERE).log("Failed to initialize database: " + e.getMessage());
            throw new RuntimeException("Failed to initialize database connection pool", e);
        }
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

        // Config table - key-value store for plugin settings
        String createConfigTable = """
            CREATE TABLE IF NOT EXISTS fg_config (
                config_key VARCHAR(128) PRIMARY KEY,
                config_value TEXT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """;

        // Factions table - admin-defined major factions
        // spawn_x/y/z = faction capital location
        // rtp_spawn_x/y/z = where players spawn after starter area (between capitals)
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

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute(createPlayerDataTable);
            LOGGER.at(Level.INFO).log("Created/verified fg_player_data table");

            stmt.execute(createGuildsTable);
            LOGGER.at(Level.INFO).log("Created/verified fg_guilds table");

            stmt.execute(createGuildFactionIndex);
            LOGGER.at(Level.INFO).log("Created/verified guild faction index");

            stmt.execute(createClaimsTable);
            LOGGER.at(Level.INFO).log("Created/verified fg_claims table");

            // Migration: Make guild_id nullable for faction claims (ignore if already nullable)
            try {
                stmt.execute(alterClaimsGuildIdNullable);
                LOGGER.at(Level.INFO).log("Made guild_id nullable for faction claims");
            } catch (SQLException e) {
                // Column might already be nullable or constraint doesn't exist - that's fine
                LOGGER.at(Level.FINE).log("guild_id already nullable or migration not needed");
            }

            // Migration: Add suppressor_uuid column for spawn suppression
            try {
                stmt.execute(addSuppressorUuidColumn);
                LOGGER.at(Level.INFO).log("Added suppressor_uuid column for spawn suppression");
            } catch (SQLException e) {
                // Column might already exist - that's fine
                LOGGER.at(Level.FINE).log("suppressor_uuid column already exists or migration not needed");
            }
            
            // Migration: Add home location columns to player data
            try {
                stmt.execute(addPlayerHomeColumns);
                LOGGER.at(Level.INFO).log("Added home location columns to player data");
            } catch (SQLException e) {
                // Columns might already exist - that's fine
                LOGGER.at(Level.FINE).log("Home columns already exist or migration not needed");
            }

            // Migration: Add player_owner_id column for solo player claims
            try {
                stmt.execute(addPlayerOwnerIdColumn);
                LOGGER.at(Level.INFO).log("Added player_owner_id column for solo player claims");
            } catch (SQLException e) {
                // Column might already exist - that's fine
                LOGGER.at(Level.FINE).log("player_owner_id column already exists or migration not needed");
            }

            // Create index for player claims (partial index only on non-null values)
            try {
                stmt.execute(createClaimPlayerIndex);
                LOGGER.at(Level.INFO).log("Created/verified player claims index");
            } catch (SQLException e) {
                LOGGER.at(Level.FINE).log("Player claims index already exists");
            }

            // Migration: Add tag column for custom guild tags
            try {
                stmt.execute(addGuildTagColumn);
                LOGGER.at(Level.INFO).log("Added tag column to guilds table");
            } catch (SQLException e) {
                LOGGER.at(Level.FINE).log("Guild tag column already exists or migration not needed");
            }

            stmt.execute(createClaimGuildIndex);
            stmt.execute(createClaimFactionIndex);
            stmt.execute(createClaimLocationIndex);
            LOGGER.at(Level.INFO).log("Created/verified claim indexes");

            stmt.execute(createInvitationsTable);
            LOGGER.at(Level.INFO).log("Created/verified fg_invitations table");

            stmt.execute(createJoinRequestsTable);
            LOGGER.at(Level.INFO).log("Created/verified fg_join_requests table");

            stmt.execute(createConfigTable);
            LOGGER.at(Level.INFO).log("Created/verified fg_config table");

            stmt.execute(createFactionsTable);
            LOGGER.at(Level.INFO).log("Created/verified fg_factions table");

            // Migration: Add RTP spawn columns to factions
            try {
                stmt.execute(addRtpSpawnColumns);
                LOGGER.at(Level.INFO).log("Added RTP spawn columns to factions table");
            } catch (SQLException e) {
                LOGGER.at(Level.FINE).log("RTP spawn columns already exist or migration not needed");
            }

            LOGGER.at(Level.INFO).log("Database schema initialization complete");

        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Failed to initialize database schema: " + e.getMessage());
            throw new RuntimeException("Failed to initialize database schema", e);
        }
    }

    /**
     * Gets the underlying DataSource for repository use.
     */
    public DataSource getDataSource() {
        return dataSource;
    }

    /**
     * Gets a connection from the pool.
     */
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * Closes the connection pool gracefully.
     */
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            LOGGER.at(Level.INFO).log("Closing database connection pool...");
            dataSource.close();
            LOGGER.at(Level.INFO).log("Database connection pool closed");
        }
    }
}
