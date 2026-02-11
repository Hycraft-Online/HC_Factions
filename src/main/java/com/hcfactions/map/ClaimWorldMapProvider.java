package com.hcfactions.map;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldmap.IWorldMap;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapLoadException;
import com.hypixel.hytale.server.core.universe.world.worldmap.provider.IWorldMapProvider;

/**
 * Custom world map provider that renders guild claims with colored overlays.
 * Claims are colored based on the owning guild's faction color.
 */
public class ClaimWorldMapProvider implements IWorldMapProvider {

    public static final String ID = "FactionGuilds";
    public static final BuilderCodec<ClaimWorldMapProvider> CODEC =
        BuilderCodec.builder(ClaimWorldMapProvider.class, ClaimWorldMapProvider::new).build();

    @Override
    public IWorldMap getGenerator(World world) throws WorldMapLoadException {
        return ClaimChunkWorldMap.INSTANCE;
    }
}
