package com.hcfactions.models;

import java.util.UUID;

/**
 * Per-member access grant for a specific guild-owned chunk.
 */
public class GuildChunkAccess {

    private final UUID guildId;
    private final UUID memberUuid;
    private final String world;
    private final int chunkX;
    private final int chunkZ;
    private final boolean canEdit;
    private final boolean canChest;
    private final UUID grantedBy;
    private final long updatedAt;

    public GuildChunkAccess(UUID guildId, UUID memberUuid, String world, int chunkX, int chunkZ,
                            boolean canEdit, boolean canChest, UUID grantedBy, long updatedAt) {
        this.guildId = guildId;
        this.memberUuid = memberUuid;
        this.world = world;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.canEdit = canEdit;
        this.canChest = canChest;
        this.grantedBy = grantedBy;
        this.updatedAt = updatedAt;
    }

    public UUID getGuildId() {
        return guildId;
    }

    public UUID getMemberUuid() {
        return memberUuid;
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

    public boolean canEdit() {
        return canEdit;
    }

    public boolean canChest() {
        return canChest;
    }

    public UUID getGrantedBy() {
        return grantedBy;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public String getKey() {
        return createKey(guildId, memberUuid, world, chunkX, chunkZ);
    }

    public static String createKey(UUID guildId, UUID memberUuid, String world, int chunkX, int chunkZ) {
        return guildId + ":" + memberUuid + ":" + world + ":" + chunkX + ":" + chunkZ;
    }
}
