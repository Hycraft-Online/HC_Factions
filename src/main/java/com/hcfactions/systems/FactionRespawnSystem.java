package com.hcfactions.systems;

import com.hcfactions.HC_FactionsPlugin;
import com.hcfactions.models.Faction;
import com.hcfactions.models.PlayerData;

import com.hypixel.hytale.builtin.instances.config.InstanceEntityConfig;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.data.PlayerRespawnPointData;
import com.hypixel.hytale.server.core.entity.entities.player.data.PlayerWorldData;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;
import java.util.logging.Level;

/**
 * System that teleports players to their faction spawn point after respawning.
 * 
 * This extends RefChangeSystem to listen for DeathComponent removal events,
 * which occur when a player respawns. After the default respawn teleport completes,
 * this system checks if the player has a faction and teleports them to the
 * faction-specific spawn point.
 */
public class FactionRespawnSystem extends RefChangeSystem<EntityStore, DeathComponent> {

    private final HC_FactionsPlugin plugin;

    public FactionRespawnSystem(HC_FactionsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    @Override
    @Nonnull
    public ComponentType<EntityStore, DeathComponent> componentType() {
        return DeathComponent.getComponentType();
    }

    @Override
    public void onComponentAdded(@Nonnull Ref<EntityStore> ref, @Nonnull DeathComponent component,
                                  @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        // Player died - no action needed here
    }

    @Override
    public void onComponentSet(@Nonnull Ref<EntityStore> ref, @Nullable DeathComponent oldComponent,
                                @Nonnull DeathComponent newComponent, @Nonnull Store<EntityStore> store,
                                @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        // Death component updated - no action needed
    }

    @Override
    public void onComponentRemoved(@Nonnull Ref<EntityStore> ref, @Nonnull DeathComponent component,
                                    @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        // Player is respawning - check if they should be teleported to faction spawn
        try {
            Player playerComponent = store.getComponent(ref, Player.getComponentType());
            if (playerComponent == null) {
                return;
            }

            // Skip if player has an instance config - they just exited an instance
            // and the ExitInstance RespawnController already handled their teleport
            InstanceEntityConfig instanceConfig = commandBuffer.getComponent(ref, InstanceEntityConfig.getComponentType());
            if (instanceConfig != null) {
                plugin.getLogger().at(Level.INFO).log(
                    "[FactionRespawn] Player has InstanceEntityConfig - skipping faction teleport (instance exit)"
                );
                return;
            }

            UUID playerUuid = playerComponent.getPlayerRef().getUuid();
            var externalData = store.getExternalData();
            if (externalData == null || externalData.getWorld() == null) {
                return;
            }
            World world = externalData.getWorld();
            String currentWorldName = world.getName();

            // Check if player has a bed/respawn point set - if so, let bed spawn take precedence
            PlayerWorldData playerWorldData = playerComponent.getPlayerConfigData().getPerWorldData(currentWorldName);
            if (playerWorldData != null) {
                PlayerRespawnPointData[] respawnPoints = playerWorldData.getRespawnPoints();
                if (respawnPoints != null && respawnPoints.length > 0) {
                    // Player has a bed spawn point - don't override with faction spawn
                    plugin.getLogger().at(Level.FINE).log(
                        "[FactionRespawn] Player %s has %d respawn point(s) set - using bed spawn instead of faction spawn",
                        playerUuid.toString().substring(0, 8),
                        respawnPoints.length
                    );
                    return;
                }
            }

            // Look up player's faction
            PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerUuid);
            if (playerData == null || playerData.getFactionId() == null) {
                // Player has no faction - use default spawn
                return;
            }

            Faction faction = plugin.getFactionManager().getFaction(playerData.getFactionId());
            if (faction == null) {
                return;
            }

            // Check if faction spawn is in the current world
            String factionSpawnWorld = faction.getSpawnWorld();
            if (factionSpawnWorld == null || !factionSpawnWorld.equals(currentWorldName)) {
                // Faction spawn is in a different world - use default spawn for this world
                plugin.getLogger().at(Level.FINE).log(
                    "[FactionRespawn] Player %s faction spawn is in world '%s', but respawning in '%s' - using default",
                    playerUuid.toString().substring(0, 8),
                    factionSpawnWorld,
                    currentWorldName
                );
                return;
            }

            // Create teleport to faction spawn
            Transform factionSpawn = new Transform(
                new Vector3d(faction.getSpawnX(), faction.getSpawnY(), faction.getSpawnZ()),
                Vector3f.ZERO
            );

            plugin.getLogger().at(Level.INFO).log(
                "[FactionRespawn] Teleporting player %s to %s faction spawn (%.1f, %.1f, %.1f)",
                playerUuid.toString().substring(0, 8),
                faction.getDisplayName(),
                faction.getSpawnX(),
                faction.getSpawnY(),
                faction.getSpawnZ()
            );

            // Schedule teleport on the world executor to avoid "Store is currently processing" error
            world.execute(() -> {
                if (!ref.isValid()) {
                    return;
                }
                Store<EntityStore> entityStore = world.getEntityStore().getStore();
                Teleport teleportComponent = Teleport.createForPlayer(factionSpawn);
                entityStore.addComponent(ref, Teleport.getComponentType(), teleportComponent);
            });

        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).withCause(e).log(
                "[FactionRespawn] Error handling faction respawn"
            );
        }
    }
}
