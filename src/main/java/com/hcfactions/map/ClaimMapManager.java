package com.hcfactions.map;

import com.hcfactions.HC_FactionsPlugin;
import com.hcfactions.models.Claim;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Manages the claim world map system.
 * Sets up map providers and handles map update queuing when claims change.
 */
public class ClaimMapManager {

    private static final ClaimMapManager INSTANCE = new ClaimMapManager();

    public static ClaimMapManager getInstance() {
        return INSTANCE;
    }

    private ClaimMapManager() {}

    // Map update queue: world name -> set of chunk indices to update
    private final Map<String, LongSet> mapUpdateQueue = new ConcurrentHashMap<>();
    private final Set<String> worldsNeedingUpdates = ConcurrentHashMap.newKeySet();

    /**
     * Initializes the claim map system for all worlds.
     * Should be called after the server has loaded worlds.
     */
    public void initialize() {
        try {
            Universe universe = Universe.get();
            if (universe == null) {
                HC_FactionsPlugin.getInstance().getLogger().at(Level.WARNING).log(
                    "[FactionGuilds] Universe is null - cannot initialize map providers"
                );
                return;
            }

            int worldCount = 0;
            for (World world : universe.getWorlds().values()) {
                HC_FactionsPlugin.getInstance().getLogger().at(Level.INFO).log(
                    "[FactionGuilds] Found world: " + world.getName()
                );
                
                // Set our claim world map provider for existing worlds
                world.getWorldConfig().setWorldMapProvider(new ClaimWorldMapProvider());
                HC_FactionsPlugin.getInstance().getLogger().at(Level.INFO).log(
                    "[FactionGuilds] Set ClaimWorldMapProvider for world: " + world.getName()
                );
                
                setupWorldMapProvider(world);
                worldCount++;
            }

            if (worldCount == 0) {
                HC_FactionsPlugin.getInstance().getLogger().at(Level.WARNING).log(
                    "[FactionGuilds] No worlds found to set up map providers"
                );
            }

            HC_FactionsPlugin.getInstance().getLogger().at(Level.INFO).log(
                "[FactionGuilds] ClaimMapManager initialized for " + worldCount + " world(s)"
            );

            // Queue all existing claims for map refresh
            refreshAllExistingClaims();

        } catch (Exception e) {
            HC_FactionsPlugin.getInstance().getLogger().at(Level.SEVERE).log(
                "[FactionGuilds] Failed to initialize ClaimMapManager: " + e.getMessage()
            );
            e.printStackTrace();
        }
    }

    /**
     * Sets up the claim map provider for a specific world.
     */
    public void setupWorldMapProvider(World world) {
        try {
            WorldMapManager mapManager = world.getWorldMapManager();
            if (mapManager == null) {
                HC_FactionsPlugin.getInstance().getLogger().at(Level.WARNING).log(
                    "[FactionGuilds] WorldMapManager is null for world: " + world.getName()
                );
                return;
            }

            Map<String, WorldMapManager.MarkerProvider> providers = mapManager.getMarkerProviders();

            // Remove the default player icon provider and replace with faction-aware version
            if (providers.containsKey("playerIcons")) {
                providers.remove("playerIcons");
                HC_FactionsPlugin.getInstance().getLogger().at(Level.INFO).log(
                    "[FactionGuilds] Removed default playerIcons provider from world: " + world.getName()
                );
            }

            // NOTE: Player markers are now handled by FactionPlayerMarkerTicker (a TickingSystem)
            // to avoid async thread access warnings. Only capital markers use MarkerProvider.

            // Add faction capital marker provider to show capitals on the map
            mapManager.addMarkerProvider(
                FactionCapitalMarkerProvider.PROVIDER_ID,
                new FactionCapitalMarkerProvider()
            );
            HC_FactionsPlugin.getInstance().getLogger().at(Level.INFO).log(
                "[FactionGuilds] Added FactionCapitalMarkerProvider to world: " + world.getName()
            );

        } catch (Exception e) {
            HC_FactionsPlugin.getInstance().getLogger().at(Level.SEVERE).log(
                "[FactionGuilds] Failed to setup map provider for world " + world.getName() + ": " + e.getMessage()
            );
            e.printStackTrace();
        }
    }

    /**
     * Queues a chunk for map update.
     * Called when a claim is created or removed.
     */
    public void queueMapUpdate(String worldName, int chunkX, int chunkZ) {
        long chunkIndex = com.hypixel.hytale.math.util.ChunkUtil.indexChunk(chunkX, chunkZ);
        
        mapUpdateQueue.computeIfAbsent(worldName, k -> new LongOpenHashSet()).add(chunkIndex);
        worldsNeedingUpdates.add(worldName);

        HC_FactionsPlugin.getInstance().getLogger().at(Level.FINE).log(
            "[FactionGuilds] Queued map update for chunk " + chunkX + "," + chunkZ + " in world " + worldName
        );
    }

    /**
     * Queues multiple chunks for map update (with neighbors for border rendering).
     */
    public void queueMapUpdateWithNeighbors(String worldName, int chunkX, int chunkZ) {
        // Queue the chunk itself and all 8 neighbors for proper border rendering
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                queueMapUpdate(worldName, chunkX + dx, chunkZ + dz);
            }
        }
    }

    /**
     * Gets the map update queue for a world.
     */
    public Map<String, LongSet> getMapUpdateQueue() {
        return mapUpdateQueue;
    }

    /**
     * Gets the set of worlds needing updates.
     */
    public Set<String> getWorldsNeedingUpdates() {
        return worldsNeedingUpdates;
    }

    /**
     * Queues all existing claims for map refresh.
     * Called on startup to ensure cached map images are updated.
     */
    public void refreshAllExistingClaims() {
        try {
            HC_FactionsPlugin plugin = HC_FactionsPlugin.getInstance();
            if (plugin == null || plugin.getClaimManager() == null) {
                return;
            }

            // Get all guilds and their claims
            var guildRepo = plugin.getGuildRepository();
            var claimRepo = plugin.getClaimRepository();
            
            if (guildRepo == null || claimRepo == null) {
                return;
            }

            int claimCount = 0;
            var allGuilds = guildRepo.getAllGuilds();
            
            for (var guild : allGuilds) {
                List<Claim> claims = claimRepo.getGuildClaims(guild.getId());
                for (Claim claim : claims) {
                    queueMapUpdateWithNeighbors(claim.getWorld(), claim.getChunkX(), claim.getChunkZ());
                    claimCount++;
                }
            }

            if (claimCount > 0) {
                plugin.getLogger().at(Level.INFO).log(
                    "[FactionGuilds] Queued " + claimCount + " existing claims for map refresh"
                );
            }

        } catch (Exception e) {
            HC_FactionsPlugin.getInstance().getLogger().at(Level.WARNING).log(
                "[FactionGuilds] Error refreshing existing claims: " + e.getMessage()
            );
        }
    }
}
