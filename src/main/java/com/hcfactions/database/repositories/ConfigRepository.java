package com.hcfactions.database.repositories;

import com.hcfactions.config.FactionGuildsConfig;
import com.hcfactions.database.DatabaseManager;
import com.hypixel.hytale.logger.HytaleLogger;

import java.sql.*;
import java.util.logging.Level;

/**
 * Repository for persisting and loading plugin configuration from the database.
 * Uses a key-value table to store config values, making it easy to add new settings.
 */
public class ConfigRepository {

    private static final HytaleLogger LOGGER = HytaleLogger.getLogger().getSubLogger("FactionGuilds-ConfigRepo");

    private final DatabaseManager databaseManager;

    public ConfigRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /**
     * Loads configuration from the database.
     * If a value doesn't exist in the database, uses defaults and saves them.
     *
     * @return The loaded configuration
     */
    public FactionGuildsConfig loadConfig() {
        FactionGuildsConfig config = new FactionGuildsConfig();

        String selectSql = "SELECT config_key, config_value FROM fg_config";

        try (Connection conn = databaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(selectSql)) {

            int loadedCount = 0;
            while (rs.next()) {
                String key = rs.getString("config_key");
                String value = rs.getString("config_value");
                applyConfigValue(config, key, value);
                loadedCount++;
            }

            LOGGER.at(Level.INFO).log("Loaded " + loadedCount + " config values from database");

            // If no config exists, save defaults
            if (loadedCount == 0) {
                LOGGER.at(Level.INFO).log("No config found in database, saving defaults...");
                saveConfig(config);
            }

        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error loading config from database: " + e.getMessage());
            LOGGER.at(Level.INFO).log("Using default configuration values");
        }

        return config;
    }

    /**
     * Saves the configuration to the database.
     * Uses UPSERT to insert or update each config value.
     *
     * @param config The configuration to save
     */
    public void saveConfig(FactionGuildsConfig config) {
        String upsertSql = """
            INSERT INTO fg_config (config_key, config_value, updated_at)
            VALUES (?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (config_key) DO UPDATE SET
                config_value = EXCLUDED.config_value,
                updated_at = CURRENT_TIMESTAMP
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(upsertSql)) {

            // Guild settings
            upsertValue(stmt, "guild.maxNameLength", String.valueOf(config.getGuildMaxNameLength()));
            upsertValue(stmt, "guild.minNameLength", String.valueOf(config.getGuildMinNameLength()));
            upsertValue(stmt, "guild.baseClaimsPerGuild", String.valueOf(config.getGuildBaseClaimsPerGuild()));
            upsertValue(stmt, "guild.claimsPerAdditionalMember", String.valueOf(config.getGuildClaimsPerAdditionalMember()));
            upsertValue(stmt, "guild.maxMembers", String.valueOf(config.getGuildMaxMembers()));
            upsertValue(stmt, "guild.defaultPower", String.valueOf(config.getGuildDefaultPower()));
            upsertValue(stmt, "guild.powerPerClaim", String.valueOf(config.getGuildPowerPerClaim()));
            upsertValue(stmt, "guild.homeCooldownSeconds", String.valueOf(config.getGuildHomeCooldownSeconds()));

            // Protection settings
            upsertValue(stmt, "protection.enemyCanDestroy", String.valueOf(config.isProtectionEnemyCanDestroy()));
            upsertValue(stmt, "protection.enemyCanBuild", String.valueOf(config.isProtectionEnemyCanBuild()));
            upsertValue(stmt, "protection.sameFactionGuildAccess", String.valueOf(config.isProtectionSameFactionGuildAccess()));

            // PvP settings
            upsertValue(stmt, "pvp.allowSameFactionPvp", String.valueOf(config.isPvpAllowSameFactionPvp()));
            upsertValue(stmt, "pvp.protectNoFaction", String.valueOf(config.isPvpProtectNoFaction()));

            // Spawn suppression settings
            upsertValue(stmt, "spawnSuppression.enabled", String.valueOf(config.isSpawnSuppressionEnabled()));

            LOGGER.at(Level.INFO).log("Saved configuration to database");

        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error saving config to database: " + e.getMessage());
        }
    }

    /**
     * Updates a single config value in the database and the config object.
     *
     * @param config The config object to update
     * @param key    The config key
     * @param value  The new value
     * @return true if the update was successful
     */
    public boolean updateConfigValue(FactionGuildsConfig config, String key, String value) {
        String upsertSql = """
            INSERT INTO fg_config (config_key, config_value, updated_at)
            VALUES (?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (config_key) DO UPDATE SET
                config_value = EXCLUDED.config_value,
                updated_at = CURRENT_TIMESTAMP
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(upsertSql)) {

            stmt.setString(1, key);
            stmt.setString(2, value);
            stmt.executeUpdate();

            // Also update the in-memory config
            applyConfigValue(config, key, value);

            LOGGER.at(Level.INFO).log("Updated config: " + key + " = " + value);
            return true;

        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error updating config " + key + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Gets a single config value from the database.
     *
     * @param key The config key
     * @return The value, or null if not found
     */
    public String getConfigValue(String key) {
        String sql = "SELECT config_value FROM fg_config WHERE config_key = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, key);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                return rs.getString("config_value");
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error getting config " + key + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Helper method to execute an upsert for a key-value pair.
     */
    private void upsertValue(PreparedStatement stmt, String key, String value) throws SQLException {
        stmt.setString(1, key);
        stmt.setString(2, value);
        stmt.executeUpdate();
    }

    /**
     * Applies a config value from the database to the config object.
     */
    private void applyConfigValue(FactionGuildsConfig config, String key, String value) {
        try {
            switch (key) {
                // Guild settings
                case "guild.maxNameLength" -> config.setGuildMaxNameLength(Integer.parseInt(value));
                case "guild.minNameLength" -> config.setGuildMinNameLength(Integer.parseInt(value));
                case "guild.baseClaimsPerGuild" -> config.setGuildBaseClaimsPerGuild(Integer.parseInt(value));
                case "guild.claimsPerAdditionalMember" -> config.setGuildClaimsPerAdditionalMember(Integer.parseInt(value));
                case "guild.maxMembers" -> config.setGuildMaxMembers(Integer.parseInt(value));
                case "guild.defaultPower" -> config.setGuildDefaultPower(Integer.parseInt(value));
                case "guild.powerPerClaim" -> config.setGuildPowerPerClaim(Integer.parseInt(value));
                case "guild.homeCooldownSeconds" -> config.setGuildHomeCooldownSeconds(Integer.parseInt(value));

                // Protection settings
                case "protection.enemyCanDestroy" -> config.setProtectionEnemyCanDestroy(Boolean.parseBoolean(value));
                case "protection.enemyCanBuild" -> config.setProtectionEnemyCanBuild(Boolean.parseBoolean(value));
                case "protection.sameFactionGuildAccess" -> config.setProtectionSameFactionGuildAccess(Boolean.parseBoolean(value));

                // PvP settings
                case "pvp.allowSameFactionPvp" -> config.setPvpAllowSameFactionPvp(Boolean.parseBoolean(value));
                case "pvp.protectNoFaction" -> config.setPvpProtectNoFaction(Boolean.parseBoolean(value));

                // Spawn suppression settings
                case "spawnSuppression.enabled" -> config.setSpawnSuppressionEnabled(Boolean.parseBoolean(value));

                default -> LOGGER.at(Level.WARNING).log("Unknown config key: " + key);
            }
        } catch (NumberFormatException e) {
            LOGGER.at(Level.WARNING).log("Invalid value for config " + key + ": " + value);
        }
    }
}
