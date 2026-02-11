package com.hcfactions.managers;

import com.hcfactions.HC_FactionsPlugin;
import com.hcfactions.models.Claim;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.spawning.SpawningPlugin;
import com.hypixel.hytale.server.spawning.assets.spawnsuppression.SpawnSuppression;
import com.hypixel.hytale.server.spawning.spawnmarkers.SpawnMarkerEntity;
import com.hypixel.hytale.server.spawning.suppression.SpawnSuppressorEntry;
import com.hypixel.hytale.server.spawning.suppression.component.SpawnSuppressionController;
import it.unimi.dsi.fastutil.objects.ObjectList;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Manages spawn suppression for claimed territories.
 *
 * OPTIMIZATION: Instead of one suppressor per chunk (which caused 2000+ entries),
 * this creates one suppressor per territory owner (faction, guild, or player).
 *
 * Each suppressor is placed at the centroid of the owner's claims with a radius
 * that covers the entire territory. For very large territories, multiple suppressors
 * are created to ensure full coverage.
 */
public class SpawnSuppressionManager {

    private static final HytaleLogger LOGGER = HytaleLogger.getLogger().getSubLogger("FactionGuilds-SpawnSuppression");

    // Suppression asset IDs (must match JSON file names without extension)
    public static final String FACTION_CAPITAL_SUPPRESSION_ID = "FactionGuilds_FactionCapital";
    public static final String GUILD_CLAIM_SUPPRESSION_ID = "FactionGuilds_GuildClaim";

    // Hytale uses 32-block chunks
    private static final int CHUNK_SIZE = 32;
    // Spawn suppressor at Y=64 (middle of typical terrain)
    private static final double SUPPRESSOR_Y = 64.0;
    // Suppression radius from the asset files (should match JSON)
    private static final double SUPPRESSION_RADIUS = 200.0;
    // Buffer to ensure coverage at edges (half diagonal of a chunk)
    private static final double COVERAGE_BUFFER = CHUNK_SIZE * Math.sqrt(2) / 2;

    private final HC_FactionsPlugin plugin;

    // Cache of suppressor UUIDs by owner key (e.g., "faction:KWEEBECS", "guild:uuid", "player:uuid")
    // Each owner can have multiple suppressors for large territories
    private final Map<String, List<UUID>> territorySuppressors = new ConcurrentHashMap<>();

    public SpawnSuppressionManager(HC_FactionsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Creates an owner key for a claim.
     * Format: "faction:{factionId}" for faction claims, "guild:{guildId}" for guild claims,
     * "player:{playerUuid}" for solo player claims.
     */
    public static String getOwnerKey(Claim claim) {
        if (claim.isFactionClaim()) {
            return "faction:" + claim.getFactionId();
        } else if (claim.isSoloPlayerClaim()) {
            return "player:" + claim.getPlayerOwnerId();
        } else {
            return "guild:" + claim.getGuildId();
        }
    }

    /**
     * Gets the appropriate suppression ID for a claim type.
     */
    private static String getSuppressionId(Claim claim) {
        return claim.isFactionClaim() ? FACTION_CAPITAL_SUPPRESSION_ID : GUILD_CLAIM_SUPPRESSION_ID;
    }

    /**
     * Updates the spawn suppressor(s) for a territory owner.
     * Call this when claims are added or removed.
     *
     * @param claims All claims belonging to this owner (must be non-empty)
     */
    public void updateTerritorySuppressor(List<Claim> claims) {
        if (claims == null || claims.isEmpty()) {
            return;
        }

        Claim firstClaim = claims.get(0);
        String ownerKey = getOwnerKey(firstClaim);
        String worldName = firstClaim.getWorld();
        String suppressionId = getSuppressionId(firstClaim);

        World world = Universe.get().getWorld(worldName);
        if (world == null) {
            LOGGER.at(Level.WARNING).log("Cannot update suppressor: world '" + worldName + "' not found");
            return;
        }

        // Remove existing suppressors for this owner
        removeTerritorySuppressors(ownerKey, world);

        // Calculate suppressor positions to cover all claims
        List<Vector3d> suppressorPositions = calculateSuppressorPositions(claims);

        // Create new suppressors
        List<UUID> newSuppressorUuids = new ArrayList<>();

        SpawnSuppressionController controller = world.getEntityStore()
            .getStore()
            .getResource(SpawnSuppressionController.getResourceType());

        if (controller == null) {
            LOGGER.at(Level.WARNING).log("SpawnSuppressionController not found for world " + worldName);
            return;
        }

        Map<UUID, SpawnSuppressorEntry> suppressorMap = controller.getSpawnSuppressorMap();

        for (Vector3d position : suppressorPositions) {
            UUID suppressorUuid = UUID.randomUUID();
            SpawnSuppressorEntry entry = new SpawnSuppressorEntry(suppressionId, position);
            suppressorMap.put(suppressorUuid, entry);
            newSuppressorUuids.add(suppressorUuid);
        }

        territorySuppressors.put(ownerKey, newSuppressorUuids);

        // Retroactively suppress any existing spawn markers within range (runs async on world thread)
        suppressExistingMarkers(world, suppressorMap, newSuppressorUuids);

        LOGGER.at(Level.INFO).log("Created " + suppressorPositions.size() + " suppressor(s) for " + ownerKey +
            " covering " + claims.size() + " chunks");
    }

    /**
     * Removes all suppressors for a territory owner.
     * Call this when all claims are removed (e.g., guild disbanded).
     *
     * @param ownerKey The owner key (e.g., "guild:uuid")
     * @param world The world to remove from
     */
    public void removeTerritorySuppressors(String ownerKey, World world) {
        List<UUID> existingUuids = territorySuppressors.remove(ownerKey);
        if (existingUuids == null || existingUuids.isEmpty()) {
            return;
        }

        SpawnSuppressionController controller = world.getEntityStore()
            .getStore()
            .getResource(SpawnSuppressionController.getResourceType());

        if (controller == null) {
            return;
        }

        Map<UUID, SpawnSuppressorEntry> suppressorMap = controller.getSpawnSuppressorMap();

        // Release suppression from markers before removing suppressors
        releaseMarkerSuppressions(world, suppressorMap, existingUuids);

        for (UUID uuid : existingUuids) {
            suppressorMap.remove(uuid);
        }

        LOGGER.at(Level.FINE).log("Removed " + existingUuids.size() + " suppressor(s) for " + ownerKey);
    }

    /**
     * Releases suppression from markers that were suppressed by the given suppressors.
     */
    private void releaseMarkerSuppressions(World world, Map<UUID, SpawnSuppressorEntry> suppressorMap, List<UUID> suppressorUuids) {
        if (suppressorUuids == null || suppressorUuids.isEmpty()) {
            return;
        }

        try {
            Store<EntityStore> store = world.getEntityStore().getStore();

            SpatialResource<Ref<EntityStore>, EntityStore> markerSpatial =
                store.getResource(SpawningPlugin.get().getSpawnMarkerSpatialResource());

            if (markerSpatial == null) {
                return;
            }

            for (UUID suppressorUuid : suppressorUuids) {
                SpawnSuppressorEntry entry = suppressorMap.get(suppressorUuid);
                if (entry == null) continue;

                SpawnSuppression suppression = SpawnSuppression.getAssetMap().getAsset(entry.getSuppressionId());
                if (suppression == null || !suppression.isSuppressSpawnMarkers()) {
                    continue;
                }

                double radius = suppression.getRadius();
                Vector3d position = entry.getPosition();

                ObjectList<Ref<EntityStore>> nearbyMarkers = SpatialResource.getThreadLocalReferenceList();
                markerSpatial.getSpatialStructure().collect(position, radius, nearbyMarkers);

                for (Ref<EntityStore> markerRef : nearbyMarkers) {
                    if (markerRef == null || !markerRef.isValid()) continue;

                    SpawnMarkerEntity marker = store.getComponent(markerRef, SpawnMarkerEntity.getComponentType());
                    if (marker != null) {
                        marker.releaseSuppression(suppressorUuid);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.at(Level.WARNING).log("Failed to release marker suppressions: " + e.getMessage());
        }
    }

    /**
     * Removes all suppressors for a territory owner by claims.
     */
    public void removeTerritorySuppressors(List<Claim> claims) {
        if (claims == null || claims.isEmpty()) {
            return;
        }

        Claim firstClaim = claims.get(0);
        String ownerKey = getOwnerKey(firstClaim);
        String worldName = firstClaim.getWorld();

        World world = Universe.get().getWorld(worldName);
        if (world != null) {
            removeTerritorySuppressors(ownerKey, world);
        }
    }

    /**
     * Calculates optimal suppressor positions to cover all claims.
     *
     * For small territories (fits in one 200-block radius), returns the centroid.
     * For larger territories, uses a grid-based approach but only creates suppressors
     * at positions that actually have claims within range (avoids wasting suppressors
     * on sparse/scattered territories).
     */
    private List<Vector3d> calculateSuppressorPositions(List<Claim> claims) {
        List<Vector3d> positions = new ArrayList<>();

        if (claims.isEmpty()) {
            return positions;
        }

        // Calculate bounding box of all claims (in world coordinates)
        double minX = Double.MAX_VALUE, maxX = Double.MIN_VALUE;
        double minZ = Double.MAX_VALUE, maxZ = Double.MIN_VALUE;

        // Also store claim centers for proximity checks
        List<double[]> claimCenters = new ArrayList<>();

        for (Claim claim : claims) {
            double chunkMinX = claim.getChunkX() * CHUNK_SIZE;
            double chunkMaxX = chunkMinX + CHUNK_SIZE;
            double chunkMinZ = claim.getChunkZ() * CHUNK_SIZE;
            double chunkMaxZ = chunkMinZ + CHUNK_SIZE;

            minX = Math.min(minX, chunkMinX);
            maxX = Math.max(maxX, chunkMaxX);
            minZ = Math.min(minZ, chunkMinZ);
            maxZ = Math.max(maxZ, chunkMaxZ);

            // Store center of each claim chunk
            claimCenters.add(new double[]{chunkMinX + CHUNK_SIZE / 2.0, chunkMinZ + CHUNK_SIZE / 2.0});
        }

        double width = maxX - minX;
        double height = maxZ - minZ;
        double centerX = (minX + maxX) / 2;
        double centerZ = (minZ + maxZ) / 2;

        // Check if a single suppressor at center can cover the entire territory
        // Need to cover from center to the farthest corner
        double maxDistanceFromCenter = Math.sqrt(width * width + height * height) / 2 + COVERAGE_BUFFER;

        if (maxDistanceFromCenter <= SUPPRESSION_RADIUS) {
            // Single suppressor is sufficient
            positions.add(new Vector3d(centerX, SUPPRESSOR_Y, centerZ));
        } else {
            // Need multiple suppressors - use a grid but only where claims exist
            // Grid spacing should ensure overlap for coverage
            double gridSpacing = SUPPRESSION_RADIUS * 1.5; // 300 blocks

            int gridCountX = (int) Math.ceil(width / gridSpacing);
            int gridCountZ = (int) Math.ceil(height / gridSpacing);

            // Ensure at least 1x1 grid
            gridCountX = Math.max(1, gridCountX);
            gridCountZ = Math.max(1, gridCountZ);

            // Calculate actual spacing to center the grid
            double actualSpacingX = gridCountX > 1 ? width / (gridCountX - 1) : 0;
            double actualSpacingZ = gridCountZ > 1 ? height / (gridCountZ - 1) : 0;

            for (int gx = 0; gx < gridCountX; gx++) {
                for (int gz = 0; gz < gridCountZ; gz++) {
                    double posX = gridCountX > 1 ? minX + gx * actualSpacingX : centerX;
                    double posZ = gridCountZ > 1 ? minZ + gz * actualSpacingZ : centerZ;

                    // Only create suppressor if there's at least one claim within range
                    if (hasClaimWithinRange(posX, posZ, claimCenters, SUPPRESSION_RADIUS)) {
                        positions.add(new Vector3d(posX, SUPPRESSOR_Y, posZ));
                    }
                }
            }
        }

        return positions;
    }

    /**
     * Checks if any claim center is within the specified range of a position.
     */
    private boolean hasClaimWithinRange(double x, double z, List<double[]> claimCenters, double range) {
        double rangeSquared = range * range;
        for (double[] center : claimCenters) {
            double dx = center[0] - x;
            double dz = center[1] - z;
            if (dx * dx + dz * dz <= rangeSquared) {
                return true;
            }
        }
        return false;
    }

    /**
     * Retroactively suppresses existing spawn markers that are within range of suppressors.
     * This is needed because SpawnMarkerSuppressionSystem only runs when markers are first added,
     * not for markers that already exist when suppressors are created.
     *
     * This must run on the world thread to safely access entities.
     *
     * @param world The world to search for markers
     * @param suppressorMap The map of suppressor entries
     * @param suppressorUuids The UUIDs of the suppressors to check
     * @return The number of markers that were suppressed (0 if queued for later)
     */
    private int suppressExistingMarkers(World world, Map<UUID, SpawnSuppressorEntry> suppressorMap, List<UUID> suppressorUuids) {
        if (suppressorUuids == null || suppressorUuids.isEmpty()) {
            return 0;
        }

        // Queue suppression to run on the world thread
        world.execute(() -> {
            try {
                Store<EntityStore> store = world.getEntityStore().getStore();

                // Get the spawn marker spatial resource for efficient lookup
                SpatialResource<Ref<EntityStore>, EntityStore> markerSpatial =
                    store.getResource(SpawningPlugin.get().getSpawnMarkerSpatialResource());

                if (markerSpatial == null) {
                    LOGGER.at(Level.WARNING).log("SpawnMarker spatial resource not found, cannot suppress existing markers");
                    return;
                }

                // Check how many markers are in the spatial structure at all
                int totalMarkersInWorld = 0;
                try {
                    // Collect all markers with a very large radius from world origin
                    ObjectList<Ref<EntityStore>> allMarkers = SpatialResource.getThreadLocalReferenceList();
                    markerSpatial.getSpatialStructure().collect(new Vector3d(0, 64, 0), 10000, allMarkers);
                    totalMarkersInWorld = allMarkers.size();
                    LOGGER.at(Level.INFO).log("Total spawn markers loaded in world: " + totalMarkersInWorld);
                } catch (Exception e) {
                    LOGGER.at(Level.FINE).log("Could not count total markers: " + e.getMessage());
                }

                int suppressedCount = 0;

                // For each of our new suppressors, find and suppress nearby markers
                for (UUID suppressorUuid : suppressorUuids) {
                    SpawnSuppressorEntry entry = suppressorMap.get(suppressorUuid);
                    if (entry == null) continue;

                    // Get the suppression asset to check radius and if it suppresses markers
                    SpawnSuppression suppression = SpawnSuppression.getAssetMap().getAsset(entry.getSuppressionId());
                    if (suppression == null) {
                        LOGGER.at(Level.WARNING).log("Suppression asset '" + entry.getSuppressionId() + "' not found");
                        continue;
                    }
                    if (!suppression.isSuppressSpawnMarkers()) {
                        LOGGER.at(Level.FINE).log("Suppression asset '" + entry.getSuppressionId() + "' does not suppress spawn markers");
                        continue;
                    }

                    double radius = suppression.getRadius();
                    Vector3d position = entry.getPosition();

                    // Collect all markers within range
                    ObjectList<Ref<EntityStore>> nearbyMarkers = SpatialResource.getThreadLocalReferenceList();
                    markerSpatial.getSpatialStructure().collect(position, radius, nearbyMarkers);

                    LOGGER.at(Level.FINE).log("Found " + nearbyMarkers.size() + " markers near suppressor at " + position);

                    // Suppress each marker
                    for (Ref<EntityStore> markerRef : nearbyMarkers) {
                        if (markerRef == null || !markerRef.isValid()) continue;

                        SpawnMarkerEntity marker = store.getComponent(markerRef, SpawnMarkerEntity.getComponentType());
                        if (marker != null) {
                            marker.suppress(suppressorUuid);
                            suppressedCount++;
                        }
                    }
                }

                if (suppressedCount > 0) {
                    LOGGER.at(Level.INFO).log("Retroactively suppressed " + suppressedCount + " existing spawn markers");
                }
            } catch (Exception e) {
                LOGGER.at(Level.WARNING).log("Failed to suppress existing markers: " + e.getMessage());
            }
        });

        return 0; // Returns 0 since actual suppression is async
    }

    /**
     * Initializes suppressors for all territories on startup.
     * Groups claims by owner and creates optimized suppressors.
     *
     * @param allClaims All claims from the database
     */
    public void initializeAllTerritories(List<Claim> allClaims) {
        if (allClaims == null || allClaims.isEmpty()) {
            LOGGER.at(Level.INFO).log("No claims to initialize suppressors for");
            return;
        }

        // Verify suppression assets are loaded
        SpawnSuppression factionCapitalSuppression = SpawnSuppression.getAssetMap().getAsset(FACTION_CAPITAL_SUPPRESSION_ID);
        SpawnSuppression guildClaimSuppression = SpawnSuppression.getAssetMap().getAsset(GUILD_CLAIM_SUPPRESSION_ID);

        if (factionCapitalSuppression != null) {
            LOGGER.at(Level.INFO).log("Loaded suppression asset '" + FACTION_CAPITAL_SUPPRESSION_ID + "': " + factionCapitalSuppression);
        } else {
            LOGGER.at(Level.SEVERE).log("FAILED to load suppression asset '" + FACTION_CAPITAL_SUPPRESSION_ID + "' - spawn suppression WILL NOT WORK");
        }

        if (guildClaimSuppression != null) {
            LOGGER.at(Level.INFO).log("Loaded suppression asset '" + GUILD_CLAIM_SUPPRESSION_ID + "': " + guildClaimSuppression);
        } else {
            LOGGER.at(Level.SEVERE).log("FAILED to load suppression asset '" + GUILD_CLAIM_SUPPRESSION_ID + "' - spawn suppression WILL NOT WORK");
        }

        // Clean up stale suppressors first
        World defaultWorld = Universe.get().getWorld("default");
        if (defaultWorld != null) {
            int cleaned = cleanupStaleSuppressors(defaultWorld);
            if (cleaned > 0) {
                LOGGER.at(Level.INFO).log("Cleaned up " + cleaned + " stale suppressors");
            }
        }

        // Group claims by owner
        Map<String, List<Claim>> claimsByOwner = new HashMap<>();
        for (Claim claim : allClaims) {
            String ownerKey = getOwnerKey(claim);
            claimsByOwner.computeIfAbsent(ownerKey, k -> new ArrayList<>()).add(claim);
        }

        // Create suppressors for each territory
        int totalSuppressors = 0;
        for (Map.Entry<String, List<Claim>> entry : claimsByOwner.entrySet()) {
            updateTerritorySuppressor(entry.getValue());
            List<UUID> uuids = territorySuppressors.get(entry.getKey());
            if (uuids != null) {
                totalSuppressors += uuids.size();
            }
        }

        LOGGER.at(Level.INFO).log("Initialized " + totalSuppressors + " suppressors for " +
            claimsByOwner.size() + " territories (" + allClaims.size() + " total claims)");
    }

    /**
     * Cleans up stale spawn suppressors from previous server sessions.
     */
    public int cleanupStaleSuppressors(World world) {
        if (world == null) {
            LOGGER.at(Level.WARNING).log("Cannot cleanup suppressors: world is null");
            return 0;
        }

        try {
            SpawnSuppressionController controller = world.getEntityStore()
                .getStore()
                .getResource(SpawnSuppressionController.getResourceType());

            if (controller == null) {
                LOGGER.at(Level.WARNING).log("SpawnSuppressionController not found for world " + world.getName());
                return 0;
            }

            Map<UUID, SpawnSuppressorEntry> suppressorMap = controller.getSpawnSuppressorMap();

            // Find all entries that belong to our plugin (by suppression ID)
            List<UUID> toRemove = new ArrayList<>();
            for (Map.Entry<UUID, SpawnSuppressorEntry> entry : suppressorMap.entrySet()) {
                String suppressionId = entry.getValue().getSuppressionId();
                if (FACTION_CAPITAL_SUPPRESSION_ID.equals(suppressionId) ||
                    GUILD_CLAIM_SUPPRESSION_ID.equals(suppressionId)) {
                    toRemove.add(entry.getKey());
                }
            }

            // Remove stale entries
            for (UUID uuid : toRemove) {
                suppressorMap.remove(uuid);
            }

            return toRemove.size();
        } catch (Exception e) {
            LOGGER.at(Level.SEVERE).log("Failed to cleanup stale suppressors: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Gets the total number of active suppressors.
     */
    public int getTotalSuppressorCount() {
        return territorySuppressors.values().stream()
            .mapToInt(List::size)
            .sum();
    }

    /**
     * Gets the number of territories with suppressors.
     */
    public int getTerritoryCount() {
        return territorySuppressors.size();
    }

    // ========== Legacy methods for backward compatibility ==========
    // These are kept for ClaimManager but now delegate to territory-based logic

    /**
     * @deprecated Use updateTerritorySuppressor instead
     */
    @Deprecated
    public UUID createSuppressor(Claim claim) {
        // This is now handled by updateTerritorySuppressor
        // Return null to indicate the caller should use the new method
        return null;
    }

    /**
     * @deprecated Use removeTerritorySuppressors instead
     */
    @Deprecated
    public boolean removeSuppressor(String worldName, int chunkX, int chunkZ, UUID suppressorUuid) {
        // This is now handled by updateTerritorySuppressor
        return false;
    }
}
