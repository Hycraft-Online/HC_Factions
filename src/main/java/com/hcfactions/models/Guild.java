package com.hcfactions.models;

import java.util.UUID;

/**
 * Represents a player-created guild within a major faction.
 */
public class Guild {

    private final UUID id;
    private String name;
    private String tag; // Custom 1-4 letter tag for nameplates, null = use name
    private String factionId;
    private UUID leaderId;
    private int power;
    private int maxPower;
    private double bankBalance;
    
    // Guild home location
    private String homeWorld;
    private Double homeX;
    private Double homeY;
    private Double homeZ;
    
    private long createdAt;
    private long updatedAt;

    public Guild(UUID id, String name, String factionId, UUID leaderId) {
        this.id = id;
        this.name = name;
        this.factionId = factionId;
        this.leaderId = leaderId;
        this.power = 10;
        this.maxPower = 10;
        this.bankBalance = 0;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = System.currentTimeMillis();
    }

    // Getters

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    /**
     * Gets the custom tag if set, or null if not set.
     */
    public String getTag() {
        return tag;
    }

    /**
     * Gets the display tag for nameplates.
     * Returns the custom tag if set, otherwise truncates the name to 4 characters.
     */
    public String getDisplayTag() {
        if (tag != null && !tag.isEmpty()) {
            return tag;
        }
        // Fall back to truncated name
        if (name.length() > 4) {
            return name.substring(0, 4);
        }
        return name;
    }

    public String getFactionId() {
        return factionId;
    }

    public UUID getLeaderId() {
        return leaderId;
    }

    public int getPower() {
        return power;
    }

    public int getMaxPower() {
        return maxPower;
    }

    public double getBankBalance() {
        return bankBalance;
    }

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

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public boolean hasHome() {
        return homeWorld != null && homeX != null && homeY != null && homeZ != null;
    }

    // Setters

    public void setName(String name) {
        this.name = name;
        this.updatedAt = System.currentTimeMillis();
    }

    /**
     * Sets the custom guild tag (1-4 uppercase letters).
     * Pass null to clear the tag and use the guild name instead.
     */
    public void setTag(String tag) {
        this.tag = tag;
        this.updatedAt = System.currentTimeMillis();
    }

    public void setLeaderId(UUID leaderId) {
        this.leaderId = leaderId;
        this.updatedAt = System.currentTimeMillis();
    }

    public void setPower(int power) {
        this.power = power;
        this.updatedAt = System.currentTimeMillis();
    }

    public void setMaxPower(int maxPower) {
        this.maxPower = maxPower;
        this.updatedAt = System.currentTimeMillis();
    }

    public void setBankBalance(double bankBalance) {
        this.bankBalance = bankBalance;
        this.updatedAt = System.currentTimeMillis();
    }

    public void setHome(String world, double x, double y, double z) {
        this.homeWorld = world;
        this.homeX = x;
        this.homeY = y;
        this.homeZ = z;
        this.updatedAt = System.currentTimeMillis();
    }

    public void clearHome() {
        this.homeWorld = null;
        this.homeX = null;
        this.homeY = null;
        this.homeZ = null;
        this.updatedAt = System.currentTimeMillis();
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    // Power management

    public void addPower(int amount) {
        this.power = Math.min(this.power + amount, this.maxPower);
        this.updatedAt = System.currentTimeMillis();
    }

    public void removePower(int amount) {
        this.power = Math.max(0, this.power - amount);
        this.updatedAt = System.currentTimeMillis();
    }

    // Bank management

    public boolean withdraw(double amount) {
        if (amount <= 0 || amount > bankBalance) {
            return false;
        }
        this.bankBalance -= amount;
        this.updatedAt = System.currentTimeMillis();
        return true;
    }

    public void deposit(double amount) {
        if (amount > 0) {
            this.bankBalance += amount;
            this.updatedAt = System.currentTimeMillis();
        }
    }

    @Override
    public String toString() {
        return "Guild{id=" + id + ", name='" + name + "', faction='" + factionId + "'}";
    }
}
