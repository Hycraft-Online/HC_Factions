package com.hcfactions.systems;

import com.hcfactions.map.ClaimMapManager;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.DelayedSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

/**
 * Ticking system that processes map update queue and refreshes
 * the world map for players when claims change.
 */
public class WorldMapUpdateSystem extends DelayedSystem<ChunkStore> {

    public WorldMapUpdateSystem() {
        // Check every 3 ticks (about 150ms at 20 TPS)
        super(3);
    }

    @Override
    public void delayedTick(float deltaTime, int tick, @NonNullDecl Store<ChunkStore> store) {
        var externalData = store.getExternalData();
        if (externalData == null || externalData.getWorld() == null) {
            return;
        }
        World world = externalData.getWorld();
        String worldName = world.getName();

        if (ClaimMapManager.getInstance().getMapUpdateQueue().containsKey(worldName)) {
            final var chunks = ClaimMapManager.getInstance().getMapUpdateQueue().get(worldName);
            
            if (chunks != null && !chunks.isEmpty()) {
                world.execute(() -> {
                    try {
                        // Clear the cached map images for these chunks
                        world.getWorldMapManager().clearImagesInChunks(chunks);

                        // Force all players to re-fetch these chunks
                        for (PlayerRef playerRef : world.getPlayerRefs()) {
                            var ref = playerRef.getReference();
                            if (ref != null && ref.isValid()) {
                                var player = ref.getStore().getComponent(ref, Player.getComponentType());
                                if (player != null) {
                                    player.getWorldMapTracker().clearChunks(chunks);
                                }
                            }
                        }
                    } catch (Exception e) {
                        // Log but don't crash
                    }
                });

                // Clear the queue for this world
                ClaimMapManager.getInstance().getMapUpdateQueue().remove(worldName);
                ClaimMapManager.getInstance().getWorldsNeedingUpdates().remove(worldName);
            }
        }
    }
}
