package com.hcfactions.config;

import com.hypixel.hytale.logger.HytaleLogger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.logging.Level;

/**
 * Handles loading database connection configuration from a properties file.
 * This is loaded BEFORE database connection, so it must be file-based.
 */
public class DatabaseConfig {

    private static final HytaleLogger LOGGER = HytaleLogger.getLogger().getSubLogger("FactionGuilds-DBConfig");

    // Default values (for Docker container-to-container communication)
    private static final String DEFAULT_URL = "jdbc:postgresql://postgres:5432/factionwars";
    private static final String DEFAULT_USER = "factionwars";
    private static final String DEFAULT_PASSWORD = "factionwars_secret";
    private static final int DEFAULT_POOL_SIZE = 10;

    private final String url;
    private final String username;
    private final String password;
    private final int poolSize;

    /**
     * Creates a DatabaseConfig with the specified values.
     */
    public DatabaseConfig(String url, String username, String password, int poolSize) {
        this.url = url;
        this.username = username;
        this.password = password;
        this.poolSize = poolSize;
    }

    /**
     * Loads database configuration from the properties file.
     * If the file doesn't exist, creates it with default values.
     *
     * @param modFolder The mod's data folder (e.g., mods/FactionGuilds)
     * @return The loaded configuration
     */
    public static DatabaseConfig load(File modFolder) {
        File configFile = new File(modFolder, "database.properties");

        if (!configFile.exists()) {
            LOGGER.at(Level.INFO).log("database.properties not found, creating with defaults...");
            createDefaultConfig(configFile);
        }

        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(configFile)) {
            props.load(fis);
        } catch (IOException e) {
            LOGGER.at(Level.WARNING).log("Failed to load database.properties: " + e.getMessage());
            LOGGER.at(Level.WARNING).log("Using default database configuration");
            return createDefault();
        }

        String url = props.getProperty("db.url", DEFAULT_URL);
        String username = props.getProperty("db.username", DEFAULT_USER);
        String password = props.getProperty("db.password", DEFAULT_PASSWORD);
        int poolSize = DEFAULT_POOL_SIZE;

        try {
            poolSize = Integer.parseInt(props.getProperty("db.pool.size", String.valueOf(DEFAULT_POOL_SIZE)));
        } catch (NumberFormatException e) {
            LOGGER.at(Level.WARNING).log("Invalid db.pool.size, using default: " + DEFAULT_POOL_SIZE);
        }

        LOGGER.at(Level.INFO).log("Loaded database config from " + configFile.getAbsolutePath());
        LOGGER.at(Level.INFO).log("  URL: " + url);
        LOGGER.at(Level.INFO).log("  User: " + username);
        LOGGER.at(Level.INFO).log("  Pool Size: " + poolSize);

        return new DatabaseConfig(url, username, password, poolSize);
    }

    /**
     * Creates a default configuration.
     */
    public static DatabaseConfig createDefault() {
        return new DatabaseConfig(DEFAULT_URL, DEFAULT_USER, DEFAULT_PASSWORD, DEFAULT_POOL_SIZE);
    }

    /**
     * Creates the default configuration file.
     */
    private static void createDefaultConfig(File configFile) {
        // Ensure parent directory exists
        File parent = configFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        Properties props = new Properties();
        props.setProperty("db.url", DEFAULT_URL);
        props.setProperty("db.username", DEFAULT_USER);
        props.setProperty("db.password", DEFAULT_PASSWORD);
        props.setProperty("db.pool.size", String.valueOf(DEFAULT_POOL_SIZE));

        try (FileOutputStream fos = new FileOutputStream(configFile)) {
            props.store(fos, """
                FactionGuilds Database Configuration
                
                For Docker (default): Use postgres:5432 as the host
                For external access: Use localhost:5432 (or your DB host)
                
                The database tables are created automatically on first plugin load.
                Tables are prefixed with fg_ (FactionGuilds) to avoid conflicts.
                """);
            LOGGER.at(Level.INFO).log("Created default database.properties at " + configFile.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.at(Level.SEVERE).log("Failed to create database.properties: " + e.getMessage());
        }
    }

    public String getUrl() {
        return url;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public int getPoolSize() {
        return poolSize;
    }
}
