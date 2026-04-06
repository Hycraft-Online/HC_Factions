package com.hcfactions.systems;

import com.hcfactions.HC_FactionsPlugin;
import com.hcfactions.hud.TerritoryHud;
import com.hcfactions.managers.ClaimManager;
import com.hcfactions.managers.GuildChunkAccessManager;
import com.hcfactions.managers.GuildChunkAccessManager.AccessAction;
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
import com.hypixel.hytale.protocol.MovementSettings;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Notifies players when they enter or leave claimed territory.
 * Also applies highway speed boosts when entering highway claims.
 */
public class ChunkEnterNotifySystem extends EntityTickingSystem<EntityStore> {

    private static final float HIGHWAY_SPEED_MULTIPLIER = 1.5f;

    private final HC_FactionsPlugin plugin;

    // Track last chunk per player to detect chunk changes
    private final Map<UUID, String> playerLastChunk = new ConcurrentHashMap<>();

    // Track players currently boosted by highway claims
    private final Set<UUID> highwayBoostedPlayers = ConcurrentHashMap.newKeySet();

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
        if (ref == null || !ref.isValid()) return;

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

            // Handle highway speed boost transitions
            boolean wasOnHighway = oldClaim != null && oldClaim.isHighwayClaim();
            boolean nowOnHighway = newClaim != null && newClaim.isHighwayClaim();
            if (wasOnHighway != nowOnHighway) {
                if (nowOnHighway) {
                    applyHighwayBoost(ref, store, playerRef);
                } else {
                    removeHighwayBoost(ref, store, playerRef);
                }
            }

            // Determine if the claim ownership changed
            boolean claimChanged = !claimsMatchOwner(oldClaim, newClaim);

            // Also detect highway<->standard transitions within the same faction
            boolean claimTypeChanged = !claimChanged && oldClaim != null && newClaim != null
                && wasOnHighway != nowOnHighway;

            if (claimChanged) {
                if (newClaim != null) {
                    // Entered claimed territory (guild, faction, or solo claim)
                    updateTerritoryHud(playerRef, newClaim);
                    updatePermissionHud(playerRef, newClaim);
                } else {
                    // Left claimed territory — hide territory HUD
                    hideTerritoryHud(playerRef);
                }
            } else if (claimTypeChanged) {
                // Same faction owner but claim type changed (highway <-> standard)
                updateTerritoryHud(playerRef, newClaim);
                updatePermissionHud(playerRef, newClaim);
            } else if (newClaim != null) {
                // Same territory owner, same type — but permissions might differ per chunk
                updatePermissionHud(playerRef, newClaim);
            }
        }
    }

    /**
     * Checks if two claims have the same owner (guild, faction, or solo player).
     * Returns true if they represent the same territory owner.
     */
    private boolean claimsMatchOwner(@Nullable Claim a, @Nullable Claim b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;

        // Compare guild IDs
        UUID guildA = a.getGuildId();
        UUID guildB = b.getGuildId();
        if (guildA != null && guildB != null) {
            return guildA.equals(guildB);
        }

        // Compare faction claims (both null guild, same faction)
        if (guildA == null && guildB == null) {
            // Both are faction or solo claims -- compare faction ID + player owner
            boolean sameFaction = a.getFactionId().equals(b.getFactionId());
            UUID ownerA = a.getPlayerOwnerId();
            UUID ownerB = b.getPlayerOwnerId();
            boolean sameOwner = (ownerA == null && ownerB == null)
                    || (ownerA != null && ownerA.equals(ownerB));
            return sameFaction && sameOwner;
        }

        // One has a guild, the other doesn't -- different owners
        return false;
    }

    /**
     * Update the persistent territory HUD for a player entering claimed territory.
     */
    private void updateTerritoryHud(PlayerRef playerRef, Claim claim) {
        TerritoryHud hud = plugin.getTerritoryHud(playerRef.getUuid());
        if (hud == null) {
            return;
        }

        Faction faction = plugin.getFactionManager().getFaction(claim.getFactionId());
        if (faction == null) {
            return;
        }

        String colorHex = faction.getColorHex();

        if (claim.isHighwayClaim()) {
            // Highway claim - gold color
            hud.updateTerritory("Highway +50% Speed", "#ffc832", true);
        } else if (claim.getGuildId() != null) {
            // Guild claim - show guild name + faction
            Guild guild = plugin.getGuildRepository().getGuild(claim.getGuildId());
            if (guild != null) {
                String displayText = guild.getName() + " [" + faction.getDisplayName() + "]";
                hud.updateTerritory(displayText, colorHex, true);
            }
        } else if (claim.isFactionClaim()) {
            // Faction-level claim (protected area)
            hud.updateTerritory("Protected Territory", colorHex, true);
        } else if (claim.isSoloPlayerClaim()) {
            // Solo player claim
            hud.updateTerritory("Player Territory", colorHex, true);
        }
    }

    /**
     * Update the permission indicators on the territory HUD for the current chunk.
     * Shows permissions for own guild claims and own solo claims; hides for all others.
     */
    private void updatePermissionHud(PlayerRef playerRef, Claim claim) {
        TerritoryHud hud = plugin.getTerritoryHud(playerRef.getUuid());
        if (hud == null) {
            return;
        }

        // Solo player claims — owner gets full access, others see nothing
        if (claim.isSoloPlayerClaim()) {
            if (playerRef.getUuid().equals(claim.getPlayerOwnerId())) {
                hud.updatePermissions(true, true, true, true, true);
            } else {
                hud.hidePermissions();
            }
            return;
        }

        // Guild claims — resolve per-action permissions for guild members
        if (claim.getGuildId() != null) {
            PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
            if (playerData == null || !playerData.isInGuild()
                    || !claim.getGuildId().equals(playerData.getGuildId())) {
                hud.hidePermissions();
                return;
            }

            GuildChunkAccessManager accessMgr = plugin.getGuildChunkAccessManager();
            boolean canBuild = accessMgr.canAccessGuildClaim(playerData, claim, AccessAction.BREAK, null);
            boolean canDoor = accessMgr.canAccessGuildClaim(playerData, claim, AccessAction.INTERACT_DOORS, null);
            boolean canChest = accessMgr.canAccessGuildClaim(playerData, claim, AccessAction.INTERACT_CHESTS, null);
            boolean canCraft = accessMgr.canAccessGuildClaim(playerData, claim, AccessAction.INTERACT_BENCHES, null);
            boolean canUse = accessMgr.canAccessGuildClaim(playerData, claim, AccessAction.INTERACT, null);

            hud.updatePermissions(canBuild, canDoor, canChest, canCraft, canUse);
            return;
        }

        // Faction/highway claims — no permissions to show
        hud.hidePermissions();
    }

    /**
     * Hide the persistent territory HUD (entering wilderness/unclaimed).
     */
    private void hideTerritoryHud(PlayerRef playerRef) {
        TerritoryHud hud = plugin.getTerritoryHud(playerRef.getUuid());
        if (hud != null) {
            hud.hide();
        }
    }

    /**
     * Clears tracking for a player (call on disconnect).
     */
    public void removePlayer(UUID playerUuid) {
        playerLastChunk.remove(playerUuid);
        highwayBoostedPlayers.remove(playerUuid);
    }

    /**
     * Resets highway speed boost for a player if active.
     * Call when a player disconnects or teleports to ensure clean state.
     */
    public void resetHighwayBoost(Ref<EntityStore> entityRef, Store<EntityStore> store, PlayerRef playerRef) {
        if (highwayBoostedPlayers.remove(playerRef.getUuid())) {
            setBaseSpeedMultiplier(entityRef, store, 1.0f);
        }
    }

    private void applyHighwayBoost(Ref<EntityStore> entityRef, Store<EntityStore> store, PlayerRef playerRef) {
        if (highwayBoostedPlayers.add(playerRef.getUuid())) {
            setBaseSpeedMultiplier(entityRef, store, HIGHWAY_SPEED_MULTIPLIER);
        }
    }

    private void removeHighwayBoost(Ref<EntityStore> entityRef, Store<EntityStore> store, PlayerRef playerRef) {
        if (highwayBoostedPlayers.remove(playerRef.getUuid())) {
            setBaseSpeedMultiplier(entityRef, store, 1.0f);
        }
    }

    /**
     * Modifies the base movement speed relative to defaults, then syncs to client.
     * Uses baseSpeed so the boost applies to all movement types including mounts.
     */
    private void setBaseSpeedMultiplier(Ref<EntityStore> entityRef, Store<EntityStore> store, float multiplier) {
        MovementManager movementManager = store.getComponent(entityRef, MovementManager.getComponentType());
        if (movementManager == null) return;

        MovementSettings current = movementManager.getSettings();
        MovementSettings defaults = movementManager.getDefaultSettings();
        if (current == null || defaults == null) return;

        current.baseSpeed = defaults.baseSpeed * multiplier;

        // Sync to client
        Player player = store.getComponent(entityRef, Player.getComponentType());
        if (player == null) return;
        PlayerRef pRef = player.getPlayerRef();
        if (pRef == null) return;
        PacketHandler packetHandler = pRef.getPacketHandler();
        if (packetHandler == null) return;
        movementManager.update(packetHandler);
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
