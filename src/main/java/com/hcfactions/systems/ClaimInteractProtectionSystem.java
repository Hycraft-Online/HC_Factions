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
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import javax.annotation.Nullable;
import java.awt.Color;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

/**
 * Handles block interaction protection based on claim ownership.
 * This prevents players from picking up flowers, harvesting wheat, etc. in protected areas.
 * 
 * Rules:
 * - Faction claim -> BLOCKED (protected territory, unless admin)
 * - Own guild claim -> ALLOWED
 * - Same faction, different guild -> BLOCKED
 * - Enemy faction claim -> BLOCKED (can't harvest enemy resources)
 * - Unclaimed land -> ALLOWED for everyone
 */
public class ClaimInteractProtectionSystem extends EntityEventSystem<EntityStore, UseBlockEvent.Pre> {

    private static final Message MSG_CANNOT_INTERACT = Message.raw("You cannot interact with blocks here!").color(Color.RED);
    private static final Message MSG_PROTECTED_TERRITORY = Message.raw("This is protected faction territory!").color(Color.RED);

    // Permission node for bypassing faction claim protection
    private static final String ADMIN_BYPASS_PERMISSION = "factionguilds.admin.bypass";

    private final HC_FactionsPlugin plugin;

    public ClaimInteractProtectionSystem(HC_FactionsPlugin plugin) {
        super(UseBlockEvent.Pre.class);
        this.plugin = plugin;
    }

    @Override
    public void handle(int index, @NonNullDecl ArchetypeChunk<EntityStore> archetypeChunk,
                       @NonNullDecl Store<EntityStore> store,
                       @NonNullDecl CommandBuffer<EntityStore> commandBuffer,
                       @NonNullDecl UseBlockEvent.Pre event) {
        
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

        // Get block ID for filtering
        String blockId = event.getBlockType() != null ? event.getBlockType().getId() : "unknown";
        
        boolean isContainer = isContainerBlock(blockId);

        // Containers/chests are always claim-protected.
        // Other interactions follow blacklist/whitelist behavior.
        if (!isContainer) {
            if (plugin.getPickupBlacklistManager().isWhitelisted(blockId)) {
                return; // Always allow
            }

            if (!plugin.getPickupBlacklistManager().isBlacklisted(blockId)) {
                return; // Not blacklisted, allow interaction
            }
        }

        // Block is blacklisted - now check claim protection
        // worldName already retrieved at start of method
        int chunkX = ClaimManager.toChunkCoord(event.getTargetBlock().getX());
        int chunkZ = ClaimManager.toChunkCoord(event.getTargetBlock().getZ());

        // Check claim
        Claim claim = plugin.getClaimManager().getClaim(worldName, chunkX, chunkZ);
        if (claim == null) {
            // Unclaimed land - allow interaction
            return;
        }

        // Check admin bypass
        boolean hasPermission = player.hasPermission(ADMIN_BYPASS_PERMISSION);
        boolean bypassEnabled = HC_FactionsPlugin.isBypassEnabled(playerRef.getUuid());
        if (hasPermission && bypassEnabled) {
            return; // Admin bypass active
        }

        // External bypass check (e.g. market zones) - applies to ALL claim types
        if (HC_FactionsPlugin.isClaimBypassed(playerRef.getUuid(), worldName,
                event.getTargetBlock().getX(), event.getTargetBlock().getY(), event.getTargetBlock().getZ(),
                HC_FactionsPlugin.ClaimBypassOperation.INTERACT)) {
            return;
        }

        // Faction claims block interaction for everyone except admins and editors
        if (claim.isFactionClaim()) {
            if (HC_FactionsPlugin.isFactionEditor(playerRef.getUuid())) {
                return; // Faction editor
            }

            event.setCancelled(true);
            playerRef.sendMessage(MSG_PROTECTED_TERRITORY);
            return;
        }

        // Get player data
        PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
        UUID playerGuildId = playerData != null ? playerData.getGuildId() : null;

        // Solo player claims - only owner can interact
        if (claim.isSoloPlayerClaim()) {
            if (playerRef.getUuid().equals(claim.getPlayerOwnerId())) {
                return; // Owner can interact
            }
            event.setCancelled(true);
            playerRef.sendMessage(MSG_CANNOT_INTERACT);
            return;
        }

        // Same guild - allowed (with per-interaction-type permission check)
        if (playerGuildId != null && playerGuildId.equals(claim.getGuildId())) {
            GuildChunkAccessManager.AccessAction action = categorizeInteraction(blockId);
            if (plugin.getGuildChunkAccessManager().canAccessGuildClaim(
                playerData, claim, action, blockId
            )) {
                return;
            }
            event.setCancelled(true);
            playerRef.sendMessage(MSG_CANNOT_INTERACT);
            return;
        }

        // Any other case in a claimed area - block interaction
        event.setCancelled(true);
        playerRef.sendMessage(MSG_CANNOT_INTERACT);
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

    private boolean isContainerBlock(@Nullable String blockId) {
        if (blockId == null) {
            return false;
        }

        String lower = blockId.toLowerCase();
        return lower.contains("chest")
            || lower.contains("barrel")
            || lower.contains("crate")
            || lower.contains("storage");
    }

    /**
     * Categorizes a block interaction into a specific AccessAction type
     * based on the block ID string.
     */
    private GuildChunkAccessManager.AccessAction categorizeInteraction(@Nullable String blockId) {
        if (blockId == null) return GuildChunkAccessManager.AccessAction.INTERACT;

        String lower = blockId.toLowerCase();

        // Containers (chests, barrels, crates, storage)
        if (lower.contains("chest") || lower.contains("barrel")
                || lower.contains("crate") || lower.contains("storage")) {
            return GuildChunkAccessManager.AccessAction.INTERACT_CHESTS;
        }

        // Doors (doors, gates, trapdoors)
        if (lower.contains("door") || lower.contains("gate") || lower.contains("trapdoor")) {
            return GuildChunkAccessManager.AccessAction.INTERACT_DOORS;
        }

        // Transport (rails, minecarts)
        if (lower.contains("rail") || lower.contains("minecart") || lower.contains("cart")) {
            return GuildChunkAccessManager.AccessAction.INTERACT_TRANSPORT;
        }

        // Processing (furnaces, smelters, kilns, anvils)
        if (lower.contains("furnace") || lower.contains("smelter")
                || lower.contains("kiln") || lower.contains("anvil")) {
            return GuildChunkAccessManager.AccessAction.INTERACT_PROCESSING;
        }

        // Workbenches (check before generic "bench" for seats)
        if (lower.contains("workbench") || lower.contains("crafting")
                || lower.contains("loom") || lower.contains("sawmill")) {
            return GuildChunkAccessManager.AccessAction.INTERACT_BENCHES;
        }

        // Seats (chairs, benches, thrones, stools)
        if (lower.contains("chair") || lower.contains("bench")
                || lower.contains("throne") || lower.contains("seat")
                || lower.contains("stool")) {
            return GuildChunkAccessManager.AccessAction.INTERACT_SEATS;
        }

        // Default: generic interact
        return GuildChunkAccessManager.AccessAction.INTERACT;
    }
}
