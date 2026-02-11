package com.hcfactions.models;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Represents a claimed chunk.
 *
 * Claims can be:
 * - Guild claims: owned by a specific guild (guildId is set)
 * - Faction claims: owned by the faction itself (guildId is null, playerOwnerId is null)
 * - Solo player claims: owned by a player not in a guild (playerOwnerId is set, guildId is null)
 *
 * Faction claims are used for protected areas like capitals and are
 * managed by admins. They block all building and breaking for non-admins.
 */
public class Claim {

    private final int chunkX;
    private final int chunkZ;
    private final String world;
    @Nullable
    private final UUID guildId;  // null for faction-level and solo player claims
    private final String factionId;
    @Nullable
    private UUID playerOwnerId;  // UUID of solo player owner (null for guild/faction claims)
    @Nullable
    private UUID suppressorUuid;  // UUID of spawn suppressor entity
    private final long claimedAt;

    /**
     * Creates a guild claim.
     */
    public Claim(int chunkX, int chunkZ, String world, UUID guildId, String factionId) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.world = world;
        this.guildId = guildId;
        this.factionId = factionId;
        this.suppressorUuid = null;
        this.claimedAt = System.currentTimeMillis();
    }

    /**
     * Creates a claim with a specific timestamp.
     */
    public Claim(int chunkX, int chunkZ, String world, @Nullable UUID guildId, String factionId, long claimedAt) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.world = world;
        this.guildId = guildId;
        this.factionId = factionId;
        this.suppressorUuid = null;
        this.claimedAt = claimedAt;
    }

    /**
     * Creates a claim with a specific timestamp and suppressor UUID.
     */
    public Claim(int chunkX, int chunkZ, String world, @Nullable UUID guildId, String factionId,
                 @Nullable UUID suppressorUuid, long claimedAt) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.world = world;
        this.guildId = guildId;
        this.factionId = factionId;
        this.playerOwnerId = null;
        this.suppressorUuid = suppressorUuid;
        this.claimedAt = claimedAt;
    }

    /**
     * Creates a claim with all fields including player owner.
     */
    public Claim(int chunkX, int chunkZ, String world, @Nullable UUID guildId, String factionId,
                 @Nullable UUID playerOwnerId, @Nullable UUID suppressorUuid, long claimedAt) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.world = world;
        this.guildId = guildId;
        this.factionId = factionId;
        this.playerOwnerId = playerOwnerId;
        this.suppressorUuid = suppressorUuid;
        this.claimedAt = claimedAt;
    }

    /**
     * Creates a faction-level claim (no guild owner).
     * Used for protected areas like faction capitals.
     */
    public static Claim createFactionClaim(int chunkX, int chunkZ, String world, String factionId) {
        return new Claim(chunkX, chunkZ, world, null, factionId, null, null, System.currentTimeMillis());
    }

    /**
     * Creates a solo player claim (no guild, owned by individual player).
     * Used for players who haven't joined a guild yet.
     */
    public static Claim createSoloPlayerClaim(int chunkX, int chunkZ, String world,
                                               UUID playerOwnerId, String factionId) {
        return new Claim(chunkX, chunkZ, world, null, factionId, playerOwnerId, null, System.currentTimeMillis());
    }

    public int getChunkX() {
        return chunkX;
    }

    public int getChunkZ() {
        return chunkZ;
    }

    public String getWorld() {
        return world;
    }

    @Nullable
    public UUID getGuildId() {
        return guildId;
    }

    public String getFactionId() {
        return factionId;
    }

    /**
     * Returns true if this is a faction-level claim (no guild owner, no player owner).
     * Faction claims are protected areas that block all building/breaking for non-admins.
     */
    public boolean isFactionClaim() {
        return guildId == null && playerOwnerId == null;
    }

    /**
     * Returns true if this is a solo player claim (no guild, but has a player owner).
     */
    public boolean isSoloPlayerClaim() {
        return guildId == null && playerOwnerId != null;
    }

    /**
     * Gets the player owner UUID for solo claims.
     */
    @Nullable
    public UUID getPlayerOwnerId() {
        return playerOwnerId;
    }

    @Nullable
    public UUID getSuppressorUuid() {
        return suppressorUuid;
    }

    public void setSuppressorUuid(@Nullable UUID suppressorUuid) {
        this.suppressorUuid = suppressorUuid;
    }

    public long getClaimedAt() {
        return claimedAt;
    }

    /**
     * Creates a unique key for this claim's location.
     */
    public String getLocationKey() {
        return world + ":" + chunkX + ":" + chunkZ;
    }

    /**
     * Creates a unique key for a chunk location.
     */
    public static String createLocationKey(String world, int chunkX, int chunkZ) {
        return world + ":" + chunkX + ":" + chunkZ;
    }

    @Override
    public String toString() {
        return "Claim{world='" + world + "', x=" + chunkX + ", z=" + chunkZ +
               ", guild=" + guildId + ", playerOwner=" + playerOwnerId +
               ", faction='" + factionId + "', suppressor=" + suppressorUuid + "'}";
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Claim claim = (Claim) obj;
        return chunkX == claim.chunkX && 
               chunkZ == claim.chunkZ && 
               world.equals(claim.world);
    }

    @Override
    public int hashCode() {
        return getLocationKey().hashCode();
    }
}
