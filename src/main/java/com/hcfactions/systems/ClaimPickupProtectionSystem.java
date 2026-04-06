package com.hcfactions.systems;

import com.hcfactions.HC_FactionsPlugin;
import com.hcfactions.managers.ClaimManager;
import com.hcfactions.managers.GuildChunkAccessManager;
import com.hcfactions.models.Claim;
import com.hcfactions.models.PlayerData;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.RootDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.event.events.ecs.InteractivelyPickupItemEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import javax.annotation.Nullable;
import java.awt.Color;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.logging.Level;

/**
 * Handles item pickup protection based on claim ownership using a BLACKLIST approach.
 * Only BLACKLISTED items (flowers, mushrooms, foliage) are blocked from pickup in protected areas.
 * Non-blacklisted items can be picked up anywhere.
 *
 * Uses the player's current position to determine which chunk they're in.
 *
 * Rules:
 * - Non-blacklisted item -> ALLOWED everywhere
 * - Blacklisted item + Unclaimed land -> ALLOWED
 * - Blacklisted item + Own guild claim -> ALLOWED
 * - Blacklisted item + Faction claim -> BLOCKED (unless admin bypass)
 * - Blacklisted item + Other's guild claim -> BLOCKED
 */
public class ClaimPickupProtectionSystem extends EntityEventSystem<EntityStore, InteractivelyPickupItemEvent> {

    private static final Message MSG_CANNOT_PICKUP = Message.raw("You cannot pick up items here!").color(Color.RED);
    private static final Message MSG_PROTECTED_TERRITORY = Message.raw("This is protected faction territory!").color(Color.RED);

    // Permission node for bypassing faction claim protection
    private static final String ADMIN_BYPASS_PERMISSION = "factionguilds.admin.bypass";

    // Pickup listeners - called when an interactive pickup is allowed (not cancelled)
    // BiConsumer<PlayerRef, String itemId>
    private static final List<BiConsumer<PlayerRef, String>> pickupListeners = new CopyOnWriteArrayList<>();

    /**
     * Register a listener that is called whenever an interactive item pickup is allowed.
     * This fires for crop harvests, flower pickups, and other interactive gathers.
     *
     * @param listener receives (PlayerRef, itemId)
     */
    public static void addPickupListener(BiConsumer<PlayerRef, String> listener) {
        pickupListeners.add(listener);
    }

    private final HC_FactionsPlugin plugin;

    public ClaimPickupProtectionSystem(HC_FactionsPlugin plugin) {
        super(InteractivelyPickupItemEvent.class);
        this.plugin = plugin;
    }

    @Override
    public void handle(int index, @NonNullDecl ArchetypeChunk<EntityStore> archetypeChunk,
                       @NonNullDecl Store<EntityStore> store,
                       @NonNullDecl CommandBuffer<EntityStore> commandBuffer,
                       @NonNullDecl InteractivelyPickupItemEvent event) {

        // Skip arena/instance worlds - no claim rules there
        var externalData = store.getExternalData();
        if (externalData == null || externalData.getWorld() == null) {
            return;
        }
        String worldName = externalData.getWorld().getName();
        if (HC_FactionsPlugin.isArenaWorld(worldName)) {
            return;
        }

        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        if (ref == null || !ref.isValid()) {
            return;
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());

        if (playerRef == null || player == null || transform == null) {
            return;
        }

        String itemId = event.getItemStack().getItem().getId();

        // Check if item is on the blacklist - if not, allow pickup everywhere
        if (!plugin.getPickupBlacklistManager().isBlacklisted(itemId)) {
            notifyPickupListeners(playerRef, itemId);
            return;
        }

        // worldName already retrieved at start of method
        // Get player's position and convert to chunk coordinates
        int chunkX = ClaimManager.toChunkCoord((int) transform.getPosition().getX());
        int chunkZ = ClaimManager.toChunkCoord((int) transform.getPosition().getZ());

        // Check claim
        Claim claim = plugin.getClaimManager().getClaim(worldName, chunkX, chunkZ);
        if (claim == null) {
            // Unclaimed land - allow pickup and notify listeners (grants XP)
            notifyPickupListeners(playerRef, itemId);
            return;
        }

        // Admin bypass - applies to ALL claim types (faction, guild, solo)
        if (player.hasPermission(ADMIN_BYPASS_PERMISSION) &&
            HC_FactionsPlugin.isBypassEnabled(playerRef.getUuid())) {
            notifyPickupListeners(playerRef, itemId);
            return;
        }

        // Faction claims block ALL pickups (protected territory like capitals)
        if (claim.isFactionClaim()) {
            // Block the pickup
            event.setCancelled(true);
            playerRef.sendMessage(MSG_PROTECTED_TERRITORY);
            return;
        }

        // Get player data
        PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
        UUID playerGuildId = playerData != null ? playerData.getGuildId() : null;

        // Solo player claims - only owner can pickup
        if (claim.isSoloPlayerClaim()) {
            if (playerRef.getUuid().equals(claim.getPlayerOwnerId())) {
                notifyPickupListeners(playerRef, itemId);
                return; // Owner can pickup
            }
            event.setCancelled(true);
            playerRef.sendMessage(MSG_CANNOT_PICKUP);
            return;
        }

        // Same guild - always allowed
        if (playerGuildId != null && playerGuildId.equals(claim.getGuildId())) {
            if (plugin.getGuildChunkAccessManager().canAccessGuildClaim(
                playerData, claim, GuildChunkAccessManager.AccessAction.PICKUP, null
            )) {
                notifyPickupListeners(playerRef, itemId);
                return;
            }
            event.setCancelled(true);
            playerRef.sendMessage(MSG_CANNOT_PICKUP);
            return;
        }

        // Any other case in a claimed area - block pickup
        // (Same faction different guild, or enemy faction)
        event.setCancelled(true);
        playerRef.sendMessage(MSG_CANNOT_PICKUP);
    }

    private static void notifyPickupListeners(PlayerRef playerRef, String itemId) {
        for (BiConsumer<PlayerRef, String> listener : pickupListeners) {
            try {
                listener.accept(playerRef, itemId);
            } catch (Exception e) {
                // Don't let listener errors break pickup
            }
        }
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
