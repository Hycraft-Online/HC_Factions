package com.hcfactions.map;

import com.hcfactions.HC_FactionsPlugin;
import com.hcfactions.managers.ClaimManager;
import com.hcfactions.managers.FactionManager;
import com.hcfactions.models.Claim;
import com.hcfactions.models.Faction;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.packets.worldmap.MapImage;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.environment.config.Environment;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.ChunkColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import com.hcfactions.models.Guild;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Custom image builder that overlays guild claim colors on the world map.
 * Based directly on SimpleClaims' CustomImageBuilder.
 */
public class ClaimImageBuilder {
    private final long index;
    private final World world;
    @Nonnull
    private final MapImage image;
    private final int sampleWidth;
    private final int sampleHeight;
    private final int blockStepX;
    private final int blockStepZ;
    @Nonnull
    private final short[] heightSamples;
    @Nonnull
    private final int[] tintSamples;
    @Nonnull
    private final int[] blockSamples;
    @Nonnull
    private final short[] neighborHeightSamples;
    @Nonnull
    private final short[] fluidDepthSamples;
    @Nonnull
    private final int[] environmentSamples;
    @Nonnull
    private final int[] fluidSamples;
    private final Color outColor = new Color();
    @Nullable
    private WorldChunk worldChunk;
    private FluidSection[] fluidSections;

    public ClaimImageBuilder(long index, int imageWidth, int imageHeight, World world) {
        this.index = index;
        this.world = world;
        this.image = new MapImage(imageWidth, imageHeight, new int[imageWidth * imageHeight]);
        this.sampleWidth = Math.min(32, this.image.width);
        this.sampleHeight = Math.min(32, this.image.height);
        this.blockStepX = Math.max(1, 32 / this.image.width);
        this.blockStepZ = Math.max(1, 32 / this.image.height);
        this.heightSamples = new short[this.sampleWidth * this.sampleHeight];
        this.tintSamples = new int[this.sampleWidth * this.sampleHeight];
        this.blockSamples = new int[this.sampleWidth * this.sampleHeight];
        this.neighborHeightSamples = new short[(this.sampleWidth + 2) * (this.sampleHeight + 2)];
        this.fluidDepthSamples = new short[this.sampleWidth * this.sampleHeight];
        this.environmentSamples = new int[this.sampleWidth * this.sampleHeight];
        this.fluidSamples = new int[this.sampleWidth * this.sampleHeight];
    }

    public long getIndex() {
        return this.index;
    }

    @Nonnull
    public MapImage getImage() {
        return this.image;
    }

    @Nonnull
    private CompletableFuture<ClaimImageBuilder> fetchChunk() {
        return this.world.getChunkStore().getChunkReferenceAsync(this.index).thenApplyAsync((ref) -> {
            if (ref != null && ref.isValid()) {
                this.worldChunk = (WorldChunk)ref.getStore().getComponent(ref, WorldChunk.getComponentType());
                ChunkColumn chunkColumn = (ChunkColumn)ref.getStore().getComponent(ref, ChunkColumn.getComponentType());
                this.fluidSections = new FluidSection[10];

                for(int y = 0; y < 10; ++y) {
                    Ref<ChunkStore> sectionRef = chunkColumn.getSection(y);
                    this.fluidSections[y] = (FluidSection)this.world.getChunkStore().getStore().getComponent(sectionRef, FluidSection.getComponentType());
                }

                return this;
            } else {
                return null;
            }
        }, this.world);
    }

    @Nonnull
    private CompletableFuture<ClaimImageBuilder> sampleNeighborsSync() {
        CompletableFuture<Void> north = this.world.getChunkStore().getChunkReferenceAsync(ChunkUtil.indexChunk(this.worldChunk.getX(), this.worldChunk.getZ() - 1)).thenAcceptAsync((ref) -> {
            if (ref != null && ref.isValid()) {
                WorldChunk worldChunk = (WorldChunk)ref.getStore().getComponent(ref, WorldChunk.getComponentType());
                int z = (this.sampleHeight - 1) * this.blockStepZ;

                for(int ix = 0; ix < this.sampleWidth; ++ix) {
                    int x = ix * this.blockStepX;
                    this.neighborHeightSamples[1 + ix] = worldChunk.getHeight(x, z);
                }

            }
        }, this.world);
        CompletableFuture<Void> south = this.world.getChunkStore().getChunkReferenceAsync(ChunkUtil.indexChunk(this.worldChunk.getX(), this.worldChunk.getZ() + 1)).thenAcceptAsync((ref) -> {
            if (ref != null && ref.isValid()) {
                WorldChunk worldChunk = (WorldChunk)ref.getStore().getComponent(ref, WorldChunk.getComponentType());
                int z = 0;
                int neighbourStartIndex = (this.sampleHeight + 1) * (this.sampleWidth + 2) + 1;

                for(int ix = 0; ix < this.sampleWidth; ++ix) {
                    int x = ix * this.blockStepX;
                    this.neighborHeightSamples[neighbourStartIndex + ix] = worldChunk.getHeight(x, z);
                }

            }
        }, this.world);
        CompletableFuture<Void> west = this.world.getChunkStore().getChunkReferenceAsync(ChunkUtil.indexChunk(this.worldChunk.getX() - 1, this.worldChunk.getZ())).thenAcceptAsync((ref) -> {
            if (ref != null && ref.isValid()) {
                WorldChunk worldChunk = (WorldChunk)ref.getStore().getComponent(ref, WorldChunk.getComponentType());
                int x = (this.sampleWidth - 1) * this.blockStepX;

                for(int iz = 0; iz < this.sampleHeight; ++iz) {
                    int z = iz * this.blockStepZ;
                    this.neighborHeightSamples[(iz + 1) * (this.sampleWidth + 2)] = worldChunk.getHeight(x, z);
                }

            }
        }, this.world);
        CompletableFuture<Void> east = this.world.getChunkStore().getChunkReferenceAsync(ChunkUtil.indexChunk(this.worldChunk.getX() + 1, this.worldChunk.getZ())).thenAcceptAsync((ref) -> {
            if (ref != null && ref.isValid()) {
                WorldChunk worldChunk = (WorldChunk)ref.getStore().getComponent(ref, WorldChunk.getComponentType());
                int x = 0;

                for(int iz = 0; iz < this.sampleHeight; ++iz) {
                    int z = iz * this.blockStepZ;
                    this.neighborHeightSamples[(iz + 1) * (this.sampleWidth + 2) + this.sampleWidth + 1] = worldChunk.getHeight(x, z);
                }

            }
        }, this.world);
        CompletableFuture<Void> northeast = this.world.getChunkStore().getChunkReferenceAsync(ChunkUtil.indexChunk(this.worldChunk.getX() + 1, this.worldChunk.getZ() - 1)).thenAcceptAsync((ref) -> {
            if (ref != null && ref.isValid()) {
                WorldChunk worldChunk = (WorldChunk)ref.getStore().getComponent(ref, WorldChunk.getComponentType());
                int x = 0;
                int z = (this.sampleHeight - 1) * this.blockStepZ;
                this.neighborHeightSamples[0] = worldChunk.getHeight(x, z);
            }
        }, this.world);
        CompletableFuture<Void> northwest = this.world.getChunkStore().getChunkReferenceAsync(ChunkUtil.indexChunk(this.worldChunk.getX() - 1, this.worldChunk.getZ() - 1)).thenAcceptAsync((ref) -> {
            if (ref != null && ref.isValid()) {
                WorldChunk worldChunk = (WorldChunk)ref.getStore().getComponent(ref, WorldChunk.getComponentType());
                int x = (this.sampleWidth - 1) * this.blockStepX;
                int z = (this.sampleHeight - 1) * this.blockStepZ;
                this.neighborHeightSamples[this.sampleWidth + 1] = worldChunk.getHeight(x, z);
            }
        }, this.world);
        CompletableFuture<Void> southeast = this.world.getChunkStore().getChunkReferenceAsync(ChunkUtil.indexChunk(this.worldChunk.getX() + 1, this.worldChunk.getZ() + 1)).thenAcceptAsync((ref) -> {
            if (ref != null && ref.isValid()) {
                WorldChunk worldChunk = (WorldChunk)ref.getStore().getComponent(ref, WorldChunk.getComponentType());
                int x = 0;
                int z = 0;
                this.neighborHeightSamples[(this.sampleHeight + 1) * (this.sampleWidth + 2) + this.sampleWidth + 1] = worldChunk.getHeight(x, z);
            }
        }, this.world);
        CompletableFuture<Void> southwest = this.world.getChunkStore().getChunkReferenceAsync(ChunkUtil.indexChunk(this.worldChunk.getX() - 1, this.worldChunk.getZ() + 1)).thenAcceptAsync((ref) -> {
            if (ref != null && ref.isValid()) {
                WorldChunk worldChunk = (WorldChunk)ref.getStore().getComponent(ref, WorldChunk.getComponentType());
                int x = (this.sampleWidth - 1) * this.blockStepX;
                int z = 0;
                this.neighborHeightSamples[(this.sampleHeight + 1) * (this.sampleWidth + 2)] = worldChunk.getHeight(x, z);
            }
        }, this.world);
        return CompletableFuture.allOf(north, south, west, east, northeast, northwest, southeast, southwest).thenApply((v) -> this);
    }

    private ClaimImageBuilder generateImageAsync() {
        for(int ix = 0; ix < this.sampleWidth; ++ix) {
            for(int iz = 0; iz < this.sampleHeight; ++iz) {
                int sampleIndex = iz * this.sampleWidth + ix;
                int x = ix * this.blockStepX;
                int z = iz * this.blockStepZ;
                short height = this.worldChunk.getHeight(x, z);
                int tint = this.worldChunk.getTint(x, z);
                this.heightSamples[sampleIndex] = height;
                this.tintSamples[sampleIndex] = tint;
                int blockId = this.worldChunk.getBlock(x, height, z);
                this.blockSamples[sampleIndex] = blockId;
                int fluidId = 0;
                int fluidTop = 320;
                Fluid fluid = null;
                int chunkYGround = ChunkUtil.chunkCoordinate(height);
                int chunkY = 9;

                label97:
                while(chunkY >= 0 && chunkY >= chunkYGround) {
                    FluidSection fluidSection = this.fluidSections[chunkY];
                    if (fluidSection != null && !fluidSection.isEmpty()) {
                        int minBlockY = Math.max(ChunkUtil.minBlock(chunkY), height);
                        int maxBlockY = ChunkUtil.maxBlock(chunkY);

                        for(int blockY = maxBlockY; blockY >= minBlockY; --blockY) {
                            fluidId = fluidSection.getFluidId(x, blockY, z);
                            if (fluidId != 0) {
                                fluid = (Fluid)Fluid.getAssetMap().getAsset(fluidId);
                                fluidTop = blockY;
                                break label97;
                            }
                        }

                        --chunkY;
                    } else {
                        --chunkY;
                    }
                }

                int fluidBottom;
                label119:
                for(fluidBottom = height; chunkY >= 0 && chunkY >= chunkYGround; --chunkY) {
                    FluidSection fluidSection = this.fluidSections[chunkY];
                    if (fluidSection == null || fluidSection.isEmpty()) {
                        fluidBottom = Math.min(ChunkUtil.maxBlock(chunkY) + 1, fluidTop);
                        break;
                    }

                    int minBlockY = Math.max(ChunkUtil.minBlock(chunkY), height);
                    int maxBlockY = Math.min(ChunkUtil.maxBlock(chunkY), fluidTop - 1);

                    for(int blockY = maxBlockY; blockY >= minBlockY; --blockY) {
                        int nextFluidId = fluidSection.getFluidId(x, blockY, z);
                        if (nextFluidId != fluidId) {
                            Fluid nextFluid = (Fluid)Fluid.getAssetMap().getAsset(nextFluidId);
                            if (!Objects.equals(fluid.getParticleColor(), nextFluid.getParticleColor())) {
                                fluidBottom = blockY + 1;
                                break label119;
                            }
                        }
                    }
                }

                short fluidDepth = fluidId != 0 ? (short)(fluidTop - fluidBottom + 1) : 0;
                int environmentId = this.worldChunk.getBlockChunk().getEnvironment(x, fluidTop, z);
                this.fluidDepthSamples[sampleIndex] = fluidDepth;
                this.environmentSamples[sampleIndex] = environmentId;
                this.fluidSamples[sampleIndex] = fluidId;
            }
        }

        float imageToSampleRatioWidth = (float)this.sampleWidth / (float)this.image.width;
        float imageToSampleRatioHeight = (float)this.sampleHeight / (float)this.image.height;
        int blockPixelWidth = Math.max(1, this.image.width / this.sampleWidth);
        int blockPixelHeight = Math.max(1, this.image.height / this.sampleHeight);

        for(int iz = 0; iz < this.sampleHeight; ++iz) {
            System.arraycopy(this.heightSamples, iz * this.sampleWidth, this.neighborHeightSamples, (iz + 1) * (this.sampleWidth + 2) + 1, this.sampleWidth);
        }

        int chunkX = ChunkUtil.xOfChunkIndex(this.index);
        int chunkZ = ChunkUtil.zOfChunkIndex(this.index);
        int minBlockX = ChunkUtil.minBlock(chunkX);
        int minBlockZ = ChunkUtil.minBlock(chunkZ);

        // CUSTOM CODE - Get claim info for this chunk
        Claim claimedChunk = getClaimForChunk(this.worldChunk.getWorld().getName(), this.worldChunk.getX(), this.worldChunk.getZ());
        Integer claimColor = null;
        String claimOwnerId = null; // Use faction ID for faction claims, guild ID string for guild claims
        boolean isFactionClaim = false;
        if (claimedChunk != null) {
            claimColor = getColorForClaim(claimedChunk);
            isFactionClaim = claimedChunk.isFactionClaim();
            // Create a unique owner identifier
            if (isFactionClaim) {
                claimOwnerId = "faction:" + claimedChunk.getFactionId();
            } else if (claimedChunk.isSoloPlayerClaim()) {
                claimOwnerId = "player:" + claimedChunk.getPlayerOwnerId();
            } else {
                claimOwnerId = "guild:" + claimedChunk.getGuildId();
            }
        }
        var nearbyChunks = new Claim[]{
                getClaimForChunk(this.worldChunk.getWorld().getName(), this.worldChunk.getX(), this.worldChunk.getZ() + 1), //NORTH (index 0)
                getClaimForChunk(this.worldChunk.getWorld().getName(), this.worldChunk.getX(), this.worldChunk.getZ() - 1), //SOUTH (index 1)
                getClaimForChunk(this.worldChunk.getWorld().getName(), this.worldChunk.getX() + 1, this.worldChunk.getZ()), //EAST (index 2)
                getClaimForChunk(this.worldChunk.getWorld().getName(), this.worldChunk.getX() - 1, this.worldChunk.getZ()), //WEST (index 3)
        };
        //-

        for(int ix = 0; ix < this.image.width; ++ix) {
            for(int iz = 0; iz < this.image.height; ++iz) {
                int sampleX = Math.min((int)((float)ix * imageToSampleRatioWidth), this.sampleWidth - 1);
                int sampleZ = Math.min((int)((float)iz * imageToSampleRatioHeight), this.sampleHeight - 1);
                int sampleIndex = sampleZ * this.sampleWidth + sampleX;
                int blockPixelX = ix % blockPixelWidth;
                int blockPixelZ = iz % blockPixelHeight;
                short height = this.heightSamples[sampleIndex];
                int tint = this.tintSamples[sampleIndex];
                int blockId = this.blockSamples[sampleIndex];
                getBlockColor(blockId, tint, this.outColor);

                short north = this.neighborHeightSamples[sampleZ * (this.sampleWidth + 2) + sampleX + 1];
                short south = this.neighborHeightSamples[(sampleZ + 2) * (this.sampleWidth + 2) + sampleX + 1];
                short west = this.neighborHeightSamples[(sampleZ + 1) * (this.sampleWidth + 2) + sampleX];
                short east = this.neighborHeightSamples[(sampleZ + 1) * (this.sampleWidth + 2) + sampleX + 2];
                short northWest = this.neighborHeightSamples[sampleZ * (this.sampleWidth + 2) + sampleX];
                short northEast = this.neighborHeightSamples[sampleZ * (this.sampleWidth + 2) + sampleX + 2];
                short southWest = this.neighborHeightSamples[(sampleZ + 2) * (this.sampleWidth + 2) + sampleX];
                short southEast = this.neighborHeightSamples[(sampleZ + 2) * (this.sampleWidth + 2) + sampleX + 2];
                float shade = shadeFromHeights(blockPixelX, blockPixelZ, blockPixelWidth, blockPixelHeight, height, north, south, west, east, northWest, northEast, southWest, southEast);
                this.outColor.multiply(shade);
                if (height < 320) {
                    int fluidId = this.fluidSamples[sampleIndex];
                    if (fluidId != 0) {
                        short fluidDepth = this.fluidDepthSamples[sampleIndex];
                        int environmentId = this.environmentSamples[sampleIndex];
                        getFluidColor(fluidId, environmentId, fluidDepth, this.outColor);
                    }
                }

                //CUSTOM CODE - Apply claim color AFTER terrain and fluid rendering (so it shows over water)
                if (claimColor != null && claimOwnerId != null) {
                    var isBorder = false;
                    // Faction claims have thicker borders (4px) for more ornate appearance
                    var borderSize = isFactionClaim ? 4 : 2;
                    // Check if neighbors have different owners (for border rendering)
                    if ((ix <= borderSize && !isSameOwner(nearbyChunks[3], claimOwnerId)) //WEST
                            || (ix >= this.image.width - borderSize - 1 && !isSameOwner(nearbyChunks[2], claimOwnerId)) //EAST
                            || (iz <= borderSize && !isSameOwner(nearbyChunks[1], claimOwnerId)) // SOUTH
                            || (iz >= this.image.height - borderSize - 1 && !isSameOwner(nearbyChunks[0], claimOwnerId))) { //NORTH
                        isBorder = true;
                    }
                    // Use different overlay methods for faction vs guild claims
                    if (isFactionClaim) {
                        applyFactionClaimOverlay(claimColor, this.outColor, isBorder, ix, iz);
                    } else {
                        applyGuildClaimOverlay(claimColor, this.outColor, isBorder);
                    }
                }
                //-

                this.populateImageData(iz * this.image.width + ix, sampleX, sampleZ, minBlockX, minBlockZ);
            }
        }

        // Draw claim names - check for any nearby claims whose text might overlap this chunk
        drawNearbyClaimNames(this.worldChunk.getX(), this.worldChunk.getZ());

        return this;
    }

    /**
     * Draws claim names for any nearby claims whose text might overlap this chunk.
     * This includes both guild names and faction names (for faction-level claims).
     * Text can extend beyond the claimed territory.
     */
    private void drawNearbyClaimNames(int thisChunkX, int thisChunkZ) {
        HC_FactionsPlugin plugin = HC_FactionsPlugin.getInstance();
        if (plugin == null || plugin.getClaimManager() == null) return;

        String worldName = this.worldChunk.getWorld().getName();

        // Check a radius around this chunk for claims
        // Text can extend ~3-4 chunks depending on name length, so check wider area
        // Faction claims may be larger, so check even wider for those
        int searchRadius = 7;
        java.util.Set<UUID> processedGuilds = new java.util.HashSet<>();
        java.util.Set<String> processedFactions = new java.util.HashSet<>();
        java.util.Set<UUID> processedPlayers = new java.util.HashSet<>();

        for (int dx = -searchRadius; dx <= searchRadius; dx++) {
            for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                Claim nearby = getClaimForChunk(worldName, thisChunkX + dx, thisChunkZ + dz);
                if (nearby == null) continue;

                if (nearby.isFactionClaim()) {
                    // Faction-level claim - draw faction name
                    String factionId = nearby.getFactionId();
                    if (factionId != null && !processedFactions.contains(factionId)) {
                        processedFactions.add(factionId);
                        drawFactionNameCentered(factionId, thisChunkX, thisChunkZ);
                    }
                } else if (nearby.isSoloPlayerClaim()) {
                    // Solo player claim - draw player name
                    UUID playerOwnerId = nearby.getPlayerOwnerId();
                    if (playerOwnerId != null && !processedPlayers.contains(playerOwnerId)) {
                        processedPlayers.add(playerOwnerId);
                        drawPlayerNameCentered(playerOwnerId, thisChunkX, thisChunkZ);
                    }
                } else if (nearby.getGuildId() != null) {
                    // Guild claim - draw guild name
                    UUID guildId = nearby.getGuildId();
                    if (!processedGuilds.contains(guildId)) {
                        processedGuilds.add(guildId);
                        drawGuildNameCentered(guildId, thisChunkX, thisChunkZ);
                    }
                }
            }
        }
    }

    /**
     * Draws the guild name truly centered on the guild's territory.
     * Each chunk draws its portion of the text, offset so when combined they form a centered label.
     */
    private void drawGuildNameCentered(UUID guildId, int thisChunkX, int thisChunkZ) {
        if (guildId == null) return;

        HC_FactionsPlugin plugin = HC_FactionsPlugin.getInstance();
        if (plugin == null || plugin.getGuildManager() == null || plugin.getClaimManager() == null) return;

        Guild guild = plugin.getGuildManager().getGuild(guildId);
        if (guild == null) return;

        String name = guild.getName();
        if (name == null || name.isEmpty()) return;

        // Get all claims for this guild to find the centroid
        java.util.List<Claim> guildClaims = plugin.getClaimManager().getGuildClaims(guildId);
        if (guildClaims.isEmpty()) return;

        // Filter to same world
        String worldName = this.worldChunk.getWorld().getName();
        java.util.List<Claim> worldClaims = guildClaims.stream()
            .filter(c -> worldName.equals(c.getWorld()))
            .toList();
        if (worldClaims.isEmpty()) return;

        // Calculate centroid of guild's territory (center of all chunks)
        // Use chunk center (+0.5) to get true center
        double sumX = 0, sumZ = 0;
        for (Claim c : worldClaims) {
            sumX += c.getChunkX() + 0.5;
            sumZ += c.getChunkZ() + 0.5;
        }
        double centroidChunkX = sumX / worldClaims.size();
        double centroidChunkZ = sumZ / worldClaims.size();

        // Calculate text dimensions
        int fontSize = Math.max(8, this.image.height / 4);
        Font font = new Font("SansSerif", Font.BOLD, fontSize);

        // Get text width using a temporary graphics context
        BufferedImage tempImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D tempG2d = tempImage.createGraphics();
        tempG2d.setFont(font);
        java.awt.FontMetrics fm = tempG2d.getFontMetrics();
        int textWidth = fm.stringWidth(name);
        int textHeight = fm.getHeight();
        int textAscent = fm.getAscent();
        tempG2d.dispose();

        // Calculate the pixel position where text should start (in world-pixel coordinates)
        // The centroid is at (centroidChunkX, centroidChunkZ) in chunk coords
        // Each chunk is image.width x image.height pixels
        double centroidPixelX = centroidChunkX * this.image.width;
        double centroidPixelZ = centroidChunkZ * this.image.height;

        // Text should be centered at the centroid
        double textStartPixelX = centroidPixelX - textWidth / 2.0;
        double textStartPixelZ = centroidPixelZ - textAscent / 2.0;

        // This chunk's pixel range
        int chunkStartPixelX = thisChunkX * this.image.width;
        int chunkStartPixelZ = thisChunkZ * this.image.height;
        int chunkEndPixelX = chunkStartPixelX + this.image.width;
        int chunkEndPixelZ = chunkStartPixelZ + this.image.height;

        // Check if any part of the text falls within this chunk
        double textEndPixelX = textStartPixelX + textWidth;
        double textEndPixelZ = textStartPixelZ + textHeight;

        if (textEndPixelX < chunkStartPixelX || textStartPixelX > chunkEndPixelX ||
            textEndPixelZ < chunkStartPixelZ || textStartPixelZ > chunkEndPixelZ) {
            // Text doesn't intersect this chunk
            return;
        }

        // Calculate where to draw text within this chunk's local coordinates
        int localX = (int)(textStartPixelX - chunkStartPixelX);
        int localY = (int)(textStartPixelZ - chunkStartPixelZ) + textAscent;

        // Convert MapImage data to BufferedImage for text drawing
        BufferedImage bufferedImage = new BufferedImage(this.image.width, this.image.height, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < this.image.data.length; i++) {
            int pixel = this.image.data[i];
            // Convert from RGBA to ARGB
            int argb = (pixel << 24) | ((pixel >> 8) & 0xFFFFFF);
            bufferedImage.setRGB(i % this.image.width, i / this.image.width, argb);
        }

        // Draw text
        Graphics2D g2d = bufferedImage.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2d.setFont(font);

        // Draw shadow for visibility
        g2d.setColor(new java.awt.Color(0, 0, 0, 200));
        g2d.drawString(name, localX + 1, localY + 1);

        // Draw white text
        g2d.setColor(java.awt.Color.WHITE);
        g2d.drawString(name, localX, localY);

        g2d.dispose();

        // Convert back to MapImage data
        for (int i = 0; i < this.image.data.length; i++) {
            int px = i % this.image.width;
            int py = i / this.image.width;
            int argb = bufferedImage.getRGB(px, py);
            // Convert from ARGB to RGBA
            int rgba = ((argb >> 24) & 0xFF) | ((argb & 0xFFFFFF) << 8);
            this.image.data[i] = rgba;
        }
    }

    /**
     * Draws the player name centered on their personal claim territory.
     * Same style as guild names.
     */
    private void drawPlayerNameCentered(UUID playerOwnerId, int thisChunkX, int thisChunkZ) {
        if (playerOwnerId == null) return;

        HC_FactionsPlugin plugin = HC_FactionsPlugin.getInstance();
        if (plugin == null || plugin.getClaimManager() == null || plugin.getPlayerDataRepository() == null) return;

        com.hcfactions.models.PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerOwnerId);
        String name = playerData != null ? playerData.getPlayerName() : null;
        if (name == null || name.isEmpty()) return;

        // Get all claims for this player to find the centroid
        java.util.List<Claim> playerClaims = plugin.getClaimManager().getPlayerClaims(playerOwnerId);
        if (playerClaims.isEmpty()) return;

        // Filter to same world
        String worldName = this.worldChunk.getWorld().getName();
        java.util.List<Claim> worldClaims = playerClaims.stream()
            .filter(c -> worldName.equals(c.getWorld()))
            .toList();
        if (worldClaims.isEmpty()) return;

        // Calculate centroid of player's territory
        double sumX = 0, sumZ = 0;
        for (Claim c : worldClaims) {
            sumX += c.getChunkX() + 0.5;
            sumZ += c.getChunkZ() + 0.5;
        }
        double centroidChunkX = sumX / worldClaims.size();
        double centroidChunkZ = sumZ / worldClaims.size();

        // Same font size as guild names
        int fontSize = Math.max(8, this.image.height / 4);
        Font font = new Font("SansSerif", Font.BOLD, fontSize);

        BufferedImage tempImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D tempG2d = tempImage.createGraphics();
        tempG2d.setFont(font);
        java.awt.FontMetrics fm = tempG2d.getFontMetrics();
        int textWidth = fm.stringWidth(name);
        int textHeight = fm.getHeight();
        int textAscent = fm.getAscent();
        tempG2d.dispose();

        double centroidPixelX = centroidChunkX * this.image.width;
        double centroidPixelZ = centroidChunkZ * this.image.height;
        double textStartPixelX = centroidPixelX - textWidth / 2.0;
        double textStartPixelZ = centroidPixelZ - textAscent / 2.0;

        int chunkStartPixelX = thisChunkX * this.image.width;
        int chunkStartPixelZ = thisChunkZ * this.image.height;
        int chunkEndPixelX = chunkStartPixelX + this.image.width;
        int chunkEndPixelZ = chunkStartPixelZ + this.image.height;

        double textEndPixelX = textStartPixelX + textWidth;
        double textEndPixelZ = textStartPixelZ + textHeight;

        if (textEndPixelX < chunkStartPixelX || textStartPixelX > chunkEndPixelX ||
            textEndPixelZ < chunkStartPixelZ || textStartPixelZ > chunkEndPixelZ) {
            return;
        }

        int localX = (int)(textStartPixelX - chunkStartPixelX);
        int localY = (int)(textStartPixelZ - chunkStartPixelZ) + textAscent;

        BufferedImage bufferedImage = new BufferedImage(this.image.width, this.image.height, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < this.image.data.length; i++) {
            int pixel = this.image.data[i];
            int argb = (pixel << 24) | ((pixel >> 8) & 0xFFFFFF);
            bufferedImage.setRGB(i % this.image.width, i / this.image.width, argb);
        }

        Graphics2D g2d = bufferedImage.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2d.setFont(font);

        g2d.setColor(new java.awt.Color(0, 0, 0, 200));
        g2d.drawString(name, localX + 1, localY + 1);

        g2d.setColor(java.awt.Color.WHITE);
        g2d.drawString(name, localX, localY);

        g2d.dispose();

        for (int i = 0; i < this.image.data.length; i++) {
            int px = i % this.image.width;
            int py = i / this.image.width;
            int argb = bufferedImage.getRGB(px, py);
            int rgba = ((argb >> 24) & 0xFF) | ((argb & 0xFFFFFF) << 8);
            this.image.data[i] = rgba;
        }
    }

    /**
     * Draws the faction name centered on the faction's territory (faction claims only).
     * Similar to guild names but with larger font for prominence on larger territories.
     * Uses stronger outline for visibility over the striped pattern.
     */
    private void drawFactionNameCentered(String factionId, int thisChunkX, int thisChunkZ) {
        if (factionId == null) return;

        HC_FactionsPlugin plugin = HC_FactionsPlugin.getInstance();
        if (plugin == null || plugin.getFactionManager() == null || plugin.getClaimManager() == null) return;

        Faction faction = plugin.getFactionManager().getFaction(factionId);
        if (faction == null) return;

        String name = faction.getDisplayName();
        if (name == null || name.isEmpty()) return;

        // Get all faction-level claims (not guild claims) for this faction
        java.util.List<Claim> factionClaims = plugin.getClaimManager().getFactionOnlyClaims(factionId);
        if (factionClaims.isEmpty()) return;

        // Filter to same world
        String worldName = this.worldChunk.getWorld().getName();
        java.util.List<Claim> worldClaims = factionClaims.stream()
            .filter(c -> worldName.equals(c.getWorld()))
            .toList();
        if (worldClaims.isEmpty()) return;

        // Calculate centroid of faction's territory (center of all chunks)
        double sumX = 0, sumZ = 0;
        for (Claim c : worldClaims) {
            sumX += c.getChunkX() + 0.5;
            sumZ += c.getChunkZ() + 0.5;
        }
        double centroidChunkX = sumX / worldClaims.size();
        double centroidChunkZ = sumZ / worldClaims.size();

        // Larger font size for faction names (faction territories are bigger)
        int fontSize = Math.max(12, this.image.height / 3);
        Font font = new Font("SansSerif", Font.BOLD, fontSize);

        // Get text width using a temporary graphics context
        BufferedImage tempImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D tempG2d = tempImage.createGraphics();
        tempG2d.setFont(font);
        java.awt.FontMetrics fm = tempG2d.getFontMetrics();
        int textWidth = fm.stringWidth(name);
        int textHeight = fm.getHeight();
        int textAscent = fm.getAscent();
        tempG2d.dispose();

        // Calculate the pixel position where text should start (in world-pixel coordinates)
        double centroidPixelX = centroidChunkX * this.image.width;
        double centroidPixelZ = centroidChunkZ * this.image.height;

        // Text should be centered at the centroid
        double textStartPixelX = centroidPixelX - textWidth / 2.0;
        double textStartPixelZ = centroidPixelZ - textAscent / 2.0;

        // This chunk's pixel range
        int chunkStartPixelX = thisChunkX * this.image.width;
        int chunkStartPixelZ = thisChunkZ * this.image.height;
        int chunkEndPixelX = chunkStartPixelX + this.image.width;
        int chunkEndPixelZ = chunkStartPixelZ + this.image.height;

        // Check if any part of the text falls within this chunk
        double textEndPixelX = textStartPixelX + textWidth;
        double textEndPixelZ = textStartPixelZ + textHeight;

        if (textEndPixelX < chunkStartPixelX || textStartPixelX > chunkEndPixelX ||
            textEndPixelZ < chunkStartPixelZ || textStartPixelZ > chunkEndPixelZ) {
            // Text doesn't intersect this chunk
            return;
        }

        // Calculate where to draw text within this chunk's local coordinates
        int localX = (int)(textStartPixelX - chunkStartPixelX);
        int localY = (int)(textStartPixelZ - chunkStartPixelZ) + textAscent;

        // Convert MapImage data to BufferedImage for text drawing
        BufferedImage bufferedImage = new BufferedImage(this.image.width, this.image.height, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < this.image.data.length; i++) {
            int pixel = this.image.data[i];
            // Convert from RGBA to ARGB
            int argb = (pixel << 24) | ((pixel >> 8) & 0xFFFFFF);
            bufferedImage.setRGB(i % this.image.width, i / this.image.width, argb);
        }

        // Draw text with stronger outline for visibility over striped pattern
        Graphics2D g2d = bufferedImage.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2d.setFont(font);

        // Draw thicker shadow/outline (multiple offsets for stronger visibility)
        g2d.setColor(new java.awt.Color(0, 0, 0, 220));
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx != 0 || dz != 0) {
                    g2d.drawString(name, localX + dx, localY + dz);
                }
            }
        }

        // Draw white text on top
        g2d.setColor(java.awt.Color.WHITE);
        g2d.drawString(name, localX, localY);

        g2d.dispose();

        // Convert back to MapImage data
        for (int i = 0; i < this.image.data.length; i++) {
            int px = i % this.image.width;
            int py = i / this.image.width;
            int argb = bufferedImage.getRGB(px, py);
            // Convert from ARGB to RGBA
            int rgba = ((argb >> 24) & 0xFF) | ((argb & 0xFFFFFF) << 8);
            this.image.data[i] = rgba;
        }
    }

    // Helper to get claim for a chunk
    @Nullable
    private static Claim getClaimForChunk(String worldName, int chunkX, int chunkZ) {
        HC_FactionsPlugin plugin = HC_FactionsPlugin.getInstance();
        if (plugin == null || plugin.getClaimManager() == null) {
            return null;
        }
        return plugin.getClaimManager().getClaim(worldName, chunkX, chunkZ);
    }

    // Helper to check if a neighbor claim has the same owner
    private static boolean isSameOwner(@Nullable Claim neighbor, String currentOwnerId) {
        if (neighbor == null) {
            return false;
        }
        String neighborOwnerId;
        if (neighbor.isFactionClaim()) {
            neighborOwnerId = "faction:" + neighbor.getFactionId();
        } else if (neighbor.isSoloPlayerClaim()) {
            neighborOwnerId = "player:" + neighbor.getPlayerOwnerId();
        } else {
            neighborOwnerId = "guild:" + neighbor.getGuildId();
        }
        return currentOwnerId.equals(neighborOwnerId);
    }

    // Helper to get color for a claim
    private static int getColorForClaim(Claim claim) {
        if (claim == null) {
            return 0xFF0000; // Default to red for debugging
        }
        HC_FactionsPlugin plugin = HC_FactionsPlugin.getInstance();
        if (plugin == null || plugin.getFactionManager() == null) {
            return 0xFF0000; // Default to red for debugging
        }
        Faction faction = plugin.getFactionManager().getFaction(claim.getFactionId());
        if (faction != null) {
            java.awt.Color color = faction.getColor();
            return (color.getRed() << 16) | (color.getGreen() << 8) | color.getBlue();
        }
        return 0xFF0000; // Default to red for debugging
    }

    private static float shadeFromHeights(int blockPixelX, int blockPixelZ, int blockPixelWidth, int blockPixelHeight, short height, short north, short south, short west, short east, short northWest, short northEast, short southWest, short southEast) {
        float u = ((float)blockPixelX + 0.5F) / (float)blockPixelWidth;
        float v = ((float)blockPixelZ + 0.5F) / (float)blockPixelHeight;
        float ud = (u + v) / 2.0F;
        float vd = (1.0F - u + v) / 2.0F;
        float dhdx1 = (float)(height - west) * (1.0F - u) + (float)(east - height) * u;
        float dhdz1 = (float)(height - north) * (1.0F - v) + (float)(south - height) * v;
        float dhdx2 = (float)(height - northWest) * (1.0F - ud) + (float)(southEast - height) * ud;
        float dhdz2 = (float)(height - northEast) * (1.0F - vd) + (float)(southWest - height) * vd;
        float dhdx = dhdx1 * 2.0F + dhdx2;
        float dhdz = dhdz1 * 2.0F + dhdz2;
        float dy = 3.0F;
        float invS = 1.0F / (float)Math.sqrt((double)(dhdx * dhdx + dy * dy + dhdz * dhdz));
        float nx = dhdx * invS;
        float ny = dy * invS;
        float nz = dhdz * invS;
        float lx = -0.2F;
        float ly = 0.8F;
        float lz = 0.5F;
        float invL = 1.0F / (float)Math.sqrt((double)(lx * lx + ly * ly + lz * lz));
        lx *= invL;
        ly *= invL;
        lz *= invL;
        float lambert = Math.max(0.0F, nx * lx + ny * ly + nz * lz);
        float ambient = 0.4F;
        float diffuse = 0.6F;
        return ambient + diffuse * lambert;
    }

    private static void getBlockColor(int blockId, int biomeTintColor, @Nonnull Color outColor) {
        BlockType block = (BlockType)BlockType.getAssetMap().getAsset(blockId);
        int biomeTintR = biomeTintColor >> 16 & 255;
        int biomeTintG = biomeTintColor >> 8 & 255;
        int biomeTintB = biomeTintColor >> 0 & 255;
        com.hypixel.hytale.protocol.Color[] tintUp = block.getTintUp();
        boolean hasTint = tintUp != null && tintUp.length > 0;
        int selfTintR = hasTint ? tintUp[0].red & 255 : 255;
        int selfTintG = hasTint ? tintUp[0].green & 255 : 255;
        int selfTintB = hasTint ? tintUp[0].blue & 255 : 255;
        float biomeTintMultiplier = (float)block.getBiomeTintUp() / 100.0F;
        int tintColorR = (int)((float)selfTintR + (float)(biomeTintR - selfTintR) * biomeTintMultiplier);
        int tintColorG = (int)((float)selfTintG + (float)(biomeTintG - selfTintG) * biomeTintMultiplier);
        int tintColorB = (int)((float)selfTintB + (float)(biomeTintB - selfTintB) * biomeTintMultiplier);
        com.hypixel.hytale.protocol.Color particleColor = block.getParticleColor();
        if (particleColor != null && biomeTintMultiplier < 1.0F) {
            tintColorR = tintColorR * (particleColor.red & 255) / 255;
            tintColorG = tintColorG * (particleColor.green & 255) / 255;
            tintColorB = tintColorB * (particleColor.blue & 255) / 255;
        }

        outColor.r = tintColorR & 255;
        outColor.g = tintColorG & 255;
        outColor.b = tintColorB & 255;
        outColor.a = 255;
    }

    /**
     * Applies claim color overlay for GUILD claims by blending with existing terrain/water color.
     * This preserves some of the underlying map detail while tinting with faction color.
     */
    private static void applyGuildClaimOverlay(int claimColor, @Nonnull Color outColor, boolean isBorder) {
        int claimR = claimColor >> 16 & 255;
        int claimG = claimColor >> 8 & 255;
        int claimB = claimColor >> 0 & 255;

        // Blend factor: how much of the claim color vs original terrain
        // Border areas are more saturated (more claim color)
        float blendFactor = isBorder ? 0.7f : 0.5f;

        // Blend claim color with existing terrain color
        outColor.r = (int)(outColor.r * (1 - blendFactor) + claimR * blendFactor);
        outColor.g = (int)(outColor.g * (1 - blendFactor) + claimG * blendFactor);
        outColor.b = (int)(outColor.b * (1 - blendFactor) + claimB * blendFactor);

        // Darken border slightly for visibility
        if (isBorder) {
            outColor.multiply(0.85f);
        }
    }

    /**
     * Applies claim color overlay for FACTION claims with ornate diagonal stripe pattern.
     * Faction claims are visually distinct with:
     * - Diagonal stripe pattern (alternating opacity)
     * - Thicker, darker borders
     * - Higher overall opacity for prominence
     */
    private static void applyFactionClaimOverlay(int claimColor, @Nonnull Color outColor, boolean isBorder, int ix, int iz) {
        int claimR = claimColor >> 16 & 255;
        int claimG = claimColor >> 8 & 255;
        int claimB = claimColor >> 0 & 255;

        // Diagonal stripe pattern: alternating bands of opacity
        // Using (ix + iz) creates 45-degree diagonal stripes
        int stripeWidth = 4;
        boolean isStripe = ((ix + iz) % (stripeWidth * 2)) < stripeWidth;

        // Faction claims: higher base opacity with stripe variation
        // Stripes alternate between 55% and 40% opacity for visual texture
        float baseBlend = isBorder ? 0.75f : (isStripe ? 0.55f : 0.40f);

        // Blend claim color with existing terrain color
        outColor.r = (int)(outColor.r * (1 - baseBlend) + claimR * baseBlend);
        outColor.g = (int)(outColor.g * (1 - baseBlend) + claimG * baseBlend);
        outColor.b = (int)(outColor.b * (1 - baseBlend) + claimB * baseBlend);

        // Faction borders are darker and more prominent
        if (isBorder) {
            outColor.multiply(0.80f);
        }
    }

    private static void getFluidColor(int fluidId, int environmentId, int fluidDepth, @Nonnull Color outColor) {
        int tintColorR = 255;
        int tintColorG = 255;
        int tintColorB = 255;
        Environment environment = (Environment)Environment.getAssetMap().getAsset(environmentId);
        com.hypixel.hytale.protocol.Color waterTint = environment.getWaterTint();
        if (waterTint != null) {
            tintColorR = tintColorR * (waterTint.red & 255) / 255;
            tintColorG = tintColorG * (waterTint.green & 255) / 255;
            tintColorB = tintColorB * (waterTint.blue & 255) / 255;
        }

        Fluid fluid = (Fluid)Fluid.getAssetMap().getAsset(fluidId);
        com.hypixel.hytale.protocol.Color partcileColor = fluid.getParticleColor();
        if (partcileColor != null) {
            tintColorR = tintColorR * (partcileColor.red & 255) / 255;
            tintColorG = tintColorG * (partcileColor.green & 255) / 255;
            tintColorB = tintColorB * (partcileColor.blue & 255) / 255;
        }

        float depthMultiplier = Math.min(1.0F, 1.0F / (float)fluidDepth);
        outColor.r = (int)((float)tintColorR + (float)((outColor.r & 255) - tintColorR) * depthMultiplier) & 255;
        outColor.g = (int)((float)tintColorG + (float)((outColor.g & 255) - tintColorG) * depthMultiplier) & 255;
        outColor.b = (int)((float)tintColorB + (float)((outColor.b & 255) - tintColorB) * depthMultiplier) & 255;
    }

    private void populateImageData(int pixelIndex, int sampleX, int sampleZ, int minBlockX, int minBlockZ) {
        this.image.data[pixelIndex] = this.outColor.pack();
    }

    @Nonnull
    public static CompletableFuture<ClaimImageBuilder> build(long index, int imageWidth, int imageHeight, World world) {
        return CompletableFuture.completedFuture(new ClaimImageBuilder(index, imageWidth, imageHeight, world)).thenCompose(ClaimImageBuilder::fetchChunk).thenCompose((builder) -> builder != null ? builder.sampleNeighborsSync() : CompletableFuture.completedFuture(null)).thenApplyAsync((builder) -> builder != null ? builder.generateImageAsync() : null);
    }

    private static class Color {
        public int r;
        public int g;
        public int b;
        public int a;

        public int pack() {
            return (this.r & 255) << 24 | (this.g & 255) << 16 | (this.b & 255) << 8 | this.a & 255;
        }

        public void multiply(float value) {
            this.r = Math.min(255, Math.max(0, (int)((float)this.r * value)));
            this.g = Math.min(255, Math.max(0, (int)((float)this.g * value)));
            this.b = Math.min(255, Math.max(0, (int)((float)this.b * value)));
        }
    }
}
