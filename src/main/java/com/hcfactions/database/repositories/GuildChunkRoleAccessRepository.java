package com.hcfactions.database.repositories;

import com.hcfactions.database.DatabaseManager;
import com.hcfactions.models.GuildChunkRoleAccess;
import com.hcfactions.models.GuildRole;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nullable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Repository for per-role guild chunk access requirements.
 */
public class GuildChunkRoleAccessRepository {

    private static final HytaleLogger LOGGER = HytaleLogger.getLogger().getSubLogger("FactionGuilds-GuildChunkRoleAccessRepo");

    private static final String SELECT_COLUMNS = """
        guild_id, world, chunk_x, chunk_z,
        min_break_role, min_place_role, min_interact_role,
        min_doors_role, min_chests_role, min_benches_role,
        min_processing_role, min_seats_role, min_transport_role,
        updated_by, updated_at
        """;

    private final DatabaseManager databaseManager;

    public GuildChunkRoleAccessRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public void upsert(GuildChunkRoleAccess access) {
        String sql = """
            INSERT INTO fg_guild_chunk_role_access (
                guild_id, world, chunk_x, chunk_z,
                min_break_role, min_place_role, min_interact_role,
                min_doors_role, min_chests_role, min_benches_role,
                min_processing_role, min_seats_role, min_transport_role,
                updated_by
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (guild_id, world, chunk_x, chunk_z) DO UPDATE SET
                min_break_role = EXCLUDED.min_break_role,
                min_place_role = EXCLUDED.min_place_role,
                min_interact_role = EXCLUDED.min_interact_role,
                min_doors_role = EXCLUDED.min_doors_role,
                min_chests_role = EXCLUDED.min_chests_role,
                min_benches_role = EXCLUDED.min_benches_role,
                min_processing_role = EXCLUDED.min_processing_role,
                min_seats_role = EXCLUDED.min_seats_role,
                min_transport_role = EXCLUDED.min_transport_role,
                updated_by = EXCLUDED.updated_by,
                updated_at = CURRENT_TIMESTAMP
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, access.getGuildId());
            stmt.setString(2, access.getWorld());
            stmt.setInt(3, access.getChunkX());
            stmt.setInt(4, access.getChunkZ());
            stmt.setString(5, roleName(access.getMinBreakRole()));
            stmt.setString(6, roleName(access.getMinPlaceRole()));
            stmt.setString(7, roleName(access.getMinInteractRole()));
            stmt.setString(8, roleName(access.getMinDoorsRole()));
            stmt.setString(9, roleName(access.getMinChestsRole()));
            stmt.setString(10, roleName(access.getMinBenchesRole()));
            stmt.setString(11, roleName(access.getMinProcessingRole()));
            stmt.setString(12, roleName(access.getMinSeatsRole()));
            stmt.setString(13, roleName(access.getMinTransportRole()));
            stmt.setObject(14, access.getUpdatedBy());
            stmt.executeUpdate();
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error upserting role access: " + e.getMessage());
        }
    }

    public void delete(UUID guildId, String world, int chunkX, int chunkZ) {
        String sql = "DELETE FROM fg_guild_chunk_role_access WHERE guild_id = ? AND world = ? AND chunk_x = ? AND chunk_z = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, guildId);
            stmt.setString(2, world);
            stmt.setInt(3, chunkX);
            stmt.setInt(4, chunkZ);
            stmt.executeUpdate();
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error deleting role access: " + e.getMessage());
        }
    }

    public void deleteForChunk(UUID guildId, String world, int chunkX, int chunkZ) {
        delete(guildId, world, chunkX, chunkZ);
    }

    public void deleteForGuild(UUID guildId) {
        String sql = "DELETE FROM fg_guild_chunk_role_access WHERE guild_id = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, guildId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error deleting role access for guild: " + e.getMessage());
        }
    }

    @Nullable
    public GuildChunkRoleAccess get(UUID guildId, String world, int chunkX, int chunkZ) {
        String sql = "SELECT " + SELECT_COLUMNS
            + " FROM fg_guild_chunk_role_access"
            + " WHERE guild_id = ? AND world = ? AND chunk_x = ? AND chunk_z = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, guildId);
            stmt.setString(2, world);
            stmt.setInt(3, chunkX);
            stmt.setInt(4, chunkZ);

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return mapRow(rs);
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error getting role access: " + e.getMessage());
        }

        return null;
    }

    public List<GuildChunkRoleAccess> getAll() {
        List<GuildChunkRoleAccess> results = new ArrayList<>();
        String sql = "SELECT " + SELECT_COLUMNS + " FROM fg_guild_chunk_role_access";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                results.add(mapRow(rs));
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error loading all role access rows: " + e.getMessage());
        }

        return results;
    }

    private GuildChunkRoleAccess mapRow(ResultSet rs) throws SQLException {
        UUID guildId = rs.getObject("guild_id", UUID.class);
        String world = rs.getString("world");
        int chunkX = rs.getInt("chunk_x");
        int chunkZ = rs.getInt("chunk_z");

        GuildRole minBreakRole = parseRole(rs.getString("min_break_role"));
        GuildRole minPlaceRole = parseRole(rs.getString("min_place_role"));
        GuildRole minInteractRole = parseRole(rs.getString("min_interact_role"));
        GuildRole minDoorsRole = parseRole(rs.getString("min_doors_role"));
        GuildRole minChestsRole = parseRole(rs.getString("min_chests_role"));
        GuildRole minBenchesRole = parseRole(rs.getString("min_benches_role"));
        GuildRole minProcessingRole = parseRole(rs.getString("min_processing_role"));
        GuildRole minSeatsRole = parseRole(rs.getString("min_seats_role"));
        GuildRole minTransportRole = parseRole(rs.getString("min_transport_role"));

        UUID updatedBy = rs.getObject("updated_by", UUID.class);
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        long updatedAtMs = updatedAt != null ? updatedAt.getTime() : System.currentTimeMillis();

        return new GuildChunkRoleAccess(
            guildId, world, chunkX, chunkZ,
            minBreakRole, minPlaceRole, minInteractRole,
            minDoorsRole, minChestsRole, minBenchesRole,
            minProcessingRole, minSeatsRole, minTransportRole,
            updatedBy, updatedAtMs
        );
    }

    @Nullable
    private GuildRole parseRole(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return GuildRole.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Nullable
    private String roleName(@Nullable GuildRole role) {
        return role != null ? role.name() : null;
    }
}
