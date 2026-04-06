package com.hcfactions.database.repositories;

import com.hcfactions.database.DatabaseManager;
import com.hcfactions.models.Faction;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nullable;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;

/**
 * Repository for faction data persistence.
 * Factions are admin-defined major factions (e.g., Alliance, Horde).
 */
public class FactionRepository {

    private static final HytaleLogger LOGGER = HytaleLogger.getLogger().getSubLogger("FactionGuilds-FactionRepo");

    private final DatabaseManager databaseManager;

    public FactionRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /**
     * Gets all factions from the database.
     */
    public List<Faction> getAllFactions() {
        List<Faction> factions = new ArrayList<>();
        String sql = """
            SELECT faction_id, display_name, color, spawn_world, spawn_x, spawn_y, spawn_z,
                   rtp_spawn_x, rtp_spawn_y, rtp_spawn_z
            FROM fg_factions
            ORDER BY display_name
            """;

        try (Connection conn = databaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                factions.add(mapResultSetToFaction(rs));
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error getting all factions: " + e.getMessage());
        }
        return factions;
    }

    /**
     * Gets a faction by ID.
     */
    @Nullable
    public Faction getFaction(String factionId) {
        String sql = """
            SELECT faction_id, display_name, color, spawn_world, spawn_x, spawn_y, spawn_z,
                   rtp_spawn_x, rtp_spawn_y, rtp_spawn_z
            FROM fg_factions
            WHERE faction_id = ?
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, factionId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapResultSetToFaction(rs);
                }
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error getting faction " + factionId + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Creates a new faction.
     */
    public void createFaction(Faction faction) {
        String sql = """
            INSERT INTO fg_factions (faction_id, display_name, color, spawn_world, spawn_x, spawn_y, spawn_z,
                                     rtp_spawn_x, rtp_spawn_y, rtp_spawn_z)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, faction.getId());
            stmt.setString(2, faction.getDisplayName());
            stmt.setString(3, faction.getColorHex());
            stmt.setString(4, faction.getSpawnWorld());
            stmt.setDouble(5, faction.getSpawnX());
            stmt.setDouble(6, faction.getSpawnY());
            stmt.setDouble(7, faction.getSpawnZ());
            setNullableDouble(stmt, 8, faction.getRtpSpawnX());
            setNullableDouble(stmt, 9, faction.getRtpSpawnY());
            setNullableDouble(stmt, 10, faction.getRtpSpawnZ());

            stmt.executeUpdate();
            LOGGER.at(Level.INFO).log("Created faction: " + faction.getDisplayName() + " (" + faction.getId() + ")");
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error creating faction " + faction.getId() + ": " + e.getMessage());
            throw new RuntimeException("Failed to create faction", e);
        }
    }

    /**
     * Updates an existing faction.
     */
    public void updateFaction(Faction faction) {
        String sql = """
            UPDATE fg_factions SET
                display_name = ?,
                color = ?,
                spawn_world = ?,
                spawn_x = ?,
                spawn_y = ?,
                spawn_z = ?,
                rtp_spawn_x = ?,
                rtp_spawn_y = ?,
                rtp_spawn_z = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE faction_id = ?
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, faction.getDisplayName());
            stmt.setString(2, faction.getColorHex());
            stmt.setString(3, faction.getSpawnWorld());
            stmt.setDouble(4, faction.getSpawnX());
            stmt.setDouble(5, faction.getSpawnY());
            stmt.setDouble(6, faction.getSpawnZ());
            setNullableDouble(stmt, 7, faction.getRtpSpawnX());
            setNullableDouble(stmt, 8, faction.getRtpSpawnY());
            setNullableDouble(stmt, 9, faction.getRtpSpawnZ());
            stmt.setString(10, faction.getId());

            int updated = stmt.executeUpdate();
            if (updated > 0) {
                LOGGER.at(Level.INFO).log("Updated faction: " + faction.getId());
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error updating faction " + faction.getId() + ": " + e.getMessage());
        }
    }

    /**
     * Deletes a faction.
     * WARNING: This should only be used carefully as it may orphan player data.
     */
    public boolean deleteFaction(String factionId) {
        String sql = "DELETE FROM fg_factions WHERE faction_id = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, factionId);
            int deleted = stmt.executeUpdate();
            
            if (deleted > 0) {
                LOGGER.at(Level.INFO).log("Deleted faction: " + factionId);
                return true;
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error deleting faction " + factionId + ": " + e.getMessage());
        }
        return false;
    }

    /**
     * Checks if a faction exists.
     */
    public boolean exists(String factionId) {
        String sql = "SELECT 1 FROM fg_factions WHERE faction_id = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, factionId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error checking faction existence: " + e.getMessage());
        }
        return false;
    }

    /**
     * Gets the count of factions.
     */
    public int getFactionCount() {
        String sql = "SELECT COUNT(*) FROM fg_factions";

        try (Connection conn = databaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error getting faction count: " + e.getMessage());
        }
        return 0;
    }

    /**
     * Seeds default factions if none exist.
     */
    public void seedDefaultFactions() {
        if (getFactionCount() > 0) {
            LOGGER.at(Level.INFO).log("Factions already exist, skipping seed");
            // Still ensure RTP spawn values are set for existing factions
            ensureRtpSpawnValues();
            return;
        }

        LOGGER.at(Level.INFO).log("No factions found, seeding defaults...");

        // Create default factions with RTP spawn points
        // Capital locations and RTP spawn points (where players spawn after starter area)
        createFaction(new Faction(
            "alliance", "Kingdom of Valor", "#D4AF37",
            "default", 100.0, 64.0, 100.0,  // Capital (placeholder)
            580.0, 132.0, 126.0             // RTP spawn (between capitals)
        ));

        createFaction(new Faction(
            "horde", "The Iron Legion", "#FF6B6B",
            "default", -100.0, 64.0, -100.0,  // Capital (placeholder)
            -1315.0, 117.0, 402.0             // RTP spawn (between capitals)
        ));

        LOGGER.at(Level.INFO).log("Seeded 2 default factions with RTP spawn points");
    }

    /**
     * Ensures RTP spawn values are set for existing factions.
     * Called on startup to migrate existing data.
     */
    public void ensureRtpSpawnValues() {
        // Check if alliance/valor needs RTP spawn
        Faction alliance = getFaction("alliance");
        if (alliance != null && !alliance.hasRtpSpawn()) {
            updateRtpSpawn("alliance", 580.0, 132.0, 126.0);
            LOGGER.at(Level.INFO).log("Set RTP spawn for alliance faction");
        }

        // Check if horde/legion needs RTP spawn
        Faction horde = getFaction("horde");
        if (horde != null && !horde.hasRtpSpawn()) {
            updateRtpSpawn("horde", -1315.0, 117.0, 402.0);
            LOGGER.at(Level.INFO).log("Set RTP spawn for horde faction");
        }
    }

    /**
     * Updates only the RTP spawn coordinates for a faction.
     */
    public void updateRtpSpawn(String factionId, double x, double y, double z) {
        String sql = """
            UPDATE fg_factions SET
                rtp_spawn_x = ?,
                rtp_spawn_y = ?,
                rtp_spawn_z = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE faction_id = ?
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setDouble(1, x);
            stmt.setDouble(2, y);
            stmt.setDouble(3, z);
            stmt.setString(4, factionId);

            int updated = stmt.executeUpdate();
            if (updated > 0) {
                LOGGER.at(Level.INFO).log("Updated RTP spawn for faction %s: (%.0f, %.0f, %.0f)",
                    factionId, x, y, z);
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error updating RTP spawn for " + factionId + ": " + e.getMessage());
        }
    }

    /**
     * Maps a ResultSet row to Faction.
     */
    private Faction mapResultSetToFaction(ResultSet rs) throws SQLException {
        return new Faction(
            rs.getString("faction_id"),
            rs.getString("display_name"),
            rs.getString("color"),
            rs.getString("spawn_world"),
            rs.getDouble("spawn_x"),
            rs.getDouble("spawn_y"),
            rs.getDouble("spawn_z"),
            getNullableDouble(rs, "rtp_spawn_x"),
            getNullableDouble(rs, "rtp_spawn_y"),
            getNullableDouble(rs, "rtp_spawn_z")
        );
    }

    /**
     * Helper to get nullable Double from ResultSet.
     */
    private Double getNullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    /**
     * Helper to set nullable Double in PreparedStatement.
     */
    private void setNullableDouble(PreparedStatement stmt, int index, Double value) throws SQLException {
        if (value == null) {
            stmt.setNull(index, Types.DOUBLE);
        } else {
            stmt.setDouble(index, value);
        }
    }
}
