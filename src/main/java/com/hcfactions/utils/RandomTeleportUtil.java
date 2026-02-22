package com.hcfactions.utils;

import com.hcfactions.models.Faction;
import com.hcleveling.HC_LevelingPlugin;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility class for random teleportation within faction territory.
 * Restricted to Zone 1 only.
 */
public class RandomTeleportUtil {

    private static final Random random = new Random();
    private static final Logger LOGGER = Logger.getLogger("HC_Factions-RTP");

    // Configuration
    private static final int MIN_DISTANCE = 100;
    private static final int MAX_DISTANCE = 1000;
    private static final int MIN_HEIGHT = 60;
    private static final int MAX_HEIGHT = 260;
    private static final int MAX_ATTEMPTS = 25;

    /**
     * Teleport a player to a random location within their faction's territory.
     *
     * @param playerRef The player to teleport
     * @param faction The player's faction
     * @param onComplete Callback when teleport completes (may be null)
     */
    public static void teleportToRandomLocation(PlayerRef playerRef, Faction faction, Runnable onComplete) {
        World targetWorld = Universe.get().getWorld(faction.getSpawnWorld());
        if (targetWorld == null) {
            targetWorld = Universe.get().getDefaultWorld();
        }

        if (targetWorld == null) {
            playerRef.sendMessage(Message.raw("[RTP] Could not find target world.").color(Color.RED));
            if (onComplete != null) onComplete.run();
            return;
        }

        playerRef.sendMessage(Message.raw("[RTP] Searching for a safe location...").color(Color.YELLOW));

        final World world = targetWorld;
        world.execute(() -> {
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref == null || !ref.isValid()) {
                playerRef.sendMessage(Message.raw("[RTP] Error: Could not get player reference.").color(Color.RED));
                if (onComplete != null) onComplete.run();
                return;
            }

            Store<EntityStore> store = ref.getStore();
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null) {
                if (onComplete != null) onComplete.run();
                return;
            }

            tryRandomLocation(playerRef, player, ref, store, world, faction, 1, onComplete);
        });
    }

    private static void tryRandomLocation(PlayerRef playerRef, Player player, Ref<EntityStore> ref,
            Store<EntityStore> store, World world, Faction faction, int attempt, Runnable onComplete) {

        if (attempt > MAX_ATTEMPTS) {
            playerRef.sendMessage(Message.raw("[RTP] Could not find a safe location. Teleporting to faction spawn.").color(Color.RED));
            // Fallback to faction spawn using Teleport.createForPlayer
            teleportToPosition(playerRef, ref, store, world, faction.getSpawnX(), faction.getSpawnY(), faction.getSpawnZ());
            if (onComplete != null) onComplete.run();
            return;
        }

        // Get faction spawn as center point
        double centerX = faction.getSpawnX();
        double centerZ = faction.getSpawnZ();

        // Calculate base angle from origin to faction spawn (determines hemisphere)
        double baseAngle = Math.atan2(centerZ, centerX);

        // Random angle within 180-degree arc centered on faction's direction
        double angleRange = Math.PI;
        double angle = baseAngle - (angleRange / 2) + random.nextDouble() * angleRange;

        // Random distance within range
        double distance = MIN_DISTANCE + random.nextDouble() * (MAX_DISTANCE - MIN_DISTANCE);

        // Calculate target position
        double randomX = centerX + Math.cos(angle) * distance;
        double randomZ = centerZ + Math.sin(angle) * distance;

        int worldX = (int) Math.floor(randomX);
        int worldZ = (int) Math.floor(randomZ);

        // Zone check BEFORE chunk loading (procedural, fast)
        int zoneNumber = getZoneNumber(world, worldX, worldZ);
        if (zoneNumber != 1) {
            LOGGER.log(Level.FINE, "[RTP] Attempt " + attempt + " at X=" + worldX +
                " Z=" + worldZ + " is Zone " + zoneNumber + ", skipping...");
            tryRandomLocation(playerRef, player, ref, store, world, faction, attempt + 1, onComplete);
            return;
        }

        LOGGER.log(Level.FINE, "[RTP] Attempt " + attempt + "/" + MAX_ATTEMPTS + ": X=" + worldX + " Z=" + worldZ + " (Zone 1)");

        // Load chunks around target location
        int centerChunkX = worldX >> 4;
        int centerChunkZ = worldZ >> 4;

        ArrayList<CompletableFuture<?>> futures = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                long chunkIdx = ((long)(centerChunkX + dx) << 32) | ((long)(centerChunkZ + dz) & 0xFFFFFFFFL);
                futures.add(world.getChunkAsync(chunkIdx));
            }
        }

        final double fRandomX = randomX;
        final double fRandomZ = randomZ;

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenRun(() -> CompletableFuture.delayedExecutor(500L, TimeUnit.MILLISECONDS)
                .execute(() -> world.execute(() -> {
                    int safeY = findSafeSurfaceY(world, worldX, worldZ, MIN_HEIGHT, MAX_HEIGHT);

                    if (safeY < 0) {
                        LOGGER.log(Level.FINE, "[RTP] Attempt " + attempt + " failed - no safe spot, retrying...");
                        tryRandomLocation(playerRef, player, ref, store, world, faction, attempt + 1, onComplete);
                        return;
                    }

                    double teleportY = safeY + 1.0;
                    teleportToPosition(playerRef, ref, store, world, fRandomX, teleportY, fRandomZ);

                    String msg = String.format("Teleported to %.0f, %.0f, %.0f", fRandomX, teleportY, fRandomZ);
                    playerRef.sendMessage(Message.raw("[RTP] " + msg).color(Color.GREEN));

                    if (onComplete != null) onComplete.run();
                })));
    }

    private static void teleportToPosition(PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store,
            World world, double x, double y, double z) {
        // Re-validate ref
        if (ref == null || !ref.isValid()) {
            return;
        }

        // Clear interactions before teleport
        InteractionManager interactionManager = store.getComponent(ref,
            InteractionModule.get().getInteractionManagerComponent());
        if (interactionManager != null) {
            interactionManager.clear();
        }

        // Use Teleport.createForPlayer for proper position sync
        Vector3d target = new Vector3d(x, y, z);
        Vector3f rotation = new Vector3f(0, 0, 0);
        store.addComponent(ref, Teleport.getComponentType(),
            Teleport.createForPlayer(world, target, rotation));
    }

    /**
     * Find a safe surface Y for teleportation.
     * Checks that ground is solid (not fluid/lava), and there are 2 empty, fluid-free blocks above.
     */
    private static int findSafeSurfaceY(World world, int x, int z, int minHeight, int maxHeight) {
        for (int y = maxHeight; y >= minHeight; y--) {
            try {
                // Check ground block is actually solid (not lava, water, etc.)
                BlockType groundType = world.getBlockType(x, y, z);
                if (groundType == null || groundType.getMaterial() != BlockMaterial.Solid) {
                    continue;
                }

                // Check no fluid at ground level
                int fluidGround = world.getFluidId(x, y, z);
                if (fluidGround != 0) {
                    continue;
                }

                // Check 2 empty blocks above with no fluids
                int above1 = world.getBlock(x, y + 1, z);
                int above2 = world.getBlock(x, y + 2, z);
                int fluid1 = world.getFluidId(x, y + 1, z);
                int fluid2 = world.getFluidId(x, y + 2, z);

                if (above1 == 0 && above2 == 0 && fluid1 == 0 && fluid2 == 0) {
                    return y;
                }
            } catch (Exception e) {
                // Ignore and continue searching
            }
        }
        return -1;
    }

    /**
     * Get the zone number at a position using HC_Leveling's world generator query.
     * Returns 1 if HC_Leveling is unavailable (safe default).
     */
    private static int getZoneNumber(World world, int x, int z) {
        try {
            HC_LevelingPlugin levelingPlugin = HC_LevelingPlugin.getInstance();
            if (levelingPlugin != null && levelingPlugin.getNPCLevelManager() != null) {
                return levelingPlugin.getNPCLevelManager().getZoneNumber(world, x, z);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[RTP] Zone check failed: " + e.getMessage());
        }
        return 1;
    }
}
