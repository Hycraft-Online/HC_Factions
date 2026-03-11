package com.hcfactions.models;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Per-role access requirements for a specific guild-owned chunk.
 * Each field represents the minimum guild role required for a specific action type.
 * A null value means "no custom requirement" (falls back to default behavior).
 */
public class GuildChunkRoleAccess {

    private final UUID guildId;
    private final String world;
    private final int chunkX;
    private final int chunkZ;

    @Nullable private final GuildRole minBreakRole;
    @Nullable private final GuildRole minPlaceRole;
    @Nullable private final GuildRole minInteractRole;
    @Nullable private final GuildRole minDoorsRole;
    @Nullable private final GuildRole minChestsRole;
    @Nullable private final GuildRole minBenchesRole;
    @Nullable private final GuildRole minProcessingRole;
    @Nullable private final GuildRole minSeatsRole;
    @Nullable private final GuildRole minTransportRole;

    @Nullable private final UUID updatedBy;
    private final long updatedAt;

    public GuildChunkRoleAccess(UUID guildId, String world, int chunkX, int chunkZ,
                                @Nullable GuildRole minBreakRole, @Nullable GuildRole minPlaceRole,
                                @Nullable GuildRole minInteractRole, @Nullable GuildRole minDoorsRole,
                                @Nullable GuildRole minChestsRole, @Nullable GuildRole minBenchesRole,
                                @Nullable GuildRole minProcessingRole, @Nullable GuildRole minSeatsRole,
                                @Nullable GuildRole minTransportRole,
                                @Nullable UUID updatedBy, long updatedAt) {
        this.guildId = guildId;
        this.world = world;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minBreakRole = minBreakRole;
        this.minPlaceRole = minPlaceRole;
        this.minInteractRole = minInteractRole;
        this.minDoorsRole = minDoorsRole;
        this.minChestsRole = minChestsRole;
        this.minBenchesRole = minBenchesRole;
        this.minProcessingRole = minProcessingRole;
        this.minSeatsRole = minSeatsRole;
        this.minTransportRole = minTransportRole;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    public UUID getGuildId() { return guildId; }
    public String getWorld() { return world; }
    public int getChunkX() { return chunkX; }
    public int getChunkZ() { return chunkZ; }

    @Nullable public GuildRole getMinBreakRole() { return minBreakRole; }
    @Nullable public GuildRole getMinPlaceRole() { return minPlaceRole; }
    @Nullable public GuildRole getMinInteractRole() { return minInteractRole; }
    @Nullable public GuildRole getMinDoorsRole() { return minDoorsRole; }
    @Nullable public GuildRole getMinChestsRole() { return minChestsRole; }
    @Nullable public GuildRole getMinBenchesRole() { return minBenchesRole; }
    @Nullable public GuildRole getMinProcessingRole() { return minProcessingRole; }
    @Nullable public GuildRole getMinSeatsRole() { return minSeatsRole; }
    @Nullable public GuildRole getMinTransportRole() { return minTransportRole; }

    @Nullable public UUID getUpdatedBy() { return updatedBy; }
    public long getUpdatedAt() { return updatedAt; }

    /**
     * Backward-compat alias: returns the most permissive of break and place roles.
     */
    @Nullable
    public GuildRole getMinEditRole() {
        return mostPermissive(minBreakRole, minPlaceRole);
    }

    /**
     * Backward-compat alias: returns the most permissive of all interaction roles.
     */
    @Nullable
    public GuildRole getMinChestRole() {
        GuildRole result = minInteractRole;
        result = mostPermissive(result, minDoorsRole);
        result = mostPermissive(result, minChestsRole);
        result = mostPermissive(result, minBenchesRole);
        result = mostPermissive(result, minProcessingRole);
        result = mostPermissive(result, minSeatsRole);
        result = mostPermissive(result, minTransportRole);
        return result;
    }

    /**
     * Returns the minimum role for a specific permission row name.
     */
    @Nullable
    public GuildRole getMinRoleFor(String permissionName) {
        return switch (permissionName) {
            case "Break" -> minBreakRole;
            case "Place" -> minPlaceRole;
            case "Interact" -> minInteractRole;
            case "Doors" -> minDoorsRole;
            case "Chests" -> minChestsRole;
            case "Benches" -> minBenchesRole;
            case "Processing" -> minProcessingRole;
            case "Seats" -> minSeatsRole;
            case "Transport" -> minTransportRole;
            default -> null;
        };
    }

    /**
     * Returns true if any of the 9 role fields is non-null.
     */
    public boolean hasAnyCustomPermission() {
        return minBreakRole != null || minPlaceRole != null
            || minInteractRole != null || minDoorsRole != null
            || minChestsRole != null || minBenchesRole != null
            || minProcessingRole != null || minSeatsRole != null
            || minTransportRole != null;
    }

    public String getKey() {
        return createKey(guildId, world, chunkX, chunkZ);
    }

    public static String createKey(UUID guildId, String world, int chunkX, int chunkZ) {
        return guildId + ":" + world + ":" + chunkX + ":" + chunkZ;
    }

    @Nullable
    private static GuildRole mostPermissive(@Nullable GuildRole a, @Nullable GuildRole b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.getLevel() <= b.getLevel() ? a : b;
    }
}
