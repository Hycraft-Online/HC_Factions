package com.hcfactions.managers;

import com.hcfactions.database.DatabaseManager;
import com.hypixel.hytale.logger.HytaleLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * Manages the guild activity log, providing async insert and query operations.
 * All database operations run on a dedicated thread pool to avoid blocking the world thread.
 */
public class GuildLogManager {

    private static final HytaleLogger LOGGER = HytaleLogger.getLogger().getSubLogger("FactionGuilds-GuildLog");

    private static final int DEFAULT_MAX_ENTRIES = 200;

    private static final String INSERT_SQL =
            "INSERT INTO fg_guild_logs (guild_id, event_type, actor_uuid, target_uuid, details) VALUES (?, ?, ?, ?, ?)";

    private static final String SELECT_RECENT_SQL =
            "SELECT id, guild_id, event_type, actor_uuid, target_uuid, details, created_at " +
            "FROM fg_guild_logs WHERE guild_id = ? ORDER BY created_at DESC LIMIT ?";

    private static final String SELECT_BY_TYPE_SQL =
            "SELECT id, guild_id, event_type, actor_uuid, target_uuid, details, created_at " +
            "FROM fg_guild_logs WHERE guild_id = ? AND event_type = ? ORDER BY created_at DESC LIMIT ?";

    private static final String COUNT_SQL =
            "SELECT COUNT(*) FROM fg_guild_logs WHERE guild_id = ?";

    private static final String PRUNE_SQL =
            "DELETE FROM fg_guild_logs WHERE guild_id = ? AND id NOT IN " +
            "(SELECT id FROM fg_guild_logs WHERE guild_id = ? ORDER BY created_at DESC LIMIT ?)";

    private final DatabaseManager databaseManager;
    private final ExecutorService executor;
    private final int maxEntries;

    public GuildLogManager(DatabaseManager databaseManager) {
        this(databaseManager, DEFAULT_MAX_ENTRIES);
    }

    public GuildLogManager(DatabaseManager databaseManager, int maxEntries) {
        this.databaseManager = databaseManager;
        this.maxEntries = maxEntries;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "GuildLog-IO");
            t.setDaemon(true);
            return t;
        });
        LOGGER.at(Level.INFO).log("GuildLogManager initialized (maxEntries=" + maxEntries + ")");
    }

    // ═══════════════════════════════════════════════════════
    // LOG INSERTION (async, fire-and-forget)
    // ═══════════════════════════════════════════════════════

    /**
     * Logs a guild event with actor and target.
     */
    public void logEvent(UUID guildId, GuildLogType eventType, UUID actorUuid, UUID targetUuid, String details) {
        executor.submit(() -> {
            try {
                insertLog(guildId, eventType, actorUuid, targetUuid, details);
                pruneIfNeeded(guildId);
            } catch (Exception e) {
                LOGGER.at(Level.WARNING).log("Failed to log guild event: " + e.getMessage());
            }
        });
    }

    /**
     * Logs a guild event with actor only (no target).
     */
    public void logEvent(UUID guildId, GuildLogType eventType, UUID actorUuid, String details) {
        logEvent(guildId, eventType, actorUuid, null, details);
    }

    // ═══════════════════════════════════════════════════════
    // QUERY METHODS (synchronous - call from off-world-thread)
    // ═══════════════════════════════════════════════════════

    /**
     * Gets recent log entries for a guild.
     *
     * @param guildId Guild to query
     * @param limit   Maximum number of entries to return
     * @return List of log entries, newest first
     */
    public List<GuildLogEntry> getRecentLogs(UUID guildId, int limit) {
        List<GuildLogEntry> entries = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_RECENT_SQL)) {
            ps.setObject(1, guildId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            LOGGER.at(Level.WARNING).log("Failed to query recent guild logs: " + e.getMessage());
        }
        return entries;
    }

    /**
     * Gets log entries for a guild filtered by event type.
     *
     * @param guildId Guild to query
     * @param type    Event type to filter by
     * @param limit   Maximum number of entries to return
     * @return List of log entries, newest first
     */
    public List<GuildLogEntry> getLogsByType(UUID guildId, GuildLogType type, int limit) {
        List<GuildLogEntry> entries = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BY_TYPE_SQL)) {
            ps.setObject(1, guildId);
            ps.setString(2, type.name());
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            LOGGER.at(Level.WARNING).log("Failed to query guild logs by type: " + e.getMessage());
        }
        return entries;
    }

    // ═══════════════════════════════════════════════════════
    // INTERNAL
    // ═══════════════════════════════════════════════════════

    private void insertLog(UUID guildId, GuildLogType eventType, UUID actorUuid, UUID targetUuid, String details) throws SQLException {
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
            ps.setObject(1, guildId);
            ps.setString(2, eventType.name());
            if (actorUuid != null) {
                ps.setObject(3, actorUuid);
            } else {
                ps.setNull(3, Types.OTHER);
            }
            if (targetUuid != null) {
                ps.setObject(4, targetUuid);
            } else {
                ps.setNull(4, Types.OTHER);
            }
            ps.setString(5, details);
            ps.executeUpdate();
        }
    }

    private void pruneIfNeeded(UUID guildId) {
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement countPs = conn.prepareStatement(COUNT_SQL)) {
            countPs.setObject(1, guildId);
            try (ResultSet rs = countPs.executeQuery()) {
                if (rs.next() && rs.getInt(1) > maxEntries) {
                    try (PreparedStatement prunePs = conn.prepareStatement(PRUNE_SQL)) {
                        prunePs.setObject(1, guildId);
                        prunePs.setObject(2, guildId);
                        prunePs.setInt(3, maxEntries);
                        int deleted = prunePs.executeUpdate();
                        if (deleted > 0) {
                            LOGGER.at(Level.INFO).log("Pruned " + deleted + " old log entries for guild " + guildId);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            LOGGER.at(Level.WARNING).log("Failed to prune guild logs: " + e.getMessage());
        }
    }

    private GuildLogEntry mapRow(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        UUID guildId = (UUID) rs.getObject("guild_id");
        GuildLogType eventType;
        try {
            eventType = GuildLogType.valueOf(rs.getString("event_type"));
        } catch (IllegalArgumentException e) {
            // Unknown event type in DB - shouldn't happen but handle gracefully
            eventType = null;
        }
        UUID actorUuid = (UUID) rs.getObject("actor_uuid");
        UUID targetUuid = (UUID) rs.getObject("target_uuid");
        String details = rs.getString("details");
        Timestamp ts = rs.getTimestamp("created_at");
        Instant createdAt = ts != null ? ts.toInstant() : Instant.now();
        return new GuildLogEntry(id, guildId, eventType, actorUuid, targetUuid, details, createdAt);
    }

    /**
     * Shuts down the background executor. Call during plugin shutdown.
     */
    public void shutdown() {
        executor.shutdown();
        LOGGER.at(Level.INFO).log("GuildLogManager shut down");
    }
}
