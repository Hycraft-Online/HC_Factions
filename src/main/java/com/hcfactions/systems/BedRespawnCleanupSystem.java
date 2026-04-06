package com.hcfactions.systems;

import com.hcfactions.HC_FactionsPlugin;

import com.hypixel.hytale.common.util.ArrayUtil;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.data.PlayerRespawnPointData;
import com.hypixel.hytale.server.core.entity.entities.player.data.PlayerWorldData;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.logging.Level;

/**
 * Cleans up stale bed respawn points when a player uses a bed block.
 *
 * Problem: When a bed is moved (broken and placed elsewhere), the player's
 * {@link PlayerRespawnPointData} still references the old bed location. The vanilla
 * {@code RespawnBlock.OnRemove} system only clears respawn data for online players
 * who own that specific bed block. If the bed was in a guild claim (where ownership
 * may not be set), or the player was offline when the bed was broken, the stale
 * respawn point persists.
 *
 * Fix: After a player uses a bed (UseBlockEvent.Post), we scan their respawn points
 * and remove any that point to positions where a bed block no longer exists. This
 * ensures the player's respawn data stays in sync with the actual world state.
 */
public class BedRespawnCleanupSystem extends EntityEventSystem<EntityStore, UseBlockEvent.Post> {

    private final HC_FactionsPlugin plugin;

    public BedRespawnCleanupSystem(HC_FactionsPlugin plugin) {
        super(UseBlockEvent.Post.class);
        this.plugin = plugin;
    }

    @Override
    public void handle(int index, @NonNullDecl ArchetypeChunk<EntityStore> archetypeChunk,
                       @NonNullDecl Store<EntityStore> store,
                       @NonNullDecl CommandBuffer<EntityStore> commandBuffer,
                       @NonNullDecl UseBlockEvent.Post event) {

        // Only process bed block interactions
        String blockId = event.getBlockType() != null ? event.getBlockType().getId() : null;
        if (blockId == null || !isBedBlock(blockId)) {
            return;
        }

        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        if (ref == null || !ref.isValid()) {
            return;
        }
        Player playerComponent = store.getComponent(ref, Player.getComponentType());
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());

        if (playerComponent == null || playerRef == null) {
            return;
        }

        var externalData = store.getExternalData();
        if (externalData == null || externalData.getWorld() == null) {
            return;
        }

        World world = externalData.getWorld();
        String worldName = world.getName();

        // Skip arena/instance worlds
        if (HC_FactionsPlugin.isArenaWorld(worldName)) {
            return;
        }

        // Get player's respawn points for this world
        PlayerWorldData playerWorldData = playerComponent.getPlayerConfigData().getPerWorldData(worldName);
        if (playerWorldData == null) {
            return;
        }

        PlayerRespawnPointData[] respawnPoints = playerWorldData.getRespawnPoints();
        if (respawnPoints == null || respawnPoints.length == 0) {
            return;
        }

        // Check each respawn point - remove any where the bed block no longer exists
        boolean changed = false;
        Vector3i currentBedPos = event.getTargetBlock();

        for (int i = respawnPoints.length - 1; i >= 0; i--) {
            PlayerRespawnPointData respawnPoint = respawnPoints[i];
            Vector3i blockPos = respawnPoint.getBlockPosition();

            if (blockPos == null) {
                continue;
            }

            // Skip the bed we just interacted with - it's valid
            if (blockPos.equals(currentBedPos)) {
                continue;
            }

            // Check if there's still a bed at this respawn point's block position
            try {
                var blockType = world.getBlockType(blockPos);
                if (blockType == null || !isBedBlock(blockType.getId())) {
                    // No bed at this position anymore - remove the stale respawn point
                    plugin.getLogger().at(Level.INFO).log(
                        "[BedRespawnCleanup] Removing stale respawn point '%s' at (%d, %d, %d) for player %s - bed no longer exists",
                        respawnPoint.getName(),
                        blockPos.getX(), blockPos.getY(), blockPos.getZ(),
                        playerRef.getUsername()
                    );
                    respawnPoints = ArrayUtil.remove(respawnPoints, i);
                    changed = true;
                }
            } catch (Exception e) {
                // Chunk may not be loaded - skip this check rather than crash
                plugin.getLogger().at(Level.FINE).log(
                    "[BedRespawnCleanup] Could not check block at (%d, %d, %d) - chunk may not be loaded",
                    blockPos.getX(), blockPos.getY(), blockPos.getZ()
                );
            }
        }

        if (changed) {
            playerWorldData.setRespawnPoints(respawnPoints);
            plugin.getLogger().at(Level.INFO).log(
                "[BedRespawnCleanup] Cleaned up stale respawn points for %s, %d remaining",
                playerRef.getUsername(),
                respawnPoints.length
            );
        }
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return PlayerRef.getComponentType();
    }

    /**
     * Check if a block ID represents a bed block.
     * Bed blocks in Hytale follow naming patterns like "Bed_*" or contain "bed" in their ID.
     */
    private static boolean isBedBlock(String blockId) {
        if (blockId == null) {
            return false;
        }
        String lower = blockId.toLowerCase(Locale.ROOT);
        return lower.contains("bed");
    }
}
