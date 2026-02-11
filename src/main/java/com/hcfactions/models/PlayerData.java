package com.hcfactions.models;

import java.util.UUID;

/**
 * Represents a player's faction and guild data.
 */
public class PlayerData {

    private final UUID playerUuid;
    private String playerName;
    private String factionId;
    private UUID guildId;
    private GuildRole guildRole;
    private long guildJoinedAt;
    private boolean hasChosenFaction;
    private long createdAt;
    private long updatedAt;
    
    // Personal home location
    private String homeWorld;
    private Double homeX;
    private Double homeY;
    private Double homeZ;

    public PlayerData(UUID playerUuid) {
        this.playerUuid = playerUuid;
        this.hasChosenFaction = false;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = System.currentTimeMillis();
    }

    // Getters

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getFactionId() {
        return factionId;
    }

    public UUID getGuildId() {
        return guildId;
    }

    public GuildRole getGuildRole() {
        return guildRole;
    }

    public long getGuildJoinedAt() {
        return guildJoinedAt;
    }

    public boolean hasChosenFaction() {
        return hasChosenFaction;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public boolean isInGuild() {
        return guildId != null;
    }

    // Setters

    public void setPlayerName(String playerName) {
        this.playerName = playerName;
        this.updatedAt = System.currentTimeMillis();
    }

    public void setFactionId(String factionId) {
        this.factionId = factionId;
        this.hasChosenFaction = (factionId != null);
        this.updatedAt = System.currentTimeMillis();
    }

    public void setGuildId(UUID guildId) {
        this.guildId = guildId;
        if (guildId == null) {
            this.guildRole = null;
            this.guildJoinedAt = 0;
        }
        this.updatedAt = System.currentTimeMillis();
    }

    public void setGuildRole(GuildRole guildRole) {
        this.guildRole = guildRole;
        this.updatedAt = System.currentTimeMillis();
    }

    public void setGuildJoinedAt(long guildJoinedAt) {
        this.guildJoinedAt = guildJoinedAt;
    }

    public void setHasChosenFaction(boolean hasChosenFaction) {
        this.hasChosenFaction = hasChosenFaction;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    // Home location getters and setters
    
    public String getHomeWorld() {
        return homeWorld;
    }
    
    public Double getHomeX() {
        return homeX;
    }
    
    public Double getHomeY() {
        return homeY;
    }
    
    public Double getHomeZ() {
        return homeZ;
    }
    
    public boolean hasHome() {
        return homeWorld != null && homeX != null && homeY != null && homeZ != null;
    }
    
    public void setHome(String world, double x, double y, double z) {
        this.homeWorld = world;
        this.homeX = x;
        this.homeY = y;
        this.homeZ = z;
        this.updatedAt = System.currentTimeMillis();
    }
    
    public void setHomeWorld(String homeWorld) {
        this.homeWorld = homeWorld;
    }
    
    public void setHomeX(Double homeX) {
        this.homeX = homeX;
    }
    
    public void setHomeY(Double homeY) {
        this.homeY = homeY;
    }
    
    public void setHomeZ(Double homeZ) {
        this.homeZ = homeZ;
    }
    
    public void clearHome() {
        this.homeWorld = null;
        this.homeX = null;
        this.homeY = null;
        this.homeZ = null;
        this.updatedAt = System.currentTimeMillis();
    }

    /**
     * Joins a guild with the specified role.
     */
    public void joinGuild(UUID guildId, GuildRole role) {
        this.guildId = guildId;
        this.guildRole = role;
        this.guildJoinedAt = System.currentTimeMillis();
        this.updatedAt = System.currentTimeMillis();
    }

    /**
     * Leaves the current guild.
     */
    public void leaveGuild() {
        this.guildId = null;
        this.guildRole = null;
        this.guildJoinedAt = 0;
        this.updatedAt = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return "PlayerData{uuid=" + playerUuid + 
               ", faction='" + factionId + "'" +
               ", guild=" + guildId +
               ", role=" + guildRole + "}";
    }
}
