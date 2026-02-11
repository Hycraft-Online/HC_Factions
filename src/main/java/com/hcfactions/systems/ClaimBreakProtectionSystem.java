package com.hcfactions.systems;

import com.hcfactions.HC_FactionsPlugin;
import com.hcfactions.managers.ClaimManager;
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
import com.hypixel.hytale.server.core.event.events.ecs.DamageBlockEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import javax.annotation.Nullable;
import java.awt.Color;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

/**
 * Handles block break protection based on claim ownership.
 *
 * Rules:
 * - Faction claim -> BLOCKED (protected territory, unless admin)
 * - Own guild claim -> ALLOWED
 * - Same faction, different guild -> BLOCKED (unless config allows)
 * - Enemy faction claim -> BLOCKED (unless config allows)
 * - Unclaimed land -> ALLOWED for everyone
 */
public class ClaimBreakProtectionSystem extends EntityEventSystem<EntityStore, DamageBlockEvent> {

    private static final Message MSG_CANNOT_BREAK = Message.raw("You cannot break blocks on claimed land!").color(Color.RED);
    private static final Message MSG_PROTECTED_TERRITORY = Message.raw("This is protected faction territory!").color(Color.RED);

    // Permission node for bypassing faction claim protection
    private static final String ADMIN_BYPASS_PERMISSION = "factionguilds.admin.bypass";

    private final HC_FactionsPlugin plugin;

    public ClaimBreakProtectionSystem(HC_FactionsPlugin plugin) {
        super(DamageBlockEvent.class);
        this.plugin = plugin;
    }

    @Override
    public void handle(int index, @NonNullDecl ArchetypeChunk<EntityStore> archetypeChunk,
                       @NonNullDecl Store<EntityStore> store,
                       @NonNullDecl CommandBuffer<EntityStore> commandBuffer,
                       @NonNullDecl DamageBlockEvent event) {

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
        Player player = store.getComponent(ref, Player.getComponentType());
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());

        if (playerRef == null || player == null) {
            return;
        }

        // Convert block position to chunk coordinates
        int chunkX = ClaimManager.toChunkCoord(event.getTargetBlock().getX());
        int chunkZ = ClaimManager.toChunkCoord(event.getTargetBlock().getZ());

        // Check claim
        Claim claim = plugin.getClaimManager().getClaim(worldName, chunkX, chunkZ);
        if (claim == null) {
            // Unclaimed land - everyone can break
            return;
        }

        // Admin bypass - applies to ALL claim types (faction, guild, solo)
        if (player.hasPermission(ADMIN_BYPASS_PERMISSION) &&
            HC_FactionsPlugin.isBypassEnabled(playerRef.getUuid())) {
            return;
        }

        // Faction claims block ALL breaking (protected territory like capitals)
        if (claim.isFactionClaim()) {
            if (HC_FactionsPlugin.isFactionEditor(playerRef.getUuid())) {
                return; // Faction editor
            }

            // External bypass check (e.g. market zones)
            if (HC_FactionsPlugin.isClaimBypassed(playerRef.getUuid(), worldName,
                    event.getTargetBlock().getX(), event.getTargetBlock().getY(), event.getTargetBlock().getZ(),
                    HC_FactionsPlugin.ClaimBypassOperation.BREAK)) {
                return;
            }

            // Block the break
            event.setCancelled(true);
            playerRef.sendMessage(MSG_PROTECTED_TERRITORY);
            return;
        }

        // Get player data
        PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
        UUID playerGuildId = playerData != null ? playerData.getGuildId() : null;
        String playerFactionId = playerData != null ? playerData.getFactionId() : null;

        // Solo player claims - only owner can break
        if (claim.isSoloPlayerClaim()) {
            if (playerRef.getUuid().equals(claim.getPlayerOwnerId())) {
                return; // Owner can break
            }
            event.setCancelled(true);
            playerRef.sendMessage(MSG_CANNOT_BREAK);
            return;
        }

        // Same guild - always allowed
        if (playerGuildId != null && playerGuildId.equals(claim.getGuildId())) {
            return;
        }

        // Check if same faction (different guild)
        if (playerFactionId != null && playerFactionId.equals(claim.getFactionId())) {
            // Check if same-faction guild access is allowed
            if (plugin.getConfig().isProtectionSameFactionGuildAccess()) {
                return; // Same faction can break in allied guild claims
            }
            event.setCancelled(true);
            playerRef.sendMessage(MSG_CANNOT_BREAK);
            return;
        }

        // Enemy faction - check config if they can destroy
        if (plugin.getConfig().isProtectionEnemyCanDestroy()) {
            return; // Enemies can destroy enemy territory
        }
        event.setCancelled(true);
        playerRef.sendMessage(MSG_CANNOT_BREAK);
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
