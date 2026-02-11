package com.hcfactions.interactions;

import com.hcfactions.HC_FactionsPlugin;
import com.hcfactions.managers.ClaimManager;
import com.hcfactions.models.Claim;
import com.hcfactions.models.PlayerData;

import com.hypixel.hytale.builtin.adventure.farming.FarmingUtil;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.awt.Color;
import java.util.UUID;

/**
 * Custom HarvestCrop interaction that checks claim permissions before allowing harvest.
 * This overrides the default HarvestCrop interaction from FarmingPlugin.
 */
public class ClaimHarvestCropInteraction extends SimpleBlockInteraction {
    
    public static final BuilderCodec<ClaimHarvestCropInteraction> CODEC = 
        ((BuilderCodec.Builder) BuilderCodec.builder(ClaimHarvestCropInteraction.class, 
            ClaimHarvestCropInteraction::new, SimpleBlockInteraction.CODEC)
            .documentation("Harvests the resources from the target farmable block with claim protection."))
        .build();

    private static final Message MSG_PROTECTED = Message.raw("This is protected faction territory!").color(Color.RED);
    private static final Message MSG_CLAIMED = Message.raw("You cannot harvest here!").color(Color.RED);
    private static final String ADMIN_BYPASS_PERMISSION = "factionguilds.admin.bypass";

    @Override
    protected void interactWithBlock(@Nonnull World world, @Nonnull CommandBuffer<EntityStore> commandBuffer, 
                                     @Nonnull InteractionType type, @Nonnull InteractionContext context, 
                                     @Nullable ItemStack itemInHand, @Nonnull Vector3i targetBlock, 
                                     @Nonnull CooldownHandler cooldownHandler) {
        
        Ref<EntityStore> ref = context.getEntity();
        Store<EntityStore> store = ref.getStore();
        Player player = store.getComponent(ref, Player.getComponentType());
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        
        // If we can't get player info, block the interaction for safety
        if (player == null || playerRef == null) {
            return;
        }
        
        // DEBUG: Log that our custom interaction is being used
        HC_FactionsPlugin debugPlugin = HC_FactionsPlugin.getInstance();
        if (debugPlugin != null) {
            debugPlugin.getLogger().at(java.util.logging.Level.INFO).log(
                "[ClaimHarvestCrop] Interaction triggered - Player: %s, Block: %s",
                playerRef.getUsername(), targetBlock);
        }
        
        // Check claim protection
        String worldName = world.getName();
        int chunkX = ClaimManager.toChunkCoord(targetBlock.getX());
        int chunkZ = ClaimManager.toChunkCoord(targetBlock.getZ());
        
        HC_FactionsPlugin plugin = HC_FactionsPlugin.getInstance();
        if (plugin != null) {
            Claim claim = plugin.getClaimManager().getClaim(worldName, chunkX, chunkZ);
            
            if (claim != null) {
                // Faction claims block ALL harvesting (protected territory)
                if (claim.isFactionClaim()) {
                    boolean hasPermission = player.hasPermission(ADMIN_BYPASS_PERMISSION);
                    boolean bypassEnabled = HC_FactionsPlugin.isBypassEnabled(playerRef.getUuid());
                    
                    if (!(hasPermission && bypassEnabled)) {
                        playerRef.sendMessage(MSG_PROTECTED);
                        return; // Block harvest
                    }
                } else {
                    // Guild claim - check ownership
                    PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
                    UUID playerGuildId = playerData != null ? playerData.getGuildId() : null;
                    
                    // Same guild - allowed
                    if (playerGuildId == null || !playerGuildId.equals(claim.getGuildId())) {
                        playerRef.sendMessage(MSG_CLAIMED);
                        return; // Block harvest
                    }
                }
            }
        }
        
        // Proceed with harvest (copied from HarvestCropInteraction)
        ChunkStore chunkStore = world.getChunkStore();
        long chunkIndex = ChunkUtil.indexChunkFromBlock(targetBlock.x, targetBlock.z);
        Ref<ChunkStore> chunkRef = chunkStore.getChunkReference(chunkIndex);
        if (chunkRef == null || !chunkRef.isValid()) {
            return;
        }
        BlockChunk blockChunkComponent = chunkStore.getStore().getComponent(chunkRef, BlockChunk.getComponentType());
        if (blockChunkComponent == null) {
            return;
        }
        BlockSection section = blockChunkComponent.getSectionAtBlockY(targetBlock.y);
        if (section == null) {
            return;
        }
        WorldChunk worldChunkComponent = chunkStore.getStore().getComponent(chunkRef, WorldChunk.getComponentType());
        if (worldChunkComponent == null) {
            return;
        }
        BlockType blockType = worldChunkComponent.getBlockType(targetBlock);
        if (blockType == null) {
            return;
        }
        int rotationIndex = section.getRotationIndex(targetBlock.x, targetBlock.y, targetBlock.z);
        FarmingUtil.harvest(world, commandBuffer, ref, blockType, rotationIndex, targetBlock);
    }

    @Override
    protected void simulateInteractWithBlock(@Nonnull InteractionType type, @Nonnull InteractionContext context, 
                                             @Nullable ItemStack itemInHand, @Nonnull World world, 
                                             @Nonnull Vector3i targetBlock) {
        // No simulation needed
    }

    @Override
    @Nonnull
    public String toString() {
        return "ClaimHarvestCropInteraction{} " + super.toString();
    }
}
