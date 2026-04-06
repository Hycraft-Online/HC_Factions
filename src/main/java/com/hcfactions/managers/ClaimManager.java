package com.hcfactions.managers;

import com.hcfactions.HC_FactionsPlugin;
import com.hcfactions.database.repositories.ClaimRepository;
import com.hcfactions.database.repositories.GuildRepository;
import com.hcfactions.map.ClaimMapManager;
import com.hcfactions.models.Claim;
import com.hcfactions.models.Guild;
import com.hcfactions.models.PlayerData;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import static com.hcfactions.managers.GuildLogType.*;

/**
 * Manages chunk claiming for guilds and factions.
 *
 * Supports two types of claims:
 * - Guild claims: owned by a specific guild, subject to power/limit checks
 * - Faction claims: owned by the faction itself (admin-only), fully protected
 */
public class ClaimManager {

    /**
     * Maximum number of claims for solo players (no guild).
     */
    private static final int SOLO_MAX_CLAIMS = 4;

    /**
     * Result of a claim operation with specific failure reasons.
     */
    public enum ClaimResult {
        SUCCESS("Chunk claimed successfully!"),
        ALREADY_CLAIMED("This chunk is already claimed."),
        GUILD_NOT_FOUND("Guild not found."),
        MAX_CLAIMS_REACHED("You have reached the maximum number of claims."),
        INSUFFICIENT_POWER("Your guild doesn't have enough power to claim more land."),
        NOT_CONTIGUOUS("Claims must be adjacent to your existing territory."),
        INVALID_FACTION("Invalid faction."),
        NOT_IN_GUILD("You must be in a guild to perform this action."),
        RESERVED_BY_OTHER("This chunk is reserved by another territory's perimeter."),
        DATABASE_ERROR("A database error occurred.");

        private final String message;

        ClaimResult(String message) {
            this.message = message;
        }

        public String getMessage() {
            return message;
        }

        public boolean isSuccess() {
            return this == SUCCESS;
        }
    }

    private static final HytaleLogger LOGGER = HytaleLogger.getLogger().getSubLogger("FactionGuilds-Claims");

    private final HC_FactionsPlugin plugin;
    private final ClaimRepository claimRepository;
    private final GuildRepository guildRepository;
    @Nullable
    private SpawnSuppressionManager spawnSuppressionManager;

    // Cache for frequently accessed claims
    private final Map<String, Claim> claimCache = new ConcurrentHashMap<>();
    // When true, the in-memory cache is authoritative (null = unclaimed, no DB fallback needed)
    private volatile boolean cacheWarmed = false;

    // Perimeter reservation: maps "world:chunkX:chunkZ" -> ownerKey (guildId, "player:uuid", or factionId)
    // Reserved chunks prevent rival guilds from claiming directly adjacent to your territory.
    private final Map<String, String> reservedChunks = new ConcurrentHashMap<>();

    public ClaimManager(HC_FactionsPlugin plugin) {
        this.plugin = plugin;
        this.claimRepository = plugin.getClaimRepository();
        this.guildRepository = plugin.getGuildRepository();
    }

    /**
     * Sets the spawn suppression manager (called after initialization).
     */
    public void setSpawnSuppressionManager(SpawnSuppressionManager manager) {
        this.spawnSuppressionManager = manager;
    }

    /**
     * Gets the spawn suppression manager.
     */
    @Nullable
    public SpawnSuppressionManager getSpawnSuppressionManager() {
        return spawnSuppressionManager;
    }

    /**
     * Claims a chunk for a guild.
     *
     * @param guildId Guild making the claim
     * @param world World name
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return true if claim was successful
     */
    public boolean claimChunk(UUID guildId, String world, int chunkX, int chunkZ) {
        return claimChunkWithResult(guildId, world, chunkX, chunkZ).isSuccess();
    }

    /**
     * Claims a chunk for a guild with detailed result.
     *
     * @param guildId Guild making the claim
     * @param world World name
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return ClaimResult with success/failure details
     */
    public ClaimResult claimChunkWithResult(UUID guildId, String world, int chunkX, int chunkZ) {
        Guild guild = guildRepository.getGuild(guildId);
        if (guild == null) {
            return ClaimResult.GUILD_NOT_FOUND;
        }

        // Check if already claimed
        if (isClaimed(world, chunkX, chunkZ)) {
            return ClaimResult.ALREADY_CLAIMED;
        }

        // Check perimeter reservation (guild claims use guildId as owner key)
        if (isReservedByOther(world, chunkX, chunkZ, guildId.toString())) {
            return ClaimResult.RESERVED_BY_OTHER;
        }

        // Check claim limit
        int currentClaims = claimRepository.getGuildClaimCount(guildId);
        int maxClaims = getMaxClaims(guildId);
        if (currentClaims >= maxClaims) {
            return ClaimResult.MAX_CLAIMS_REACHED;
        }

        // Check power (need power >= claims)
        int powerPerClaim = plugin.getConfig().getGuildPowerPerClaim();
        if (guild.getPower() < (currentClaims + 1) * powerPerClaim) {
            return ClaimResult.INSUFFICIENT_POWER;
        }

        // Check contiguous requirement (first claim is free, subsequent must be adjacent)
        if (currentClaims > 0 && !isAdjacentToGuildClaim(guildId, world, chunkX, chunkZ)) {
            return ClaimResult.NOT_CONTIGUOUS;
        }

        // Create claim
        Claim claim = new Claim(chunkX, chunkZ, world, guildId, guild.getFactionId());
        try {
            claimRepository.createClaim(claim);
            claimCache.put(claim.getLocationKey(), claim);

            // Update spawn suppressor to cover territory
            updateTerritorySuppressor(claim);

            // Update perimeter reservations
            addReservationsForClaim(claim);

            // Queue map update for this chunk and neighbors
            ClaimMapManager.getInstance().queueMapUpdateWithNeighbors(world, chunkX, chunkZ);

            // Log chunk claim
            logGuildEvent(guildId, CHUNK_CLAIM, null,
                    "Claimed chunk at (" + chunkX + ", " + chunkZ + ") in " + world);

            LOGGER.at(Level.INFO).log("Guild " + guild.getName() + " claimed chunk at " + world + ":" + chunkX + ":" + chunkZ);
            return ClaimResult.SUCCESS;
        } catch (Exception e) {
            LOGGER.at(Level.SEVERE).log("Failed to create claim: " + e.getMessage());
            return ClaimResult.DATABASE_ERROR;
        }
    }

    /**
     * Unclaims a chunk.
     *
     * @param guildId Guild unclaiming (must be owner)
     * @param world World name
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return true if unclaim was successful
     */
    public boolean unclaimChunk(UUID guildId, String world, int chunkX, int chunkZ) {
        Claim claim = getClaim(world, chunkX, chunkZ);
        if (claim == null) {
            return false;
        }

        // Check ownership - faction claims cannot be unclaimed via this method
        if (claim.getGuildId() == null || !claim.getGuildId().equals(guildId)) {
            return false;
        }

        plugin.getGuildChunkAccessManager().removeAssignmentsForChunk(guildId, world, chunkX, chunkZ);
        plugin.getGuildChunkRoleAccessManager().removeAccessForChunk(guildId, world, chunkX, chunkZ);
        claimRepository.deleteClaim(world, chunkX, chunkZ);
        claimCache.remove(claim.getLocationKey());

        // Update spawn suppressor for remaining territory
        updateTerritorySuppressor(claim);

        // Recompute perimeter reservations for this owner
        recomputeReservationsForOwner(getOwnerKey(claim));

        // Queue map update for this chunk and neighbors
        ClaimMapManager.getInstance().queueMapUpdateWithNeighbors(world, chunkX, chunkZ);

        // Log chunk unclaim
        logGuildEvent(guildId, CHUNK_UNCLAIM, null,
                "Unclaimed chunk at (" + chunkX + ", " + chunkZ + ") in " + world);

        LOGGER.at(Level.INFO).log("Chunk unclaimed at " + world + ":" + chunkX + ":" + chunkZ);
        return true;
    }

    /**
     * Unclaims all chunks for a guild (used when disbanding).
     */
    public void unclaimAllForGuild(UUID guildId) {
        List<Claim> claims = claimRepository.getGuildClaims(guildId);

        // Queue map updates for all claims before removing them
        for (Claim claim : claims) {
            claimCache.remove(claim.getLocationKey());
            ClaimMapManager.getInstance().queueMapUpdateWithNeighbors(claim.getWorld(), claim.getChunkX(), claim.getChunkZ());
        }

        plugin.getGuildChunkAccessManager().removeAssignmentsForGuild(guildId);
        plugin.getGuildChunkRoleAccessManager().removeAccessForGuild(guildId);
        claimRepository.deleteGuildClaims(guildId);

        // Remove spawn suppressors for this territory (if enabled)
        if (!claims.isEmpty() && spawnSuppressionManager != null && plugin.getConfig().isSpawnSuppressionEnabled()) {
            spawnSuppressionManager.removeTerritorySuppressors(claims);
        }

        // Remove all perimeter reservations for this guild
        removeReservationsForOwner(guildId.toString());

        LOGGER.at(Level.INFO).log("All claims removed for guild " + guildId + " (" + claims.size() + " chunks)");
    }

    // ========== Faction Claims (Admin Only) ==========

    /**
     * Claims a chunk for a faction (admin-only, no power/limit checks).
     * Faction claims block all building and breaking for non-admins.
     *
     * @param factionId Faction to claim for
     * @param world World name
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return true if claim was successful
     */
    public boolean claimChunkForFaction(String factionId, String world, int chunkX, int chunkZ) {
        // Validate faction exists
        if (!plugin.getFactionManager().isValidFaction(factionId)) {
            return false;
        }

        // Check if already claimed
        if (isClaimed(world, chunkX, chunkZ)) {
            return false;
        }

        // Create faction claim (no guild owner)
        Claim claim = Claim.createFactionClaim(chunkX, chunkZ, world, factionId);
        try {
            claimRepository.createClaim(claim);
            claimCache.put(claim.getLocationKey(), claim);

            // Update spawn suppressor to cover territory
            updateTerritorySuppressor(claim);

            // Queue map update for this chunk and neighbors
            ClaimMapManager.getInstance().queueMapUpdateWithNeighbors(world, chunkX, chunkZ);

            LOGGER.at(Level.INFO).log("Faction " + factionId + " claimed chunk at " + world + ":" + chunkX + ":" + chunkZ);
            return true;
        } catch (Exception e) {
            LOGGER.at(Level.SEVERE).log("Failed to create faction claim: " + e.getMessage());
            return false;
        }
    }

    /**
     * Claims a chunk as a highway (admin-only, no power/limit checks).
     * Highway claims are faction-level claims that grant sprint speed boosts.
     *
     * @param factionId Faction to claim for
     * @param world World name
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return true if claim was successful
     */
    public boolean claimChunkAsHighway(String factionId, String world, int chunkX, int chunkZ) {
        if (!plugin.getFactionManager().isValidFaction(factionId)) {
            return false;
        }

        if (isClaimed(world, chunkX, chunkZ)) {
            return false;
        }

        Claim claim = Claim.createHighwayClaim(chunkX, chunkZ, world, factionId);
        try {
            claimRepository.createClaim(claim);
            claimCache.put(claim.getLocationKey(), claim);

            updateTerritorySuppressor(claim);

            ClaimMapManager.getInstance().queueMapUpdateWithNeighbors(world, chunkX, chunkZ);

            LOGGER.at(Level.INFO).log("Highway claimed for " + factionId + " at " + world + ":" + chunkX + ":" + chunkZ);
            return true;
        } catch (Exception e) {
            LOGGER.at(Level.SEVERE).log("Failed to create highway claim: " + e.getMessage());
            return false;
        }
    }

    /**
     * Sets the claim type (e.g. "standard" or "highway") for an existing faction claim.
     * Updates the database, cache, and queues a map refresh.
     */
    public boolean setClaimType(String world, int chunkX, int chunkZ, String claimType) {
        Claim claim = getClaim(world, chunkX, chunkZ);
        if (claim == null || !claim.isFactionClaim()) {
            return false;
        }

        boolean updated = claimRepository.updateClaimType(world, chunkX, chunkZ, claimType);
        if (updated) {
            // Update cached claim
            claim.setClaimType(claimType);
            claimCache.put(claim.getLocationKey(), claim);

            ClaimMapManager.getInstance().queueMapUpdateWithNeighbors(world, chunkX, chunkZ);
            LOGGER.at(Level.INFO).log("Set claim type to " + claimType + " at " + world + ":" + chunkX + ":" + chunkZ);
        }
        return updated;
    }

    /**
     * Unclaims a faction chunk (admin-only).
     * Only works on faction claims (not guild claims).
     *
     * @param world World name
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return true if unclaim was successful
     */
    public boolean unclaimFactionChunk(String world, int chunkX, int chunkZ) {
        Claim claim = getClaim(world, chunkX, chunkZ);
        if (claim == null) {
            return false;
        }

        // Only allow unclaiming faction claims (not guild claims)
        if (!claim.isFactionClaim()) {
            return false;
        }

        claimRepository.deleteClaim(world, chunkX, chunkZ);
        claimCache.remove(claim.getLocationKey());

        // Update spawn suppressor for remaining territory
        updateTerritorySuppressor(claim);

        // Queue map update for this chunk and neighbors
        ClaimMapManager.getInstance().queueMapUpdateWithNeighbors(world, chunkX, chunkZ);

        LOGGER.at(Level.INFO).log("Faction chunk unclaimed at " + world + ":" + chunkX + ":" + chunkZ);
        return true;
    }

    // ========== Solo Player Claims ==========

    /**
     * Claims a chunk for a solo player (not in a guild).
     *
     * @param playerUuid Player making the claim
     * @param world World name
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return ClaimResult with success/failure details
     */
    public ClaimResult claimChunkForPlayer(UUID playerUuid, String world, int chunkX, int chunkZ) {
        PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerUuid);
        if (playerData == null || !playerData.hasChosenFaction()) {
            return ClaimResult.INVALID_FACTION;
        }

        // Check if already claimed
        if (isClaimed(world, chunkX, chunkZ)) {
            return ClaimResult.ALREADY_CLAIMED;
        }

        // Check perimeter reservation (solo claims use "player:uuid" as owner key)
        if (isReservedByOther(world, chunkX, chunkZ, "player:" + playerUuid)) {
            return ClaimResult.RESERVED_BY_OTHER;
        }

        // Check claim limit
        int currentClaims = claimRepository.getPlayerClaimCount(playerUuid);
        if (currentClaims >= SOLO_MAX_CLAIMS) {
            return ClaimResult.MAX_CLAIMS_REACHED;
        }

        // Check contiguity (first claim is free, subsequent must be adjacent)
        if (currentClaims > 0 && !isAdjacentToPlayerClaim(playerUuid, world, chunkX, chunkZ)) {
            return ClaimResult.NOT_CONTIGUOUS;
        }

        // Create claim
        Claim claim = Claim.createSoloPlayerClaim(chunkX, chunkZ, world, playerUuid, playerData.getFactionId());
        try {
            claimRepository.createClaim(claim);
            claimCache.put(claim.getLocationKey(), claim);

            // Update spawn suppressor to cover territory
            updateTerritorySuppressor(claim);

            // Update perimeter reservations
            addReservationsForClaim(claim);

            // Queue map update for this chunk and neighbors
            ClaimMapManager.getInstance().queueMapUpdateWithNeighbors(world, chunkX, chunkZ);

            LOGGER.at(Level.INFO).log("Player " + playerUuid + " claimed chunk at " + world + ":" + chunkX + ":" + chunkZ);
            return ClaimResult.SUCCESS;
        } catch (Exception e) {
            LOGGER.at(Level.SEVERE).log("Failed to create player claim: " + e.getMessage());
            return ClaimResult.DATABASE_ERROR;
        }
    }

    /**
     * Unclaims a chunk owned by a solo player.
     *
     * @param playerUuid Player unclaiming (must be owner)
     * @param world World name
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return true if unclaim was successful
     */
    public boolean unclaimChunkForPlayer(UUID playerUuid, String world, int chunkX, int chunkZ) {
        Claim claim = getClaim(world, chunkX, chunkZ);
        if (claim == null || !claim.isSoloPlayerClaim()) {
            return false;
        }
        if (!playerUuid.equals(claim.getPlayerOwnerId())) {
            return false;
        }

        claimRepository.deleteClaim(world, chunkX, chunkZ);
        claimCache.remove(claim.getLocationKey());

        // Update spawn suppressor for remaining territory
        updateTerritorySuppressor(claim);

        // Recompute perimeter reservations for this player
        recomputeReservationsForOwner("player:" + playerUuid);

        // Queue map update for this chunk and neighbors
        ClaimMapManager.getInstance().queueMapUpdateWithNeighbors(world, chunkX, chunkZ);

        LOGGER.at(Level.INFO).log("Player " + playerUuid + " unclaimed chunk at " + world + ":" + chunkX + ":" + chunkZ);
        return true;
    }

    /**
     * Unclaims all chunks owned by a solo player.
     * Called when a player joins a guild.
     *
     * @param playerUuid Player whose claims should be removed
     */
    public void unclaimAllForPlayer(UUID playerUuid) {
        List<Claim> claims = claimRepository.getPlayerClaims(playerUuid);

        // Queue map updates for all claims before removing them
        for (Claim claim : claims) {
            claimCache.remove(claim.getLocationKey());
            ClaimMapManager.getInstance().queueMapUpdateWithNeighbors(claim.getWorld(), claim.getChunkX(), claim.getChunkZ());
        }

        claimRepository.deletePlayerClaims(playerUuid);

        // Remove spawn suppressors for this territory (if enabled)
        if (!claims.isEmpty() && spawnSuppressionManager != null && plugin.getConfig().isSpawnSuppressionEnabled()) {
            spawnSuppressionManager.removeTerritorySuppressors(claims);
        }

        // Remove all perimeter reservations for this player
        removeReservationsForOwner("player:" + playerUuid);

        LOGGER.at(Level.INFO).log("All personal claims removed for player " + playerUuid + " (" + claims.size() + " chunks)");
    }

    /**
     * Checks if a chunk is adjacent to any existing claim by the same player.
     * Used to enforce contiguous claim territory for solo players.
     *
     * @param playerUuid The player checking for adjacency
     * @param world World name
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return true if the chunk is adjacent to at least one existing player claim
     */
    public boolean isAdjacentToPlayerClaim(UUID playerUuid, String world, int chunkX, int chunkZ) {
        // Check all 4 cardinal neighbors (not diagonals - keeps territories more compact)
        int[][] neighbors = {
            {chunkX - 1, chunkZ},     // West
            {chunkX + 1, chunkZ},     // East
            {chunkX, chunkZ - 1},     // North
            {chunkX, chunkZ + 1}      // South
        };

        for (int[] neighbor : neighbors) {
            Claim neighborClaim = getClaim(world, neighbor[0], neighbor[1]);
            if (neighborClaim != null &&
                neighborClaim.isSoloPlayerClaim() &&
                playerUuid.equals(neighborClaim.getPlayerOwnerId())) {
                return true;
            }
        }

        return false;
    }

    /**
     * Gets all claims for a solo player.
     */
    public List<Claim> getPlayerClaims(UUID playerUuid) {
        return claimRepository.getPlayerClaims(playerUuid);
    }

    /**
     * Gets claim count for a solo player.
     */
    public int getPlayerClaimCount(UUID playerUuid) {
        return claimRepository.getPlayerClaimCount(playerUuid);
    }

    /**
     * Gets the maximum claims allowed for solo players.
     */
    public int getSoloMaxClaims() {
        return SOLO_MAX_CLAIMS;
    }

    // ========== Admin Guild Claims ==========

    /**
     * Claims a chunk for a guild as admin (bypasses power/limit checks).
     *
     * @param guildId Guild to claim for
     * @param world World name
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return ClaimResult with success/failure details
     */
    public ClaimResult adminClaimChunkForGuild(UUID guildId, String world, int chunkX, int chunkZ) {
        Guild guild = guildRepository.getGuild(guildId);
        if (guild == null) {
            return ClaimResult.GUILD_NOT_FOUND;
        }

        // Check if already claimed
        if (isClaimed(world, chunkX, chunkZ)) {
            return ClaimResult.ALREADY_CLAIMED;
        }

        // Create claim (no power/limit checks for admin)
        Claim claim = new Claim(chunkX, chunkZ, world, guildId, guild.getFactionId());
        try {
            claimRepository.createClaim(claim);
            claimCache.put(claim.getLocationKey(), claim);

            // Update spawn suppressor to cover territory
            updateTerritorySuppressor(claim);

            // Update perimeter reservations
            addReservationsForClaim(claim);

            // Queue map update for this chunk and neighbors
            ClaimMapManager.getInstance().queueMapUpdateWithNeighbors(world, chunkX, chunkZ);

            LOGGER.at(Level.INFO).log("[Admin] Guild " + guild.getName() + " claimed chunk at " + world + ":" + chunkX + ":" + chunkZ);
            return ClaimResult.SUCCESS;
        } catch (Exception e) {
            LOGGER.at(Level.SEVERE).log("Failed to create admin claim: " + e.getMessage());
            return ClaimResult.DATABASE_ERROR;
        }
    }

    /**
     * Unclaims a guild chunk as admin (doesn't require ownership).
     *
     * @param world World name
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return true if unclaim was successful, false if chunk wasn't claimed or is a faction claim
     */
    public boolean adminUnclaimGuildChunk(String world, int chunkX, int chunkZ) {
        Claim claim = getClaim(world, chunkX, chunkZ);
        if (claim == null) {
            return false;
        }

        // Only allow unclaiming guild claims (not faction claims - use unclaimFactionChunk for those)
        if (claim.isFactionClaim()) {
            return false;
        }

        if (claim.getGuildId() != null) {
            plugin.getGuildChunkAccessManager().removeAssignmentsForChunk(claim.getGuildId(), world, chunkX, chunkZ);
            plugin.getGuildChunkRoleAccessManager().removeAccessForChunk(claim.getGuildId(), world, chunkX, chunkZ);
        }
        claimRepository.deleteClaim(world, chunkX, chunkZ);
        claimCache.remove(claim.getLocationKey());

        // Update spawn suppressor for remaining territory
        updateTerritorySuppressor(claim);

        // Recompute perimeter reservations for this owner
        recomputeReservationsForOwner(getOwnerKey(claim));

        // Queue map update for this chunk and neighbors
        ClaimMapManager.getInstance().queueMapUpdateWithNeighbors(world, chunkX, chunkZ);

        LOGGER.at(Level.INFO).log("[Admin] Guild chunk unclaimed at " + world + ":" + chunkX + ":" + chunkZ);
        return true;
    }

    /**
     * Transfers a guild claim to another guild (admin-only).
     *
     * @param world World name
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @param newGuildId The guild to transfer ownership to
     * @return true if transfer was successful
     */
    public boolean transferGuildClaim(String world, int chunkX, int chunkZ, UUID newGuildId) {
        Claim claim = getClaim(world, chunkX, chunkZ);
        if (claim == null || claim.isFactionClaim()) {
            return false;
        }

        Guild newGuild = guildRepository.getGuild(newGuildId);
        if (newGuild == null) {
            return false;
        }

        // Delete old claim and create new one
        if (claim.getGuildId() != null) {
            plugin.getGuildChunkAccessManager().removeAssignmentsForChunk(claim.getGuildId(), world, chunkX, chunkZ);
            plugin.getGuildChunkRoleAccessManager().removeAccessForChunk(claim.getGuildId(), world, chunkX, chunkZ);
        }
        claimRepository.deleteClaim(world, chunkX, chunkZ);
        claimCache.remove(claim.getLocationKey());

        Claim newClaim = new Claim(chunkX, chunkZ, world, newGuildId, newGuild.getFactionId());
        try {
            claimRepository.createClaim(newClaim);
            claimCache.put(newClaim.getLocationKey(), newClaim);

            // Queue map update for this chunk and neighbors
            ClaimMapManager.getInstance().queueMapUpdateWithNeighbors(world, chunkX, chunkZ);

            LOGGER.at(Level.INFO).log("[Admin] Transferred claim at " + world + ":" + chunkX + ":" + chunkZ + " to guild " + newGuild.getName());
            return true;
        } catch (Exception e) {
            LOGGER.at(Level.SEVERE).log("Failed to transfer claim: " + e.getMessage());
            return false;
        }
    }

    /**
     * Gets the maximum claims for a specific guild based on member count.
     * Formula: baseClaims + claimsPerMember * (memberCount - 1)
     *
     * Examples with defaults (base=4, perMember=2):
     * - 1 member: 4 claims
     * - 2 members: 6 claims
     * - 5 members: 12 claims
     * - 10 members: 22 claims
     *
     * @param guildId The guild to calculate max claims for
     * @return The maximum number of claims this guild can have
     */
    public int getMaxClaims(UUID guildId) {
        int memberCount = plugin.getGuildManager().getMemberCount(guildId);
        int baseClaims = plugin.getConfig().getGuildBaseClaimsPerGuild();
        int claimsPerMember = plugin.getConfig().getGuildClaimsPerAdditionalMember();
        return baseClaims + claimsPerMember * (memberCount - 1);
    }

    /**
     * Gets the power required per claim.
     */
    public int getPowerPerClaim() {
        return plugin.getConfig().getGuildPowerPerClaim();
    }

    /**
     * Gets the claim at a location.
     * When the cache is warm, this is purely in-memory (no DB query).
     */
    public Claim getClaim(String world, int chunkX, int chunkZ) {
        String key = Claim.createLocationKey(world, chunkX, chunkZ);

        Claim cached = claimCache.get(key);
        if (cached != null || cacheWarmed) {
            return cached; // null = unclaimed (authoritative when cache is warm)
        }

        // Fallback to DB only before cache is warmed (startup grace period)
        Claim claim = claimRepository.getClaim(world, chunkX, chunkZ);
        if (claim != null) {
            claimCache.put(key, claim);
        }
        return claim;
    }

    /**
     * Checks if a chunk is claimed.
     */
    public boolean isClaimed(String world, int chunkX, int chunkZ) {
        return getClaim(world, chunkX, chunkZ) != null;
    }

    /**
     * Gets all claims for a guild.
     */
    public List<Claim> getGuildClaims(UUID guildId) {
        return claimRepository.getGuildClaims(guildId);
    }

    /**
     * Gets claim count for a guild.
     */
    public int getClaimCount(UUID guildId) {
        return claimRepository.getGuildClaimCount(guildId);
    }

    /**
     * Gets claims in a radius (for map display).
     */
    public List<Claim> getClaimsInRadius(String world, int centerX, int centerZ, int radius) {
        return claimRepository.getClaimsInRadius(world, centerX, centerZ, radius);
    }

    /**
     * Gets all faction-level claims (not guild claims) for a faction.
     * These are claims where guildId is null, typically used for protected areas
     * like faction capitals and admin-designated faction zones.
     *
     * @param factionId The faction ID to get claims for
     * @return List of faction-level claims (where guildId is null)
     */
    public List<Claim> getFactionOnlyClaims(String factionId) {
        // Get all claims for this faction and filter to only faction-level claims
        return claimRepository.getFactionClaims(factionId).stream()
            .filter(Claim::isFactionClaim)
            .toList();
    }

    /**
     * Converts world coordinates to chunk coordinates.
     * Hytale uses 32-block chunks (>> 5), not 16-block chunks.
     */
    public static int toChunkCoord(double worldCoord) {
        return (int) Math.floor(worldCoord) >> 5;  // Divide by 32
    }

    /**
     * Checks if a player can build/break at a location based on claim rules.
     *
     * @param world World name
     * @param chunkX Chunk X
     * @param chunkZ Chunk Z
     * @param playerUuid Player's UUID
     * @param playerGuildId Player's guild ID (null if not in guild)
     * @param playerFactionId Player's faction ID (null if no faction)
     * @param isBreaking true if breaking, false if building
     * @return true if the action is allowed
     */
    public boolean canInteract(String world, int chunkX, int chunkZ,
                               UUID playerUuid, UUID playerGuildId, String playerFactionId, boolean isBreaking) {
        Claim claim = getClaim(world, chunkX, chunkZ);

        // Unclaimed land - everyone can interact
        if (claim == null) {
            return true;
        }

        // Faction claims block ALL interactions (admin-only areas)
        if (claim.isFactionClaim()) {
            return false;
        }

        // Solo player claims - only owner can interact
        if (claim.isSoloPlayerClaim()) {
            return playerUuid != null && playerUuid.equals(claim.getPlayerOwnerId());
        }

        // Same guild - always allowed
        if (playerGuildId != null && playerGuildId.equals(claim.getGuildId())) {
            PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerUuid);
            GuildChunkAccessManager.AccessAction action = isBreaking
                ? GuildChunkAccessManager.AccessAction.BREAK
                : GuildChunkAccessManager.AccessAction.PLACE;
            return plugin.getGuildChunkAccessManager().canAccessGuildClaim(playerData, claim, action, null);
        }

        // Same faction, different guild - check config
        if (playerFactionId != null && playerFactionId.equals(claim.getFactionId())) {
            return plugin.getConfig().isProtectionSameFactionGuildAccess();
        }

        // Enemy faction - check config based on action type
        if (isBreaking) {
            return plugin.getConfig().isProtectionEnemyCanDestroy();
        } else {
            return plugin.getConfig().isProtectionEnemyCanBuild();
        }
    }

    /**
     * Loads all claims from the database into the in-memory cache.
     * After this, getClaim() never hits the DB — the cache is authoritative.
     */
    public void warmCache() {
        List<Claim> allClaims = claimRepository.getAllClaims();
        claimCache.clear();
        for (Claim claim : allClaims) {
            claimCache.put(claim.getLocationKey(), claim);
        }
        cacheWarmed = true;
        LOGGER.at(Level.INFO).log("Claim cache warmed: %d claims loaded into memory", allClaims.size());
    }

    /**
     * Returns true if the claim cache has been fully warmed.
     * Before this returns true, claim lookups may be incomplete.
     */
    public boolean isCacheReady() {
        return cacheWarmed;
    }

    /**
     * Clears the claim cache.
     */
    public void clearCache() {
        claimCache.clear();
        cacheWarmed = false;
    }

    /**
     * Checks if a chunk is adjacent to any existing claim by the same guild.
     * Used to enforce contiguous claim territory.
     *
     * @param guildId The guild checking for adjacency
     * @param world World name
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return true if the chunk is adjacent to at least one existing guild claim
     */
    public boolean isAdjacentToGuildClaim(UUID guildId, String world, int chunkX, int chunkZ) {
        // Check all 4 cardinal neighbors (not diagonals - keeps territories more compact)
        int[][] neighbors = {
            {chunkX - 1, chunkZ},     // West
            {chunkX + 1, chunkZ},     // East
            {chunkX, chunkZ - 1},     // North
            {chunkX, chunkZ + 1}      // South
        };

        for (int[] neighbor : neighbors) {
            Claim neighborClaim = getClaim(world, neighbor[0], neighbor[1]);
            if (neighborClaim != null &&
                !neighborClaim.isFactionClaim() &&
                guildId.equals(neighborClaim.getGuildId())) {
                return true;
            }
        }

        return false;
    }

    // ========== Perimeter Reservation ==========

    /**
     * Offsets for the 4 surrounding chunks (cardinal only).
     * Diagonal reservations excluded so two guilds expanding toward
     * each other can't create an unclaimable dead-zone chunk between them.
     */
    private static final int[][] PERIMETER_OFFSETS = {
                  {0, -1},
        {-1,  0},          {1,  0},
                  {0,  1}
    };

    /**
     * Gets the owner key for a claim, used as the reservation owner identifier.
     * Guild claims use guildId, solo claims use "player:uuid", faction claims use factionId.
     */
    private String getOwnerKey(Claim claim) {
        if (claim.isSoloPlayerClaim()) {
            return "player:" + claim.getPlayerOwnerId();
        } else if (claim.isFactionClaim()) {
            return claim.getFactionId();
        } else {
            return claim.getGuildId().toString();
        }
    }

    /**
     * Computes all perimeter reservations from scratch.
     * Called after warmCache() on startup.
     */
    public void computeAllReservations() {
        if (!plugin.getConfig().isPerimeterReservationEnabled()) {
            LOGGER.at(Level.INFO).log("Perimeter reservation is DISABLED, skipping computation");
            return;
        }

        reservedChunks.clear();

        for (Claim claim : claimCache.values()) {
            // Faction claims don't get perimeter reservations (admin territories)
            if (claim.isFactionClaim()) {
                continue;
            }
            addReservationsForClaimInternal(claim);
        }

        LOGGER.at(Level.INFO).log("Perimeter reservations computed: %d reserved chunks", reservedChunks.size());
    }

    /**
     * Adds perimeter reservations for a newly claimed chunk.
     * Only marks unclaimed, unreserved-by-others chunks.
     */
    private void addReservationsForClaim(Claim claim) {
        if (!plugin.getConfig().isPerimeterReservationEnabled()) {
            return;
        }
        // Faction claims don't get perimeter reservations
        if (claim.isFactionClaim()) {
            return;
        }
        addReservationsForClaimInternal(claim);
    }

    /**
     * Internal helper: adds 8-neighbor reservations for a single claim.
     */
    private void addReservationsForClaimInternal(Claim claim) {
        String ownerKey = getOwnerKey(claim);
        String world = claim.getWorld();
        int cx = claim.getChunkX();
        int cz = claim.getChunkZ();

        for (int[] offset : PERIMETER_OFFSETS) {
            int nx = cx + offset[0];
            int nz = cz + offset[1];
            String key = Claim.createLocationKey(world, nx, nz);

            // Don't reserve chunks that are already claimed
            if (claimCache.containsKey(key)) {
                continue;
            }

            // Only set if not already reserved, or if already reserved by the same owner
            reservedChunks.putIfAbsent(key, ownerKey);
        }
    }

    /**
     * Removes all reservations for a given owner key and recomputes them.
     * Used on unclaim — removes old reservations then re-derives from remaining claims.
     */
    private void recomputeReservationsForOwner(String ownerKey) {
        if (!plugin.getConfig().isPerimeterReservationEnabled()) {
            return;
        }

        // Remove all existing reservations for this owner
        removeReservationsForOwner(ownerKey);

        // Re-add reservations for all remaining claims by this owner
        for (Claim claim : claimCache.values()) {
            if (claim.isFactionClaim()) {
                continue;
            }
            if (ownerKey.equals(getOwnerKey(claim))) {
                addReservationsForClaimInternal(claim);
            }
        }
    }

    /**
     * Removes all perimeter reservations for a given owner key.
     */
    private void removeReservationsForOwner(String ownerKey) {
        reservedChunks.entrySet().removeIf(entry -> ownerKey.equals(entry.getValue()));
    }

    /**
     * Checks if a chunk is reserved by a different owner.
     * Exempts "established neighbors" — if the claimant already has claims
     * adjacent to the reservation owner's territory, the perimeter doesn't apply.
     * This prevents existing neighbors from being locked out of expansion.
     *
     * @param world World name
     * @param chunkX Chunk X
     * @param chunkZ Chunk Z
     * @param ownerKey The owner key of the entity trying to claim
     * @return true if chunk is reserved by someone else (and they're NOT established neighbors)
     */
    public boolean isReservedByOther(String world, int chunkX, int chunkZ, String ownerKey) {
        if (!plugin.getConfig().isPerimeterReservationEnabled()) {
            return false;
        }

        String key = Claim.createLocationKey(world, chunkX, chunkZ);
        String reservedBy = reservedChunks.get(key);
        if (reservedBy == null) {
            return false;
        }
        if (reservedBy.equals(ownerKey)) {
            return false; // Own reservation
        }

        // Established neighbor exemption: if claimant already has claims
        // adjacent to the reservation owner's territory, allow it
        if (isEstablishedNeighbor(ownerKey, reservedBy)) {
            return false;
        }

        return true;
    }

    /**
     * Checks if two owners are "established neighbors" — meaning the claimant
     * already has at least one claim that is cardinally adjacent to at least one
     * claim by the reservation owner. This is used to grandfather in existing
     * adjacent territories so the perimeter system doesn't retroactively block them.
     *
     * @param claimantKey Owner key of the entity trying to claim
     * @param reservationOwnerKey Owner key of the entity that reserved the chunk
     * @return true if they already share a border
     */
    private boolean isEstablishedNeighbor(String claimantKey, String reservationOwnerKey) {
        // Cardinal offsets only (not diagonal) — must share an actual border
        int[][] cardinalOffsets = {
            {-1, 0}, {1, 0}, {0, -1}, {0, 1}
        };

        for (Claim claim : claimCache.values()) {
            // Find claims belonging to the reservation owner
            if (!reservationOwnerKey.equals(getOwnerKey(claim))) {
                continue;
            }

            // Check if any cardinal neighbor belongs to the claimant
            String world = claim.getWorld();
            int cx = claim.getChunkX();
            int cz = claim.getChunkZ();

            for (int[] offset : cardinalOffsets) {
                String neighborKey = Claim.createLocationKey(world, cx + offset[0], cz + offset[1]);
                Claim neighborClaim = claimCache.get(neighborKey);
                if (neighborClaim != null && claimantKey.equals(getOwnerKey(neighborClaim))) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Gets the owner key of the reservation at a location, or null if not reserved.
     */
    @Nullable
    public String getReservationOwner(String world, int chunkX, int chunkZ) {
        if (!plugin.getConfig().isPerimeterReservationEnabled()) {
            return null;
        }
        String key = Claim.createLocationKey(world, chunkX, chunkZ);
        return reservedChunks.get(key);
    }

    /**
     * Resolves a reservation owner key to a display name.
     * Owner keys are: guildId (UUID string), "player:uuid", or factionId string.
     */
    @Nullable
    public String getReservationOwnerName(String ownerKey) {
        if (ownerKey == null) {
            return null;
        }
        if (ownerKey.startsWith("player:")) {
            String uuidStr = ownerKey.substring("player:".length());
            try {
                UUID playerUuid = UUID.fromString(uuidStr);
                PlayerData pd = plugin.getPlayerDataRepository().getPlayerData(playerUuid);
                return pd != null && pd.getPlayerName() != null ? pd.getPlayerName() : "Unknown Player";
            } catch (IllegalArgumentException e) {
                return "Unknown Player";
            }
        }
        try {
            UUID guildUuid = UUID.fromString(ownerKey);
            Guild guild = guildRepository.getGuild(guildUuid);
            return guild != null ? guild.getName() : "Unknown Guild";
        } catch (IllegalArgumentException e) {
            // It's a faction ID string
            var faction = plugin.getFactionManager().getFaction(ownerKey);
            return faction != null ? faction.getDisplayName() : ownerKey;
        }
    }

    // ========== Spawn Suppression ==========

    /**
     * Updates the spawn suppressor for a territory after a claim change.
     * This recalculates the optimal suppressor position(s) for the owner's entire territory.
     *
     * @param claim The claim that was added/removed (used to identify the owner)
     */
    private void updateTerritorySuppressor(Claim claim) {
        if (spawnSuppressionManager == null) {
            return;
        }

        // Check if spawn suppression is enabled in config
        if (!plugin.getConfig().isSpawnSuppressionEnabled()) {
            return;
        }

        // Get all claims for this owner to recalculate the territory suppressor
        List<Claim> ownerClaims = getClaimsForOwner(claim);

        if (ownerClaims.isEmpty()) {
            // No claims left - remove suppressors entirely
            spawnSuppressionManager.removeTerritorySuppressors(List.of(claim));
        } else {
            // Update suppressor to cover all remaining claims
            spawnSuppressionManager.updateTerritorySuppressor(ownerClaims);
        }
    }

    /**
     * Gets all claims for the same owner as the given claim.
     */
    private List<Claim> getClaimsForOwner(Claim claim) {
        if (claim.isFactionClaim()) {
            return getFactionOnlyClaims(claim.getFactionId());
        } else if (claim.isSoloPlayerClaim()) {
            return getPlayerClaims(claim.getPlayerOwnerId());
        } else {
            return getGuildClaims(claim.getGuildId());
        }
    }

    /**
     * Initializes spawn suppressors for all existing claims.
     * Called on plugin startup to ensure all claims have suppressors.
     *
     * OPTIMIZATION: Uses territory-based suppressors instead of per-chunk.
     * This dramatically reduces the number of suppressors (e.g., from 182 to ~10).
     */
    public void initializeSpawnSuppressors() {
        if (spawnSuppressionManager == null) {
            LOGGER.at(Level.WARNING).log("SpawnSuppressionManager not initialized");
            return;
        }

        // Check if spawn suppression is enabled in config
        if (!plugin.getConfig().isSpawnSuppressionEnabled()) {
            LOGGER.at(Level.INFO).log("Spawn suppression is DISABLED in config, skipping initialization");
            return;
        }

        LOGGER.at(Level.INFO).log("Initializing spawn suppressors for existing claims...");

        // Load all claims and initialize territory-based suppressors
        List<Claim> allClaims = claimRepository.getAllClaims();
        spawnSuppressionManager.initializeAllTerritories(allClaims);

        LOGGER.at(Level.INFO).log("Spawn suppressor initialization complete: " +
            spawnSuppressionManager.getTotalSuppressorCount() + " suppressors for " +
            spawnSuppressionManager.getTerritoryCount() + " territories");
    }

    // ═══════════════════════════════════════════════════════
    // GUILD LOG HELPER
    // ═══════════════════════════════════════════════════════

    private void logGuildEvent(UUID guildId, GuildLogType type, UUID actorUuid, String details) {
        if (guildId == null) return; // Skip logging for non-guild claims (faction/player)
        GuildLogManager logManager = plugin.getGuildLogManager();
        if (logManager != null) {
            logManager.logEvent(guildId, type, actorUuid, details);
        }
    }
}
