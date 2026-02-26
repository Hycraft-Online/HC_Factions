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
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

/**
 * Clears respawn points for ALL online players when a bed block is broken.
 *
 * The vanilla {@code RespawnBlock.OnRemove} system only clears respawn data for the
 * bed's registered owner (via {@code RespawnBlock.ownerUUID}). In guild claims, beds
 * may not have an owner set (e.g., placed by a guild member for shared use), or the
 * owner may be offline. This system catches bed breaks and proactively clears any
 * online player's respawn point data that referenced the broken bed's position.
 *
 * This runs in the BreakBlockEvent handler for the player who broke the block.
 * After detecting a bed break, it iterates all online players and removes matching
 * respawn point entries.
 */
public class BedBreakRespawnClearSystem extends EntityEventSystem<EntityStore, BreakBlockEvent> {

    private final HC_FactionsPlugin plugin;

    public BedBreakRespawnClearSystem(HC_FactionsPlugin plugin) {
        super(BreakBlockEvent.class);
        this.plugin = plugin;
    }

    @Override
    public void handle(int index, @NonNullDecl ArchetypeChunk<EntityStore> archetypeChunk,
                       @NonNullDecl Store<EntityStore> store,
                       @NonNullDecl CommandBuffer<EntityStore> commandBuffer,
                       @NonNullDecl BreakBlockEvent event) {

        // Only process bed blocks
        String blockId = event.getBlockType() != null ? event.getBlockType().getId() : null;
        if (blockId == null || !isBedBlock(blockId)) {
            return;
        }

        // Skip arena/instance worlds
        var externalData = store.getExternalData();
        if (externalData == null || externalData.getWorld() == null) {
            return;
        }
        String worldName = externalData.getWorld().getName();
        if (HC_FactionsPlugin.isArenaWorld(worldName)) {
            return;
        }

        Vector3i brokenBlockPos = event.getTargetBlock();

        // Iterate all online players and clear any respawn points at this position
        try {
            List<PlayerRef> onlinePlayers = Universe.get().getPlayers();
            for (PlayerRef onlinePlayer : onlinePlayers) {
                try {
                    Player playerComponent = onlinePlayer.getComponent(Player.getComponentType());
                    if (playerComponent == null) {
                        continue;
                    }

                    PlayerWorldData playerWorldData = playerComponent.getPlayerConfigData().getPerWorldData(worldName);
                    if (playerWorldData == null) {
                        continue;
                    }

                    PlayerRespawnPointData[] respawnPoints = playerWorldData.getRespawnPoints();
                    if (respawnPoints == null || respawnPoints.length == 0) {
                        continue;
                    }

                    // Check if any respawn point matches the broken bed position
                    for (int i = respawnPoints.length - 1; i >= 0; i--) {
                        PlayerRespawnPointData respawnPoint = respawnPoints[i];
                        Vector3i blockPos = respawnPoint.getBlockPosition();

                        if (blockPos != null && blockPos.equals(brokenBlockPos)) {
                            plugin.getLogger().at(Level.INFO).log(
                                "[BedBreakCleanup] Clearing respawn point '%s' at (%d, %d, %d) for player %s - bed was broken",
                                respawnPoint.getName(),
                                blockPos.getX(), blockPos.getY(), blockPos.getZ(),
                                onlinePlayer.getUsername()
                            );
                            respawnPoints = ArrayUtil.remove(respawnPoints, i);
                            playerWorldData.setRespawnPoints(respawnPoints);
                            // Only one respawn point per player should match a given position
                            break;
                        }
                    }
                } catch (Exception e) {
                    // Don't let one player's error prevent cleanup for others
                    plugin.getLogger().at(Level.FINE).log(
                        "[BedBreakCleanup] Error checking player respawn points: %s",
                        e.getMessage()
                    );
                }
            }
        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[BedBreakCleanup] Error iterating online players: %s",
                e.getMessage()
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
     */
    private static boolean isBedBlock(String blockId) {
        if (blockId == null) {
            return false;
        }
        String lower = blockId.toLowerCase(Locale.ROOT);
        return lower.contains("bed");
    }
}
