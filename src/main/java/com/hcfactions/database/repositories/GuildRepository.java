package com.hcfactions.database.repositories;

import com.hcfactions.database.DatabaseManager;
import com.hcfactions.models.Guild;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nullable;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;

/**
 * Repository for guild data persistence.
 */
public class GuildRepository {

    private static final HytaleLogger LOGGER = HytaleLogger.getLogger().getSubLogger("FactionGuilds-GuildRepo");

    private final DatabaseManager databaseManager;

    public GuildRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /**
     * Gets a guild by ID.
     */
    @Nullable
    public Guild getGuild(UUID guildId) {
        String sql = """
            SELECT guild_id, name, tag, faction_id, leader_uuid, power, max_power,
                   bank_balance, home_world, home_x, home_y, home_z,
                   created_at, updated_at
            FROM fg_guilds
            WHERE guild_id = ?
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, guildId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapResultSetToGuild(rs);
                }
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error getting guild " + guildId + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Gets a guild by name (case-insensitive).
     */
    @Nullable
    public Guild getGuildByName(String name) {
        String sql = """
            SELECT guild_id, name, tag, faction_id, leader_uuid, power, max_power,
                   bank_balance, home_world, home_x, home_y, home_z,
                   created_at, updated_at
            FROM fg_guilds
            WHERE LOWER(name) = LOWER(?)
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, name);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapResultSetToGuild(rs);
                }
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error getting guild by name " + name + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Checks if a guild name is already taken.
     */
    public boolean isNameTaken(String name) {
        String sql = "SELECT 1 FROM fg_guilds WHERE LOWER(name) = LOWER(?)";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, name);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error checking guild name " + name + ": " + e.getMessage());
        }
        return false;
    }

    /**
     * Creates a new guild.
     */
    public void createGuild(Guild guild) {
        String sql = """
            INSERT INTO fg_guilds (
                guild_id, name, tag, faction_id, leader_uuid, power, max_power,
                bank_balance, home_world, home_x, home_y, home_z
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, guild.getId());
            stmt.setString(2, guild.getName());
            stmt.setString(3, guild.getTag());
            stmt.setString(4, guild.getFactionId());
            stmt.setObject(5, guild.getLeaderId());
            stmt.setInt(6, guild.getPower());
            stmt.setInt(7, guild.getMaxPower());
            stmt.setDouble(8, guild.getBankBalance());
            stmt.setString(9, guild.getHomeWorld());
            stmt.setObject(10, guild.getHomeX());
            stmt.setObject(11, guild.getHomeY());
            stmt.setObject(12, guild.getHomeZ());

            stmt.executeUpdate();
            LOGGER.at(Level.INFO).log("Created guild: " + guild.getName());
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error creating guild " + guild.getName() + ": " + e.getMessage());
            throw new RuntimeException("Failed to create guild", e);
        }
    }

    /**
     * Updates an existing guild.
     */
    public void updateGuild(Guild guild) {
        String sql = """
            UPDATE fg_guilds SET
                name = ?,
                tag = ?,
                leader_uuid = ?,
                power = ?,
                max_power = ?,
                bank_balance = ?,
                home_world = ?,
                home_x = ?,
                home_y = ?,
                home_z = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE guild_id = ?
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, guild.getName());
            stmt.setString(2, guild.getTag());
            stmt.setObject(3, guild.getLeaderId());
            stmt.setInt(4, guild.getPower());
            stmt.setInt(5, guild.getMaxPower());
            stmt.setDouble(6, guild.getBankBalance());
            stmt.setString(7, guild.getHomeWorld());
            stmt.setObject(8, guild.getHomeX());
            stmt.setObject(9, guild.getHomeY());
            stmt.setObject(10, guild.getHomeZ());
            stmt.setObject(11, guild.getId());

            stmt.executeUpdate();
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error updating guild " + guild.getId() + ": " + e.getMessage());
        }
    }

    /**
     * Deletes a guild.
     */
    public void deleteGuild(UUID guildId) {
        String sql = "DELETE FROM fg_guilds WHERE guild_id = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, guildId);
            stmt.executeUpdate();
            LOGGER.at(Level.INFO).log("Deleted guild: " + guildId);
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error deleting guild " + guildId + ": " + e.getMessage());
        }
    }

    /**
     * Gets all guilds in a faction.
     */
    public List<Guild> getGuildsByFaction(String factionId) {
        List<Guild> guilds = new ArrayList<>();
        String sql = """
            SELECT guild_id, name, tag, faction_id, leader_uuid, power, max_power,
                   bank_balance, home_world, home_x, home_y, home_z,
                   created_at, updated_at
            FROM fg_guilds
            WHERE faction_id = ?
            ORDER BY name
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, factionId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    guilds.add(mapResultSetToGuild(rs));
                }
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error getting guilds for faction " + factionId + ": " + e.getMessage());
        }
        return guilds;
    }

    /**
     * Gets all guilds.
     */
    public List<Guild> getAllGuilds() {
        List<Guild> guilds = new ArrayList<>();
        String sql = """
            SELECT guild_id, name, tag, faction_id, leader_uuid, power, max_power,
                   bank_balance, home_world, home_x, home_y, home_z,
                   created_at, updated_at
            FROM fg_guilds
            ORDER BY name
            """;

        try (Connection conn = databaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                guilds.add(mapResultSetToGuild(rs));
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error getting all guilds: " + e.getMessage());
        }
        return guilds;
    }

    // ═══════════════════════════════════════════════════════
    // INVITATION MANAGEMENT
    // ═══════════════════════════════════════════════════════

    /**
     * Creates a guild invitation.
     */
    public void createInvitation(UUID guildId, UUID playerUuid) {
        String sql = """
            INSERT INTO fg_invitations (guild_id, player_uuid)
            VALUES (?, ?)
            ON CONFLICT (guild_id, player_uuid) DO NOTHING
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, guildId);
            stmt.setObject(2, playerUuid);
            stmt.executeUpdate();
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error creating invitation: " + e.getMessage());
        }
    }

    /**
     * Checks if a player has a pending invitation to a guild.
     */
    public boolean hasInvitation(UUID guildId, UUID playerUuid) {
        String sql = "SELECT 1 FROM fg_invitations WHERE guild_id = ? AND player_uuid = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, guildId);
            stmt.setObject(2, playerUuid);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error checking invitation: " + e.getMessage());
        }
        return false;
    }

    /**
     * Gets all guilds a player has invitations to.
     */
    public List<UUID> getPlayerInvitations(UUID playerUuid) {
        List<UUID> guildIds = new ArrayList<>();
        String sql = "SELECT guild_id FROM fg_invitations WHERE player_uuid = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, playerUuid);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    guildIds.add(rs.getObject("guild_id", UUID.class));
                }
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error getting player invitations: " + e.getMessage());
        }
        return guildIds;
    }

    /**
     * Deletes an invitation.
     */
    public void deleteInvitation(UUID guildId, UUID playerUuid) {
        String sql = "DELETE FROM fg_invitations WHERE guild_id = ? AND player_uuid = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, guildId);
            stmt.setObject(2, playerUuid);
            stmt.executeUpdate();
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error deleting invitation: " + e.getMessage());
        }
    }

    /**
     * Deletes all invitations for a guild.
     */
    public void deleteAllInvitations(UUID guildId) {
        String sql = "DELETE FROM fg_invitations WHERE guild_id = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, guildId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error deleting all invitations for guild: " + e.getMessage());
        }
    }

    /**
     * Deletes all invitations for a player.
     */
    public void deletePlayerInvitations(UUID playerUuid) {
        String sql = "DELETE FROM fg_invitations WHERE player_uuid = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, playerUuid);
            stmt.executeUpdate();
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error deleting player invitations: " + e.getMessage());
        }
    }

    /**
     * Gets the count of pending invitations for a player.
     */
    public int getPlayerInvitationCount(UUID playerUuid) {
        String sql = "SELECT COUNT(*) FROM fg_invitations WHERE player_uuid = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, playerUuid);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error counting player invitations: " + e.getMessage());
        }
        return 0;
    }

    /**
     * Gets all pending invitations with guild details for a player.
     * Returns a list of objects containing guildId and invitedAt timestamp.
     */
    public List<Object[]> getPlayerInvitationsDetailed(UUID playerUuid) {
        List<Object[]> invitations = new ArrayList<>();
        String sql = """
            SELECT i.guild_id, i.invited_at, g.name as guild_name
            FROM fg_invitations i
            JOIN fg_guilds g ON i.guild_id = g.guild_id
            WHERE i.player_uuid = ?
            ORDER BY i.invited_at DESC
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, playerUuid);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    UUID guildId = rs.getObject("guild_id", UUID.class);
                    java.sql.Timestamp invitedAt = rs.getTimestamp("invited_at");
                    String guildName = rs.getString("guild_name");
                    invitations.add(new Object[]{guildId, invitedAt, guildName});
                }
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error getting detailed invitations: " + e.getMessage());
        }
        return invitations;
    }

    // ═══════════════════════════════════════════════════════
    // JOIN REQUEST MANAGEMENT
    // ═══════════════════════════════════════════════════════

    /**
     * Creates a join request from a player to a guild.
     */
    public void createJoinRequest(UUID guildId, UUID playerUuid) {
        String sql = """
            INSERT INTO fg_join_requests (guild_id, player_uuid)
            VALUES (?, ?)
            ON CONFLICT (guild_id, player_uuid) DO NOTHING
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, guildId);
            stmt.setObject(2, playerUuid);
            stmt.executeUpdate();
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error creating join request: " + e.getMessage());
        }
    }

    /**
     * Checks if a player has a pending join request to a guild.
     */
    public boolean hasJoinRequest(UUID guildId, UUID playerUuid) {
        String sql = "SELECT 1 FROM fg_join_requests WHERE guild_id = ? AND player_uuid = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, guildId);
            stmt.setObject(2, playerUuid);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error checking join request: " + e.getMessage());
        }
        return false;
    }

    /**
     * Gets all pending join requests for a guild.
     */
    public List<UUID> getGuildJoinRequests(UUID guildId) {
        List<UUID> playerUuids = new ArrayList<>();
        String sql = "SELECT player_uuid FROM fg_join_requests WHERE guild_id = ? ORDER BY requested_at ASC";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, guildId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    playerUuids.add(rs.getObject("player_uuid", UUID.class));
                }
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error getting guild join requests: " + e.getMessage());
        }
        return playerUuids;
    }

    /**
     * Gets the count of pending join requests for a guild.
     */
    public int getGuildJoinRequestCount(UUID guildId) {
        String sql = "SELECT COUNT(*) FROM fg_join_requests WHERE guild_id = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, guildId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error counting guild join requests: " + e.getMessage());
        }
        return 0;
    }

    /**
     * Deletes a join request.
     */
    public void deleteJoinRequest(UUID guildId, UUID playerUuid) {
        String sql = "DELETE FROM fg_join_requests WHERE guild_id = ? AND player_uuid = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, guildId);
            stmt.setObject(2, playerUuid);
            stmt.executeUpdate();
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error deleting join request: " + e.getMessage());
        }
    }

    /**
     * Deletes all join requests for a guild.
     */
    public void deleteAllJoinRequests(UUID guildId) {
        String sql = "DELETE FROM fg_join_requests WHERE guild_id = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, guildId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error deleting all join requests for guild: " + e.getMessage());
        }
    }

    /**
     * Deletes all join requests for a player (e.g., when they join a guild).
     */
    public void deletePlayerJoinRequests(UUID playerUuid) {
        String sql = "DELETE FROM fg_join_requests WHERE player_uuid = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, playerUuid);
            stmt.executeUpdate();
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error deleting player join requests: " + e.getMessage());
        }
    }

    /**
     * Gets all guild IDs that a player has pending join requests to.
     */
    public List<UUID> getPlayerJoinRequests(UUID playerUuid) {
        List<UUID> guildIds = new ArrayList<>();
        String sql = "SELECT guild_id FROM fg_join_requests WHERE player_uuid = ? ORDER BY requested_at ASC";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, playerUuid);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    guildIds.add(rs.getObject("guild_id", UUID.class));
                }
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error getting player join requests: " + e.getMessage());
        }
        return guildIds;
    }

    /**
     * Maps a ResultSet row to Guild.
     */
    private Guild mapResultSetToGuild(ResultSet rs) throws SQLException {
        UUID guildId = rs.getObject("guild_id", UUID.class);
        String name = rs.getString("name");
        String tag = rs.getString("tag");
        String factionId = rs.getString("faction_id");
        UUID leaderId = rs.getObject("leader_uuid", UUID.class);

        Guild guild = new Guild(guildId, name, factionId, leaderId);
        guild.setTag(tag);

        guild.setPower(rs.getInt("power"));
        guild.setMaxPower(rs.getInt("max_power"));
        guild.setBankBalance(rs.getDouble("bank_balance"));

        String homeWorld = rs.getString("home_world");
        if (homeWorld != null) {
            Double homeX = rs.getObject("home_x", Double.class);
            Double homeY = rs.getObject("home_y", Double.class);
            Double homeZ = rs.getObject("home_z", Double.class);
            if (homeX != null && homeY != null && homeZ != null) {
                guild.setHome(homeWorld, homeX, homeY, homeZ);
            }
        }

        Timestamp createdAt = rs.getTimestamp("created_at");
        guild.setCreatedAt(createdAt != null ? createdAt.getTime() : System.currentTimeMillis());

        Timestamp updatedAt = rs.getTimestamp("updated_at");
        guild.setUpdatedAt(updatedAt != null ? updatedAt.getTime() : System.currentTimeMillis());

        return guild;
    }
}
