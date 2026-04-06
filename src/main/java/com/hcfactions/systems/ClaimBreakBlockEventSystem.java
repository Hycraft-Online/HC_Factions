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
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import javax.annotation.Nullable;
import java.awt.Color;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

/**
 * Handles block destruction protection based on claim ownership.
 * This is a backup to ClaimBreakProtectionSystem (DamageBlockEvent) -
 * catches the actual block break event in case damage prevention was bypassed.
 *
 * Rules:
 * - Faction claim -> BLOCKED (protected territory, unless admin with bypass enabled)
 * - Own guild claim -> ALLOWED
 * - Same faction, different guild -> BLOCKED
 * - Enemy faction claim -> ALLOWED (can destroy enemy territory)
 * - Unclaimed land -> ALLOWED for everyone
 */
public class ClaimBreakBlockEventSystem extends EntityEventSystem<EntityStore, BreakBlockEvent> {

    private static final Message MSG_CANNOT_BREAK = Message.raw("You cannot break blocks on an allied guild's land!").color(Color.RED);
    private static final Message MSG_PROTECTED_TERRITORY = Message.raw("This is protected faction territory!").color(Color.RED);

    // Permission node for bypassing faction claim protection
    private static final String ADMIN_BYPASS_PERMISSION = "factionguilds.admin.bypass";

    private final HC_FactionsPlugin plugin;

    public ClaimBreakBlockEventSystem(HC_FactionsPlugin plugin) {
        super(BreakBlockEvent.class);
        this.plugin = plugin;
    }

    @Override
    public void handle(int index, @NonNullDecl ArchetypeChunk<EntityStore> archetypeChunk,
                       @NonNullDecl Store<EntityStore> store,
                       @NonNullDecl CommandBuffer<EntityStore> commandBuffer,
                       @NonNullDecl BreakBlockEvent event) {

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
            if (plugin.getGuildChunkAccessManager().canAccessGuildClaim(
                playerData, claim, GuildChunkAccessManager.AccessAction.BREAK, null
            )) {
                return;
            }
            event.setCancelled(true);
            playerRef.sendMessage(MSG_CANNOT_BREAK);
            return;
        }

        // Enemy faction - allowed to break (can destroy enemy territory)
        if (playerFactionId != null && !playerFactionId.equals(claim.getFactionId())) {
            return;
        }

        // Same faction, different guild - cannot break (protect allied guilds)
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
