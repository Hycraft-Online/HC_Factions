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

    private final DatabaseManager databaseManager;

    public GuildChunkRoleAccessRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public void upsert(GuildChunkRoleAccess access) {
        String sql = """
            INSERT INTO fg_guild_chunk_role_access (
                guild_id, world, chunk_x, chunk_z, min_edit_role, min_chest_role, updated_by
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (guild_id, world, chunk_x, chunk_z) DO UPDATE SET
                min_edit_role = EXCLUDED.min_edit_role,
                min_chest_role = EXCLUDED.min_chest_role,
                updated_by = EXCLUDED.updated_by,
                updated_at = CURRENT_TIMESTAMP
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, access.getGuildId());
            stmt.setString(2, access.getWorld());
            stmt.setInt(3, access.getChunkX());
            stmt.setInt(4, access.getChunkZ());
            stmt.setString(5, access.getMinEditRole() != null ? access.getMinEditRole().name() : null);
            stmt.setString(6, access.getMinChestRole() != null ? access.getMinChestRole().name() : null);
            stmt.setObject(7, access.getUpdatedBy());
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
        String sql = """
            SELECT guild_id, world, chunk_x, chunk_z, min_edit_role, min_chest_role, updated_by, updated_at
            FROM fg_guild_chunk_role_access
            WHERE guild_id = ? AND world = ? AND chunk_x = ? AND chunk_z = ?
            """;

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
        String sql = """
            SELECT guild_id, world, chunk_x, chunk_z, min_edit_role, min_chest_role, updated_by, updated_at
            FROM fg_guild_chunk_role_access
            """;

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
        GuildRole minEditRole = parseRole(rs.getString("min_edit_role"));
        GuildRole minChestRole = parseRole(rs.getString("min_chest_role"));
        UUID updatedBy = rs.getObject("updated_by", UUID.class);
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        long updatedAtMs = updatedAt != null ? updatedAt.getTime() : System.currentTimeMillis();

        return new GuildChunkRoleAccess(
            guildId, world, chunkX, chunkZ, minEditRole, minChestRole, updatedBy, updatedAtMs
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
}
