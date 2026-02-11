package com.hcfactions.database.repositories;

import com.hcfactions.database.DatabaseManager;
import com.hcfactions.models.GuildRole;
import com.hcfactions.models.PlayerData;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nullable;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;

/**
 * Repository for player faction and guild data persistence.
 */
public class PlayerDataRepository {

    private static final HytaleLogger LOGGER = HytaleLogger.getLogger().getSubLogger("FactionGuilds-PlayerRepo");

    private final DatabaseManager databaseManager;

    public PlayerDataRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /**
     * Gets player data by UUID.
     */
    @Nullable
    public PlayerData getPlayerData(UUID playerUuid) {
        String sql = """
            SELECT player_uuid, player_name, faction_id, guild_id, guild_role,
                   guild_joined_at, has_chosen_faction, created_at, updated_at,
                   home_world, home_x, home_y, home_z
            FROM fg_player_data
            WHERE player_uuid = ?
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, playerUuid);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                return mapResultSetToPlayerData(rs);
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error getting player data for " + playerUuid + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Gets player data by name (case-insensitive).
     */
    @Nullable
    public PlayerData getPlayerDataByName(String playerName) {
        String sql = """
            SELECT player_uuid, player_name, faction_id, guild_id, guild_role,
                   guild_joined_at, has_chosen_faction, created_at, updated_at,
                   home_world, home_x, home_y, home_z
            FROM fg_player_data
            WHERE LOWER(player_name) = LOWER(?)
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, playerName);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                return mapResultSetToPlayerData(rs);
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error getting player data by name " + playerName + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Gets or creates player data.
     */
    public PlayerData getOrCreatePlayerData(UUID playerUuid) {
        PlayerData existing = getPlayerData(playerUuid);
        if (existing != null) {
            return existing;
        }

        PlayerData newData = new PlayerData(playerUuid);
        savePlayerData(newData);
        return newData;
    }

    /**
     * Saves player data (insert or update).
     */
    public void savePlayerData(PlayerData data) {
        String sql = """
            INSERT INTO fg_player_data (
                player_uuid, player_name, faction_id, guild_id, guild_role,
                guild_joined_at, has_chosen_faction, home_world, home_x, home_y, home_z, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (player_uuid) DO UPDATE SET
                player_name = EXCLUDED.player_name,
                faction_id = EXCLUDED.faction_id,
                guild_id = EXCLUDED.guild_id,
                guild_role = EXCLUDED.guild_role,
                guild_joined_at = EXCLUDED.guild_joined_at,
                has_chosen_faction = EXCLUDED.has_chosen_faction,
                home_world = EXCLUDED.home_world,
                home_x = EXCLUDED.home_x,
                home_y = EXCLUDED.home_y,
                home_z = EXCLUDED.home_z,
                updated_at = CURRENT_TIMESTAMP
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, data.getPlayerUuid());
            stmt.setString(2, data.getPlayerName());
            stmt.setString(3, data.getFactionId());
            stmt.setObject(4, data.getGuildId());
            stmt.setString(5, data.getGuildRole() != null ? data.getGuildRole().name() : null);
            stmt.setTimestamp(6, data.getGuildJoinedAt() > 0 ? new Timestamp(data.getGuildJoinedAt()) : null);
            stmt.setBoolean(7, data.hasChosenFaction());
            stmt.setString(8, data.getHomeWorld());
            stmt.setObject(9, data.getHomeX());
            stmt.setObject(10, data.getHomeY());
            stmt.setObject(11, data.getHomeZ());

            stmt.executeUpdate();
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error saving player data for " + data.getPlayerUuid() + ": " + e.getMessage());
        }
    }

    /**
     * Gets all members of a guild.
     */
    public List<PlayerData> getGuildMembers(UUID guildId) {
        List<PlayerData> members = new ArrayList<>();
        String sql = """
            SELECT player_uuid, player_name, faction_id, guild_id, guild_role,
                   guild_joined_at, has_chosen_faction, created_at, updated_at,
                   home_world, home_x, home_y, home_z
            FROM fg_player_data
            WHERE guild_id = ?
            ORDER BY 
                CASE guild_role 
                    WHEN 'LEADER' THEN 1
                    WHEN 'OFFICER' THEN 2
                    WHEN 'MEMBER' THEN 3
                    WHEN 'RECRUIT' THEN 4
                    ELSE 5
                END
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, guildId);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                members.add(mapResultSetToPlayerData(rs));
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error getting guild members for " + guildId + ": " + e.getMessage());
        }
        return members;
    }

    /**
     * Gets count of members in a guild.
     */
    public int getGuildMemberCount(UUID guildId) {
        String sql = "SELECT COUNT(*) FROM fg_player_data WHERE guild_id = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, guildId);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error getting guild member count for " + guildId + ": " + e.getMessage());
        }
        return 0;
    }

    /**
     * Gets all players in a faction.
     */
    public List<UUID> getFactionMembers(String factionId) {
        List<UUID> members = new ArrayList<>();
        String sql = "SELECT player_uuid FROM fg_player_data WHERE faction_id = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, factionId);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                members.add(rs.getObject("player_uuid", UUID.class));
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error getting faction members for " + factionId + ": " + e.getMessage());
        }
        return members;
    }

    /**
     * Removes a player from their guild.
     */
    public void removeFromGuild(UUID playerUuid) {
        String sql = """
            UPDATE fg_player_data
            SET guild_id = NULL,
                guild_role = NULL,
                guild_joined_at = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE player_uuid = ?
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, playerUuid);
            stmt.executeUpdate();
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error removing player from guild: " + playerUuid + ": " + e.getMessage());
        }
    }

    /**
     * Removes all members from a guild (for disbanding).
     */
    public void removeAllGuildMembers(UUID guildId) {
        String sql = """
            UPDATE fg_player_data
            SET guild_id = NULL,
                guild_role = NULL,
                guild_joined_at = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE guild_id = ?
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, guildId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error removing all guild members for " + guildId + ": " + e.getMessage());
        }
    }

    /**
     * Maps a ResultSet row to PlayerData.
     */
    private PlayerData mapResultSetToPlayerData(ResultSet rs) throws SQLException {
        UUID playerUuid = rs.getObject("player_uuid", UUID.class);
        PlayerData data = new PlayerData(playerUuid);

        data.setPlayerName(rs.getString("player_name"));
        data.setFactionId(rs.getString("faction_id"));
        data.setGuildId(rs.getObject("guild_id", UUID.class));
        
        String roleStr = rs.getString("guild_role");
        data.setGuildRole(roleStr != null ? GuildRole.fromString(roleStr) : null);
        
        Timestamp guildJoinedAt = rs.getTimestamp("guild_joined_at");
        data.setGuildJoinedAt(guildJoinedAt != null ? guildJoinedAt.getTime() : 0);

        data.setHasChosenFaction(rs.getBoolean("has_chosen_faction"));

        Timestamp createdAt = rs.getTimestamp("created_at");
        data.setCreatedAt(createdAt != null ? createdAt.getTime() : System.currentTimeMillis());

        Timestamp updatedAt = rs.getTimestamp("updated_at");
        data.setUpdatedAt(updatedAt != null ? updatedAt.getTime() : System.currentTimeMillis());

        // Load home location
        String homeWorld = rs.getString("home_world");
        if (homeWorld != null) {
            Double homeX = rs.getObject("home_x", Double.class);
            Double homeY = rs.getObject("home_y", Double.class);
            Double homeZ = rs.getObject("home_z", Double.class);
            if (homeX != null && homeY != null && homeZ != null) {
                data.setHome(homeWorld, homeX, homeY, homeZ);
            }
        }

        return data;
    }
}
