package com.hcfactions.interactions;

import com.hcfactions.HC_FactionsPlugin;
import com.hcfactions.managers.ClaimManager;
import com.hcfactions.managers.GuildChunkAccessManager;
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
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.ChangeBlockInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.awt.Color;
import java.util.UUID;

/**
 * Custom ChangeBlock interaction that checks claim permissions before allowing block changes.
 * This prevents hoes from tilling soil on claimed land that the player doesn't own.
 * Replaces the vanilla ChangeBlock interaction via codec registry.
 */
public class ClaimChangeBlockInteraction extends ChangeBlockInteraction {

    @Nonnull
    public static final BuilderCodec<ClaimChangeBlockInteraction> CODEC =
        ((BuilderCodec.Builder) BuilderCodec.builder(ClaimChangeBlockInteraction.class,
            ClaimChangeBlockInteraction::new, (BuilderCodec) ChangeBlockInteraction.CODEC)
            .documentation("Changes the target block with claim protection."))
        .build();

    private static final Message MSG_PROTECTED = Message.raw("This is protected faction territory!").color(Color.RED);
    private static final Message MSG_CLAIMED = Message.raw("You cannot modify blocks here!").color(Color.RED);
    private static final String ADMIN_BYPASS_PERMISSION = "factionguilds.admin.bypass";

    @Override
    protected void interactWithBlock(@Nonnull World world, @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                     @Nonnull InteractionType type, @Nonnull InteractionContext context,
                                     @Nullable ItemStack itemInHand, @Nonnull Vector3i targetBlock,
                                     @Nonnull CooldownHandler cooldownHandler) {

        // Skip arena/instance worlds
        if (HC_FactionsPlugin.isArenaWorld(world.getName())) {
            super.interactWithBlock(world, commandBuffer, type, context, itemInHand, targetBlock, cooldownHandler);
            return;
        }

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
            // Unclaimed land - allow
            super.interactWithBlock(world, commandBuffer, type, context, itemInHand, targetBlock, cooldownHandler);
            return;
        }

        // Admin bypass
        if (player.hasPermission(ADMIN_BYPASS_PERMISSION) &&
            HC_FactionsPlugin.isBypassEnabled(playerRef.getUuid())) {
            super.interactWithBlock(world, commandBuffer, type, context, itemInHand, targetBlock, cooldownHandler);
            return;
        }

        // Faction claims block for everyone except editors
        if (claim.isFactionClaim()) {
            if (HC_FactionsPlugin.isFactionEditor(playerRef.getUuid())) {
                super.interactWithBlock(world, commandBuffer, type, context, itemInHand, targetBlock, cooldownHandler);
                return;
            }
            playerRef.sendMessage(MSG_PROTECTED);
            return;
        }

        // Solo player claim - only owner
        if (claim.isSoloPlayerClaim()) {
            if (playerRef.getUuid().equals(claim.getPlayerOwnerId())) {
                super.interactWithBlock(world, commandBuffer, type, context, itemInHand, targetBlock, cooldownHandler);
                return;
            }
            playerRef.sendMessage(MSG_CLAIMED);
            return;
        }

        // Guild claim - check membership and access
        PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
        UUID playerGuildId = playerData != null ? playerData.getGuildId() : null;
        if (playerGuildId != null && playerGuildId.equals(claim.getGuildId())
            && plugin.getGuildChunkAccessManager().canAccessGuildClaim(
                playerData, claim, GuildChunkAccessManager.AccessAction.PLACE, null
            )) {
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

        if (!HC_FactionsPlugin.isArenaWorld(world.getName())) {
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
                        // Admin bypass
                        if (player.hasPermission(ADMIN_BYPASS_PERMISSION) &&
                            HC_FactionsPlugin.isBypassEnabled(playerRef.getUuid())) {
                            // Allow - fall through to super
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
                            if (playerGuildId == null || !playerGuildId.equals(claim.getGuildId())
                                || !plugin.getGuildChunkAccessManager().canAccessGuildClaim(
                                    playerData, claim, GuildChunkAccessManager.AccessAction.PLACE, null
                                )) {
                                return;
                            }
                        }
                    }
                }
            }
        }

        super.simulateInteractWithBlock(type, context, itemInHand, world, targetBlock);
    }
}
