package com.hcfactions.interactions;

import com.hcfactions.HC_FactionsPlugin;
import com.hcfactions.managers.ClaimManager;
import com.hcfactions.models.Claim;
import com.hcfactions.models.PlayerData;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.UseBlockInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.awt.Color;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Custom UseBlock interaction that checks claim permissions before allowing harvesting.
 * Replaces the vanilla UseBlock interaction via codec registry.
 *
 * Only blocks HARVESTABLE interactions (right-click to destroy plants/crops/flowers).
 * Non-harvestable tertiary interactions (portals, doors, benches, chests, quest boards)
 * are passed through — those are handled by ClaimInteractProtectionSystem via UseBlockEvent.Pre.
 *
 * This two-layer approach exists because UseBlockEvent.Pre cancellation does NOT prevent
 * block harvesting (the block gets destroyed before the event fires), but it DOES work
 * for other block interactions like opening chests or using portals.
 */
public class ClaimUseBlockInteraction extends UseBlockInteraction {

    @Nonnull
    public static final BuilderCodec<ClaimUseBlockInteraction> CODEC =
        ((BuilderCodec.Builder) BuilderCodec.builder(ClaimUseBlockInteraction.class,
            ClaimUseBlockInteraction::new, (BuilderCodec) SimpleBlockInteraction.CODEC)
            .documentation("Attempts to use the target block with claim protection."))
        .build();

    private static final Message MSG_PROTECTED = Message.raw("This is protected faction territory!").color(Color.RED);
    private static final Message MSG_CLAIMED = Message.raw("You cannot harvest here!").color(Color.RED);
    private static final String ADMIN_BYPASS_PERMISSION = "factionguilds.admin.bypass";

    /**
     * Patterns that identify harvestable blocks (destroyed on right-click interaction).
     * Only these blocks need interaction-handler-level protection.
     * Everything else (doors, portals, chests, benches) is handled by event systems.
     */
    private static final Set<String> HARVESTABLE_PATTERNS = Set.of(
        // Grass
        "grass",
        // Flowers
        "flower", "tulip", "rose", "daisy", "poppy", "dandelion",
        "lavender", "orchid", "lily", "sunflower", "marigold", "peony",
        "allium", "cornflower", "bluebell",
        // Mushrooms
        "mushroom", "fungus",
        // Crops
        "crop", "wheat", "carrot", "potato", "berry",
        // Foliage
        "fern", "vine", "moss", "lichen", "clover",
        "seaweed", "kelp", "seagrass", "coral",
        // Plants
        "fruit", "bush", "bramble", "cactus", "petal", "reed",
        "sapling", "hay"
    );

    /**
     * Check if a block name matches any harvestable pattern (case-insensitive contains match).
     */
    private static boolean isHarvestable(String blockName) {
        String lower = blockName.toLowerCase(Locale.ROOT);
        for (String pattern : HARVESTABLE_PATTERNS) {
            if (lower.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void interactWithBlock(@Nonnull World world, @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                     @Nonnull InteractionType type, @Nonnull InteractionContext context,
                                     @Nullable ItemStack itemInHand, @Nonnull Vector3i targetBlock,
                                     @Nonnull CooldownHandler cooldownHandler) {

        // Primary and Secondary interactions are handled by other systems
        if (type == InteractionType.Primary || type == InteractionType.Secondary) {
            super.interactWithBlock(world, commandBuffer, type, context, itemInHand, targetBlock, cooldownHandler);
            return;
        }

        // Skip arena/instance worlds
        if (HC_FactionsPlugin.isArenaWorld(world.getName())) {
            super.interactWithBlock(world, commandBuffer, type, context, itemInHand, targetBlock, cooldownHandler);
            return;
        }

        // Only check claims for harvestable blocks (plants, crops, flowers, etc.)
        // Non-harvestable blocks (portals, doors, chests, benches) pass through to event system
        String blockId = world.getBlockType(targetBlock).getId();
        if (!isHarvestable(blockId)) {
            super.interactWithBlock(world, commandBuffer, type, context, itemInHand, targetBlock, cooldownHandler);
            return;
        }

        // Harvestable block on claimed land — check permissions
        Ref<EntityStore> ref = context.getEntity();
        Store<EntityStore> store = ref.getStore();
        Player player = store.getComponent(ref, Player.getComponentType());
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());

        if (player == null || playerRef == null) {
            return;
        }

        String worldName = world.getName();
        int chunkX = ClaimManager.toChunkCoord(targetBlock.getX());
        int chunkZ = ClaimManager.toChunkCoord(targetBlock.getZ());

        HC_FactionsPlugin plugin = HC_FactionsPlugin.getInstance();
        if (plugin == null) {
            super.interactWithBlock(world, commandBuffer, type, context, itemInHand, targetBlock, cooldownHandler);
            return;
        }

        Claim claim = plugin.getClaimManager().getClaim(worldName, chunkX, chunkZ);
        if (claim == null) {
            // Unclaimed land — allow
            super.interactWithBlock(world, commandBuffer, type, context, itemInHand, targetBlock, cooldownHandler);
            return;
        }

        // Admin bypass — applies to ALL claim types (faction, guild, solo)
        if (player.hasPermission(ADMIN_BYPASS_PERMISSION) &&
            HC_FactionsPlugin.isBypassEnabled(playerRef.getUuid())) {
            super.interactWithBlock(world, commandBuffer, type, context, itemInHand, targetBlock, cooldownHandler);
            return;
        }

        // Faction claims block harvesting for everyone except editors
        if (claim.isFactionClaim()) {
            if (HC_FactionsPlugin.isFactionEditor(playerRef.getUuid())) {
                super.interactWithBlock(world, commandBuffer, type, context, itemInHand, targetBlock, cooldownHandler);
                return;
            }
            playerRef.sendMessage(MSG_PROTECTED);
            return; // Block — don't call super
        }

        // Solo player claim — only owner
        if (claim.isSoloPlayerClaim()) {
            if (playerRef.getUuid().equals(claim.getPlayerOwnerId())) {
                super.interactWithBlock(world, commandBuffer, type, context, itemInHand, targetBlock, cooldownHandler);
                return;
            }
            playerRef.sendMessage(MSG_CLAIMED);
            return;
        }

        // Guild claim — same guild allowed
        PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
        UUID playerGuildId = playerData != null ? playerData.getGuildId() : null;
        if (playerGuildId != null && playerGuildId.equals(claim.getGuildId())) {
            super.interactWithBlock(world, commandBuffer, type, context, itemInHand, targetBlock, cooldownHandler);
            return;
        }

        // Not allowed
        playerRef.sendMessage(MSG_CLAIMED);
    }

    @Override
    protected void simulateInteractWithBlock(@Nonnull InteractionType type, @Nonnull InteractionContext context,
                                             @Nullable ItemStack itemInHand, @Nonnull World world,
                                             @Nonnull Vector3i targetBlock) {

        if (type != InteractionType.Primary && type != InteractionType.Secondary
                && !HC_FactionsPlugin.isArenaWorld(world.getName())) {

            String blockId = world.getBlockType(targetBlock).getId();
            if (isHarvestable(blockId)) {
                Ref<EntityStore> ref = context.getEntity();
                Store<EntityStore> store = ref.getStore();
                Player player = store.getComponent(ref, Player.getComponentType());
                PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());

                if (player != null && playerRef != null) {
                    String worldName = world.getName();
                    int chunkX = ClaimManager.toChunkCoord(targetBlock.getX());
                    int chunkZ = ClaimManager.toChunkCoord(targetBlock.getZ());

                    HC_FactionsPlugin plugin = HC_FactionsPlugin.getInstance();
                    if (plugin != null) {
                        Claim claim = plugin.getClaimManager().getClaim(worldName, chunkX, chunkZ);
                        if (claim != null) {
                            // Admin bypass — applies to ALL claim types
                            if (player.hasPermission(ADMIN_BYPASS_PERMISSION) &&
                                HC_FactionsPlugin.isBypassEnabled(playerRef.getUuid())) {
                                // Allow — fall through to super
                            } else if (claim.isFactionClaim()) {
                                if (!HC_FactionsPlugin.isFactionEditor(playerRef.getUuid())) {
                                    return; // Block simulation
                                }
                            } else if (claim.isSoloPlayerClaim()) {
                                if (!playerRef.getUuid().equals(claim.getPlayerOwnerId())) {
                                    return;
                                }
                            } else {
                                PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
                                UUID playerGuildId = playerData != null ? playerData.getGuildId() : null;
                                if (playerGuildId == null || !playerGuildId.equals(claim.getGuildId())) {
                                    return;
                                }
                            }
                        }
                    }
                }
            }
        }

        super.simulateInteractWithBlock(type, context, itemInHand, world, targetBlock);
    }
}
