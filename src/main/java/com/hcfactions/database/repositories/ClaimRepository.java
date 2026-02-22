package com.hcfactions.database.repositories;

import com.hcfactions.database.DatabaseManager;
import com.hcfactions.models.Claim;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nullable;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;

/**
 * Repository for chunk claim persistence.
 */
public class ClaimRepository {

    private static final HytaleLogger LOGGER = HytaleLogger.getLogger().getSubLogger("FactionGuilds-ClaimRepo");

    private final DatabaseManager databaseManager;

    public ClaimRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /**
     * Gets a claim at a specific location.
     */
    @Nullable
    public Claim getClaim(String world, int chunkX, int chunkZ) {
        String sql = """
            SELECT chunk_x, chunk_z, world, guild_id, faction_id, player_owner_id, suppressor_uuid, claimed_at
            FROM fg_claims
            WHERE world = ? AND chunk_x = ? AND chunk_z = ?
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, world);
            stmt.setInt(2, chunkX);
            stmt.setInt(3, chunkZ);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                return mapResultSetToClaim(rs);
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error getting claim at " + world + ":" + chunkX + ":" + chunkZ + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Creates a new claim.
     */
    public void createClaim(Claim claim) {
        try (Connection conn = databaseManager.getConnection()) {
            try {
                insertClaim(conn, claim);
                return;
            } catch (SQLException e) {
                if (isClaimIdPrimaryKeyViolation(e)) {
                    LOGGER.at(Level.WARNING).log(
                        "Detected out-of-sync fg_claims claim_id sequence. Attempting automatic repair."
                    );
                    repairClaimIdSequenceAndRetry(conn, claim, e);
                    return;
                }
                LOGGER.at(Level.SEVERE).log("Error creating claim: " + e.getMessage());
                throw new RuntimeException("Failed to create claim", e);
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error creating claim: " + e.getMessage());
            throw new RuntimeException("Failed to create claim", e);
        }
    }

    private void insertClaim(Connection conn, Claim claim) throws SQLException {
        String sql = """
            INSERT INTO fg_claims (chunk_x, chunk_z, world, guild_id, faction_id, player_owner_id)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, claim.getChunkX());
            stmt.setInt(2, claim.getChunkZ());
            stmt.setString(3, claim.getWorld());
            stmt.setObject(4, claim.getGuildId());
            stmt.setString(5, claim.getFactionId());
            stmt.setObject(6, claim.getPlayerOwnerId());
            stmt.executeUpdate();
        }
    }

    private void repairClaimIdSequenceAndRetry(Connection conn, Claim claim, SQLException originalError) {
        boolean originalAutoCommit;
        boolean startedTransaction = false;
        try {
            originalAutoCommit = conn.getAutoCommit();
            if (originalAutoCommit) {
                conn.setAutoCommit(false);
                startedTransaction = true;
            }

            try (Statement stmt = conn.createStatement()) {
                stmt.execute("LOCK TABLE fg_claims IN EXCLUSIVE MODE");
                stmt.execute("""
                    SELECT setval(
                        pg_get_serial_sequence('fg_claims', 'claim_id'),
                        GREATEST(COALESCE((SELECT MAX(claim_id) FROM fg_claims), 0) + 1, 1),
                        false
                    )
                    """);
            }

            insertClaim(conn, claim);

            if (startedTransaction) {
                conn.commit();
                conn.setAutoCommit(true);
            }

            LOGGER.at(Level.INFO).log("Repaired fg_claims claim_id sequence and inserted claim successfully.");
        } catch (SQLException retryError) {
            if (startedTransaction) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackError) {
                    LOGGER.at(Level.SEVERE).log("Failed to roll back claim insert transaction: " + rollbackError.getMessage());
                }
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException resetError) {
                    LOGGER.at(Level.SEVERE).log("Failed to reset auto-commit after claim insert failure: " + resetError.getMessage());
                }
            }

            LOGGER.at(Level.SEVERE).log("Error creating claim after sequence repair attempt: " + retryError.getMessage());
            retryError.addSuppressed(originalError);
            throw new RuntimeException("Failed to create claim", retryError);
        }
    }

    private boolean isClaimIdPrimaryKeyViolation(SQLException exception) {
        if (!"23505".equals(exception.getSQLState())) {
            return false;
        }

        for (SQLException current = exception; current != null; current = current.getNextException()) {
            String message = current.getMessage();
            if (message != null
                && message.contains("fg_claims_pkey")
                && message.contains("claim_id")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Deletes a claim at a specific location.
     */
    public void deleteClaim(String world, int chunkX, int chunkZ) {
        String sql = "DELETE FROM fg_claims WHERE world = ? AND chunk_x = ? AND chunk_z = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, world);
            stmt.setInt(2, chunkX);
            stmt.setInt(3, chunkZ);
            stmt.executeUpdate();
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error deleting claim: " + e.getMessage());
        }
    }

    /**
     * Gets all claims for a guild.
     */
    public List<Claim> getGuildClaims(UUID guildId) {
        List<Claim> claims = new ArrayList<>();
        String sql = """
            SELECT chunk_x, chunk_z, world, guild_id, faction_id, player_owner_id, suppressor_uuid, claimed_at
            FROM fg_claims
            WHERE guild_id = ?
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, guildId);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                claims.add(mapResultSetToClaim(rs));
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error getting guild claims for " + guildId + ": " + e.getMessage());
        }
        return claims;
    }

    /**
     * Gets count of claims for a guild.
     */
    public int getGuildClaimCount(UUID guildId) {
        String sql = "SELECT COUNT(*) FROM fg_claims WHERE guild_id = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, guildId);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error getting claim count for " + guildId + ": " + e.getMessage());
        }
        return 0;
    }

    /**
     * Deletes all claims for a guild.
     */
    public void deleteGuildClaims(UUID guildId) {
        String sql = "DELETE FROM fg_claims WHERE guild_id = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, guildId);
            int deleted = stmt.executeUpdate();
            LOGGER.at(Level.INFO).log("Deleted " + deleted + " claims for guild " + guildId);
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error deleting guild claims: " + e.getMessage());
        }
    }

    /**
     * Gets all claims in a world within a chunk radius.
     */
    public List<Claim> getClaimsInRadius(String world, int centerX, int centerZ, int radius) {
        List<Claim> claims = new ArrayList<>();
        String sql = """
            SELECT chunk_x, chunk_z, world, guild_id, faction_id, player_owner_id, suppressor_uuid, claimed_at
            FROM fg_claims
            WHERE world = ?
              AND chunk_x >= ? AND chunk_x <= ?
              AND chunk_z >= ? AND chunk_z <= ?
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, world);
            stmt.setInt(2, centerX - radius);
            stmt.setInt(3, centerX + radius);
            stmt.setInt(4, centerZ - radius);
            stmt.setInt(5, centerZ + radius);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                claims.add(mapResultSetToClaim(rs));
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error getting claims in radius: " + e.getMessage());
        }
        return claims;
    }

    /**
     * Gets all claims for a faction.
     */
    public List<Claim> getFactionClaims(String factionId) {
        List<Claim> claims = new ArrayList<>();
        String sql = """
            SELECT chunk_x, chunk_z, world, guild_id, faction_id, player_owner_id, suppressor_uuid, claimed_at
            FROM fg_claims
            WHERE faction_id = ?
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, factionId);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                claims.add(mapResultSetToClaim(rs));
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error getting faction claims for " + factionId + ": " + e.getMessage());
        }
        return claims;
    }

    /**
     * Checks if a chunk is claimed.
     */
    public boolean isClaimed(String world, int chunkX, int chunkZ) {
        String sql = "SELECT 1 FROM fg_claims WHERE world = ? AND chunk_x = ? AND chunk_z = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, world);
            stmt.setInt(2, chunkX);
            stmt.setInt(3, chunkZ);
            ResultSet rs = stmt.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error checking if claimed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Updates the suppressor UUID for a claim.
     */
    public void updateSuppressorUuid(String world, int chunkX, int chunkZ, UUID suppressorUuid) {
        String sql = "UPDATE fg_claims SET suppressor_uuid = ? WHERE world = ? AND chunk_x = ? AND chunk_z = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, suppressorUuid);
            stmt.setString(2, world);
            stmt.setInt(3, chunkX);
            stmt.setInt(4, chunkZ);
            stmt.executeUpdate();
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error updating suppressor UUID: " + e.getMessage());
        }
    }

    /**
     * Gets all claims that have suppressor UUIDs (for initialization).
     */
    public List<Claim> getAllClaimsWithSuppressors() {
        List<Claim> claims = new ArrayList<>();
        String sql = """
            SELECT chunk_x, chunk_z, world, guild_id, faction_id, player_owner_id, suppressor_uuid, claimed_at
            FROM fg_claims
            WHERE suppressor_uuid IS NOT NULL
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                claims.add(mapResultSetToClaim(rs));
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error getting claims with suppressors: " + e.getMessage());
        }
        return claims;
    }

    /**
     * Gets all claims (for suppressor initialization).
     */
    public List<Claim> getAllClaims() {
        List<Claim> claims = new ArrayList<>();
        String sql = """
            SELECT chunk_x, chunk_z, world, guild_id, faction_id, player_owner_id, suppressor_uuid, claimed_at
            FROM fg_claims
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                claims.add(mapResultSetToClaim(rs));
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error getting all claims: " + e.getMessage());
        }
        return claims;
    }

    /**
     * Maps a ResultSet row to Claim.
     */
    private Claim mapResultSetToClaim(ResultSet rs) throws SQLException {
        int chunkX = rs.getInt("chunk_x");
        int chunkZ = rs.getInt("chunk_z");
        String world = rs.getString("world");
        UUID guildId = rs.getObject("guild_id", UUID.class);
        String factionId = rs.getString("faction_id");
        UUID playerOwnerId = rs.getObject("player_owner_id", UUID.class);
        UUID suppressorUuid = rs.getObject("suppressor_uuid", UUID.class);

        Timestamp claimedAt = rs.getTimestamp("claimed_at");
        long claimedAtMs = claimedAt != null ? claimedAt.getTime() : System.currentTimeMillis();

        return new Claim(chunkX, chunkZ, world, guildId, factionId, playerOwnerId, suppressorUuid, claimedAtMs);
    }

    // ========== Solo Player Claims ==========

    /**
     * Gets all claims for a solo player.
     */
    public List<Claim> getPlayerClaims(UUID playerOwnerId) {
        List<Claim> claims = new ArrayList<>();
        String sql = """
            SELECT chunk_x, chunk_z, world, guild_id, faction_id, player_owner_id, suppressor_uuid, claimed_at
            FROM fg_claims
            WHERE player_owner_id = ?
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, playerOwnerId);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                claims.add(mapResultSetToClaim(rs));
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error getting player claims for " + playerOwnerId + ": " + e.getMessage());
        }
        return claims;
    }

    /**
     * Gets count of claims for a solo player.
     */
    public int getPlayerClaimCount(UUID playerOwnerId) {
        String sql = "SELECT COUNT(*) FROM fg_claims WHERE player_owner_id = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, playerOwnerId);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error getting claim count for player " + playerOwnerId + ": " + e.getMessage());
        }
        return 0;
    }

    /**
     * Deletes all claims for a solo player.
     */
    public void deletePlayerClaims(UUID playerOwnerId) {
        String sql = "DELETE FROM fg_claims WHERE player_owner_id = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, playerOwnerId);
            int deleted = stmt.executeUpdate();
            LOGGER.at(Level.INFO).log("Deleted " + deleted + " claims for player " + playerOwnerId);
        } catch (SQLException e) {
            LOGGER.at(Level.SEVERE).log("Error deleting player claims: " + e.getMessage());
        }
    }
}
