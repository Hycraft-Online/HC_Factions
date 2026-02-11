package com.hcfactions.systems;

import com.hcfactions.HC_FactionsPlugin;
import com.hcfactions.managers.ClaimManager;
import com.hcfactions.models.Claim;
import com.hcfactions.models.Guild;
import com.hcfactions.models.Faction;
import com.hcfactions.models.PlayerData;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.RootDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import javax.annotation.Nullable;
import java.awt.Color;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Notifies players when they enter or leave claimed territory.
 */
public class ChunkEnterNotifySystem extends EntityTickingSystem<EntityStore> {

    private final HC_FactionsPlugin plugin;
    
    // Track last chunk per player to detect chunk changes
    private final Map<UUID, String> playerLastChunk = new ConcurrentHashMap<>();
    
    // Throttle updates
    private int tickCounter = 0;
    private static final int CHECK_INTERVAL = 10;

    public ChunkEnterNotifySystem(HC_FactionsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void tick(float deltaTime, int index, @NonNullDecl ArchetypeChunk<EntityStore> archetypeChunk,
                     @NonNullDecl Store<EntityStore> store,
                     @NonNullDecl CommandBuffer<EntityStore> commandBuffer) {
        
        // Skip arena/instance worlds - no claim notifications there
        var externalData = store.getExternalData();
        if (externalData == null || externalData.getWorld() == null) {
            return;
        }
        String worldName = externalData.getWorld().getName();
        if (HC_FactionsPlugin.isArenaWorld(worldName)) {
            return;
        }

        // Throttle updates
        tickCounter++;
        if (tickCounter % CHECK_INTERVAL != 0) {
            return;
        }

        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        if (!ref.isValid()) return;

        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        Player player = store.getComponent(ref, Player.getComponentType());

        if (playerRef == null || player == null) {
            return;
        }

        Vector3d position = playerRef.getTransform().getPosition();
        // worldName already retrieved at start of method
        int chunkX = ClaimManager.toChunkCoord(position.getX());
        int chunkZ = ClaimManager.toChunkCoord(position.getZ());

        String currentChunkKey = worldName + ":" + chunkX + ":" + chunkZ;
        String lastChunkKey = playerLastChunk.get(playerRef.getUuid());

        // Check if player changed chunks
        if (lastChunkKey == null || !lastChunkKey.equals(currentChunkKey)) {
            playerLastChunk.put(playerRef.getUuid(), currentChunkKey);

            // Get old and new claims
            Claim oldClaim = null;
            if (lastChunkKey != null) {
                String[] parts = lastChunkKey.split(":");
                if (parts.length == 3) {
                    try {
                        oldClaim = plugin.getClaimManager().getClaim(parts[0], 
                            Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                    } catch (NumberFormatException ignored) {}
                }
            }

            Claim newClaim = plugin.getClaimManager().getClaim(worldName, chunkX, chunkZ);

            // Check if claim changed
            UUID oldGuildId = oldClaim != null ? oldClaim.getGuildId() : null;
            UUID newGuildId = newClaim != null ? newClaim.getGuildId() : null;

            if ((oldGuildId == null && newGuildId != null) || 
                (oldGuildId != null && !oldGuildId.equals(newGuildId))) {
                
                // Entered new territory
                if (newClaim != null) {
                    sendTerritoryMessage(playerRef, newClaim);
                } else {
                    // Left claimed territory, now in wilderness
                    playerRef.sendMessage(Message.raw("~ Wilderness ~").color(Color.GRAY));
                }
            }
        }
    }

    private void sendTerritoryMessage(PlayerRef playerRef, Claim claim) {
        Guild guild = plugin.getGuildRepository().getGuild(claim.getGuildId());
        Faction faction = plugin.getFactionManager().getFaction(claim.getFactionId());

        if (guild == null || faction == null) {
            return;
        }

        // Get player's faction
        PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
        String playerFactionId = playerData != null ? playerData.getFactionId() : null;
        UUID playerGuildId = playerData != null ? playerData.getGuildId() : null;

        Color territoryColor;
        String relation;

        if (playerGuildId != null && playerGuildId.equals(claim.getGuildId())) {
            // Own guild
            territoryColor = Color.GREEN;
            relation = "Your Guild";
        } else if (playerFactionId != null && playerFactionId.equals(claim.getFactionId())) {
            // Allied (same faction, different guild)
            territoryColor = Color.CYAN;
            relation = "Friendly";
        } else {
            // Enemy faction
            territoryColor = Color.RED;
            relation = "Enemy";
        }

        String message = "~ " + guild.getName() + " [" + faction.getDisplayName() + "] - " + relation + " ~";
        playerRef.sendMessage(Message.raw(message).color(territoryColor));
    }

    /**
     * Clears tracking for a player (call on disconnect).
     */
    public void removePlayer(UUID playerUuid) {
        playerLastChunk.remove(playerUuid);
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return PlayerRef.getComponentType();
    }

    @NonNullDecl
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return Collections.singleton(RootDependency.first());
    }
}
