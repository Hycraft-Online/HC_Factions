package com.hcfactions.map;

import com.hypixel.hytale.protocol.packets.worldmap.MapMarker;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.map.WorldMap;
import com.hypixel.hytale.server.core.universe.world.worldmap.IWorldMap;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapSettings;
import com.hypixel.hytale.server.core.universe.world.worldmap.provider.chunk.ChunkWorldMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Custom world map that renders guild claims with colored overlays.
 * Based on SimpleClaims implementation.
 */
public class ClaimChunkWorldMap implements IWorldMap {

    public static final ClaimChunkWorldMap INSTANCE = new ClaimChunkWorldMap();

    @Override
    public WorldMapSettings getWorldMapSettings() {
        return ChunkWorldMap.INSTANCE.getWorldMapSettings();
    }

    @Override
    public CompletableFuture<WorldMap> generate(World world, int imageWidth, int imageHeight, LongSet chunksToGenerate) {
        @SuppressWarnings("unchecked")
        CompletableFuture<ClaimImageBuilder>[] futures = new CompletableFuture[chunksToGenerate.size()];
        int futureIndex = 0;

        for (LongIterator iterator = chunksToGenerate.iterator(); iterator.hasNext(); ) {
            futures[futureIndex++] = ClaimImageBuilder.build(iterator.nextLong(), imageWidth, imageHeight, world);
        }

        return CompletableFuture.allOf(futures).thenApply((unused) -> {
            WorldMap worldMap = new WorldMap(futures.length);

            for (int i = 0; i < futures.length; ++i) {
                ClaimImageBuilder builder = futures[i].getNow(null);
                if (builder != null) {
                    worldMap.getChunks().put(builder.getIndex(), builder.getImage());
                }
            }

            return worldMap;
        });
    }

    @Override
    public CompletableFuture<Map<String, MapMarker>> generatePointsOfInterest(World world) {
        // Delegate to default implementation for native POIs
        return ChunkWorldMap.INSTANCE.generatePointsOfInterest(world);
    }
}
