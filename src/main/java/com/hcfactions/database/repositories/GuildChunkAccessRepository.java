package com.hcfactions.database.repositories;

import com.hcfactions.database.DatabaseManager;
import com.hcfactions.models.GuildChunkAccess;
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
 * Repository for per-member guild chunk access grants.
 */
public class GuildChunkAccessRepository {

    private static final HytaleLogger LOGGER = HytaleLogger.getLogger().getSubLogger("FactionGuilds-GuildChunkAccessRepo");

    private final DatabaseManager databaseManager;

    public GuildChunkAccessRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public void upsert(GuildChunkAccess access) {
        String sql = """
            INSERT INTO fg_guild_chunk_access (
                guild_id, member_uuid, world, chunk_x, chunk_z, can_edit, can_chest, granted_by
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (guild_id, member_uuid, world, chunk_x, chunk_z) DO UPDATE SET
                can_edit = EXCLUDED.can_edit,
                can_chest = EXCLUDED.can_chest,
                granted_by = EXCLUDED.granted_by,
                updated_at = CURRENT_TIMESTAMP
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, access.getGuildId());
            stmt.setObject(2, access.getMemberUuid());
            stmt.setString(3, access.getWorld());
            stmt.setInt(4, access.getChunkX());
            stmt.setInt(5, access.getChunkZ());
            stmt.setBoolean(6, access.canEdit());
            stmt.setBoolean(7, access.canChest());
            stmt.setObject(8, access.getGrantedBy());
            stmt.executeUpdate();
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error upserting chunk access: " + e.getMessage());
        }
    }

    public void delete(UUID guildId, UUID memberUuid, String world, int chunkX, int chunkZ) {
        String sql = "DELETE FROM fg_guild_chunk_access WHERE guild_id = ? AND member_uuid = ? AND world = ? AND chunk_x = ? AND chunk_z = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, guildId);
            stmt.setObject(2, memberUuid);
            stmt.setString(3, world);
            stmt.setInt(4, chunkX);
            stmt.setInt(5, chunkZ);
            stmt.executeUpdate();
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error deleting chunk access: " + e.getMessage());
        }
    }

    public void deleteForChunk(UUID guildId, String world, int chunkX, int chunkZ) {
        String sql = "DELETE FROM fg_guild_chunk_access WHERE guild_id = ? AND world = ? AND chunk_x = ? AND chunk_z = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, guildId);
            stmt.setString(2, world);
            stmt.setInt(3, chunkX);
            stmt.setInt(4, chunkZ);
            stmt.executeUpdate();
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error deleting chunk access for chunk: " + e.getMessage());
        }
    }

    public void deleteForGuild(UUID guildId) {
        String sql = "DELETE FROM fg_guild_chunk_access WHERE guild_id = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, guildId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error deleting chunk access for guild: " + e.getMessage());
        }
    }

    public void deleteForMember(UUID guildId, UUID memberUuid) {
        String sql = "DELETE FROM fg_guild_chunk_access WHERE guild_id = ? AND member_uuid = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, guildId);
            stmt.setObject(2, memberUuid);
            stmt.executeUpdate();
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error deleting chunk access for member: " + e.getMessage());
        }
    }

    @Nullable
    public GuildChunkAccess get(UUID guildId, UUID memberUuid, String world, int chunkX, int chunkZ) {
        String sql = """
            SELECT guild_id, member_uuid, world, chunk_x, chunk_z, can_edit, can_chest, granted_by, updated_at
            FROM fg_guild_chunk_access
            WHERE guild_id = ? AND member_uuid = ? AND world = ? AND chunk_x = ? AND chunk_z = ?
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, guildId);
            stmt.setObject(2, memberUuid);
            stmt.setString(3, world);
            stmt.setInt(4, chunkX);
            stmt.setInt(5, chunkZ);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapRow(rs);
                }
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error getting chunk access: " + e.getMessage());
        }

        return null;
    }

    public List<GuildChunkAccess> getMemberAssignments(UUID guildId, UUID memberUuid) {
        List<GuildChunkAccess> results = new ArrayList<>();
        String sql = """
            SELECT guild_id, member_uuid, world, chunk_x, chunk_z, can_edit, can_chest, granted_by, updated_at
            FROM fg_guild_chunk_access
            WHERE guild_id = ? AND member_uuid = ?
            ORDER BY world, chunk_x, chunk_z
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, guildId);
            stmt.setObject(2, memberUuid);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error listing member chunk assignments: " + e.getMessage());
        }

        return results;
    }

    public List<GuildChunkAccess> getAll() {
        List<GuildChunkAccess> results = new ArrayList<>();
        String sql = """
            SELECT guild_id, member_uuid, world, chunk_x, chunk_z, can_edit, can_chest, granted_by, updated_at
            FROM fg_guild_chunk_access
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error loading all chunk assignments: " + e.getMessage());
        }

        return results;
    }

    private GuildChunkAccess mapRow(ResultSet rs) throws SQLException {
        UUID guildId = rs.getObject("guild_id", UUID.class);
        UUID memberUuid = rs.getObject("member_uuid", UUID.class);
        String world = rs.getString("world");
        int chunkX = rs.getInt("chunk_x");
        int chunkZ = rs.getInt("chunk_z");
        boolean canEdit = rs.getBoolean("can_edit");
        boolean canChest = rs.getBoolean("can_chest");
        UUID grantedBy = rs.getObject("granted_by", UUID.class);
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        long updatedAtMs = updatedAt != null ? updatedAt.getTime() : System.currentTimeMillis();

        return new GuildChunkAccess(guildId, memberUuid, world, chunkX, chunkZ,
            canEdit, canChest, grantedBy, updatedAtMs);
    }
}
