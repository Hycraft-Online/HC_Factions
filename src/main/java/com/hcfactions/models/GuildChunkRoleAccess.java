package com.hcfactions.models;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Per-role access requirements for a specific guild-owned chunk.
 */
public class GuildChunkRoleAccess {

    private final UUID guildId;
    private final String world;
    private final int chunkX;
    private final int chunkZ;
    @Nullable
    private final GuildRole minEditRole;
    @Nullable
    private final GuildRole minChestRole;
    @Nullable
    private final UUID updatedBy;
    private final long updatedAt;

    public GuildChunkRoleAccess(UUID guildId, String world, int chunkX, int chunkZ,
                                @Nullable GuildRole minEditRole, @Nullable GuildRole minChestRole,
                                @Nullable UUID updatedBy, long updatedAt) {
        this.guildId = guildId;
        this.world = world;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minEditRole = minEditRole;
        this.minChestRole = minChestRole;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    public UUID getGuildId() {
        return guildId;
    }

    public String getWorld() {
        return world;
    }

    public int getChunkX() {
        return chunkX;
    }

    public int getChunkZ() {
        return chunkZ;
    }

    @Nullable
    public GuildRole getMinEditRole() {
        return minEditRole;
    }

    @Nullable
    public GuildRole getMinChestRole() {
        return minChestRole;
    }

    @Nullable
    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public String getKey() {
        return createKey(guildId, world, chunkX, chunkZ);
    }

    public static String createKey(UUID guildId, String world, int chunkX, int chunkZ) {
        return guildId + ":" + world + ":" + chunkX + ":" + chunkZ;
    }
}
