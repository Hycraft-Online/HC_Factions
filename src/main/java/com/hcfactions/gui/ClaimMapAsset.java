package com.hcfactions.gui;

import com.hypixel.hytale.common.util.ArrayUtil;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.util.MathUtil;
import com.hypixel.hytale.protocol.ToClientPacket;
import com.hypixel.hytale.protocol.packets.setup.AssetFinalize;
import com.hypixel.hytale.protocol.packets.setup.AssetInitialize;
import com.hypixel.hytale.protocol.packets.setup.AssetPart;
import com.hypixel.hytale.protocol.packets.setup.RequestCommonAssetsRebuild;
import com.hypixel.hytale.protocol.packets.worldmap.MapImage;
import com.hypixel.hytale.server.core.asset.common.CommonAsset;
import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import com.hypixel.hytale.server.core.universe.world.worldmap.provider.chunk.ChunkWorldMap;
import it.unimi.dsi.fastutil.longs.LongArraySet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;

/**
 * Generates a map image asset to display behind the claim GUI.
 * Based on SimpleClaims' ChunkInfoMapAsset implementation.
 */
public class ClaimMapAsset extends CommonAsset {
    // Unique hash for this asset (must be unique across all mods)
    private static final String HASH = "00466163476c6473436c61696d4d617000000000000000000000000000000000";
    private static final String PATH = "UI/Custom/FactionGuilds/ClaimMap.png";
    private final byte[] data;

    private ClaimMapAsset(byte[] data) {
        super(PATH, HASH, data);
        this.data = data;
    }

    @Override
    protected CompletableFuture<byte[]> getBlob0() {
        return CompletableFuture.completedFuture(this.data);
    }

    // Placeholder asset for initial display
    private static ClaimMapAsset placeholderAsset = null;
    
    /**
     * Gets an empty/placeholder asset for initial display.
     * Creates a small transparent placeholder image.
     */
    public static CommonAsset empty() {
        // First try to get from registry
        CommonAsset existing = CommonAssetRegistry.getByName(PATH);
        if (existing != null) {
            return existing;
        }
        
        // Create a placeholder if needed
        if (placeholderAsset == null) {
            try {
                // Create a small 17x17 transparent image (one pixel per chunk in the 17x17 grid)
                BufferedImage placeholder = new BufferedImage(17, 17, BufferedImage.TYPE_INT_ARGB);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write((RenderedImage) placeholder, "PNG", baos);
                placeholderAsset = new ClaimMapAsset(baos.toByteArray());
            } catch (IOException e) {
                // If we can't create placeholder, use empty bytes
                placeholderAsset = new ClaimMapAsset(new byte[0]);
            }
        }
        return placeholderAsset;
    }

    /**
     * Generates the map image for the given chunk range.
     * @param player The player to generate the map for
     * @param minChunkX Minimum chunk X coordinate
     * @param minChunkZ Minimum chunk Z coordinate  
     * @param maxChunkX Maximum chunk X coordinate
     * @param maxChunkZ Maximum chunk Z coordinate
     * @return CompletableFuture containing the generated asset
     */
    public static CompletableFuture<ClaimMapAsset> generate(PlayerRef player, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        UUID worldId = player.getWorldUuid();
        if (worldId == null) {
            return null;
        }
        World world = Universe.get().getWorld(worldId);
        if (world == null) {
            return null;
        }
        
        WorldMapManager manager = world.getWorldMapManager();
        int partSize = MathUtil.fastFloor(32.0f * manager.getWorldMapSettings().getImageScale());
        
        // Collect all chunk indices for the range
        LongArraySet chunks = new LongArraySet();
        for (int x = minChunkX; x <= maxChunkX; ++x) {
            for (int z = minChunkZ; z <= maxChunkZ; ++z) {
                chunks.add(ChunkUtil.indexChunk(x, z));
            }
        }
        
        // Use the default ChunkWorldMap to generate base terrain (without claim overlay)
        return ChunkWorldMap.INSTANCE.generate(world, partSize, partSize, (LongSet) chunks).thenApply(map -> {
            BufferedImage image = new BufferedImage(
                partSize * (maxChunkX - minChunkX + 1),
                partSize * (maxChunkZ - minChunkZ + 1),
                BufferedImage.TYPE_INT_ARGB
            );
            
            for (int x = minChunkX; x <= maxChunkX; ++x) {
                for (int z = minChunkZ; z <= maxChunkZ; ++z) {
                    long index = ChunkUtil.indexChunk(x, z);
                    MapImage chunkImage = map.getChunks().get(index);
                    if (chunkImage == null) {
                        continue;
                    }
                    
                    int[] pixels = chunkImage.data;
                    int width = chunkImage.width;
                    int height = chunkImage.height;
                    
                    if (pixels == null) {
                        continue;
                    }
                    if (width != partSize || height != partSize) {
                        continue;
                    }
                    
                    int imageX = (x - minChunkX) * partSize;
                    int imageZ = (z - minChunkZ) * partSize;
                    
                    for (int i = 0; i < pixels.length; ++i) {
                        int pixel = pixels[i];
                        // Convert from RGBA to ARGB format
                        int argb = (pixel << 24) | ((pixel >> 8) & 0xFFFFFF);
                        int pixelX = i % width;
                        int pixelY = i / width;
                        image.setRGB(imageX + pixelX, imageZ + pixelY, argb);
                    }
                }
            }
            
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write((RenderedImage) image, "PNG", baos);
                return new ClaimMapAsset(baos.toByteArray());
            } catch (IOException e) {
                return null;
            }
        });
    }

    /**
     * Sends the map asset to a player's client.
     */
    public static void sendToPlayer(PacketHandler handler, CommonAsset asset) {
        byte[] allBytes = asset.getBlob().join();
        byte[][] parts = ArrayUtil.split(allBytes, 0x280000);
        ToClientPacket[] packets = new ToClientPacket[2 + parts.length];
        
        packets[0] = new AssetInitialize(asset.toPacket(), allBytes.length);
        for (int partIndex = 0; partIndex < parts.length; ++partIndex) {
            packets[1 + partIndex] = new AssetPart(parts[partIndex]);
        }
        packets[packets.length - 1] = new AssetFinalize();
        
        handler.write(packets);
        handler.writeNoCache(new RequestCommonAssetsRebuild());
    }
}
