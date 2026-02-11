package com.hcfactions.models;

import java.awt.Color;

/**
 * Represents an admin-defined major faction (e.g., Valor, Iron Legion).
 *
 * spawn_x/y/z = faction capital location
 * rtpSpawn_x/y/z = where players spawn after starter area (between capitals)
 */
public class Faction {

    private final String id;
    private final String displayName;
    private final Color color;
    private final String spawnWorld;
    private final double spawnX;
    private final double spawnY;
    private final double spawnZ;
    private final Double rtpSpawnX;  // Nullable - RTP spawn after starter area
    private final Double rtpSpawnY;
    private final Double rtpSpawnZ;

    public Faction(String id, String displayName, String colorHex,
                   String spawnWorld, double spawnX, double spawnY, double spawnZ) {
        this(id, displayName, colorHex, spawnWorld, spawnX, spawnY, spawnZ, null, null, null);
    }

    public Faction(String id, String displayName, String colorHex,
                   String spawnWorld, double spawnX, double spawnY, double spawnZ,
                   Double rtpSpawnX, Double rtpSpawnY, Double rtpSpawnZ) {
        this.id = id;
        this.displayName = displayName;
        this.color = parseColor(colorHex);
        this.spawnWorld = spawnWorld;
        this.spawnX = spawnX;
        this.spawnY = spawnY;
        this.spawnZ = spawnZ;
        this.rtpSpawnX = rtpSpawnX;
        this.rtpSpawnY = rtpSpawnY;
        this.rtpSpawnZ = rtpSpawnZ;
    }

    private static Color parseColor(String hex) {
        try {
            if (hex.startsWith("#")) {
                hex = hex.substring(1);
            }
            return new Color(Integer.parseInt(hex, 16));
        } catch (Exception e) {
            return Color.WHITE;
        }
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Gets a short name for the faction (first 3 letters uppercase).
     * Used for map marker tags like [VAL] or [IRN].
     */
    public String getShortName() {
        if (displayName == null || displayName.isEmpty()) {
            return id.substring(0, Math.min(3, id.length())).toUpperCase();
        }
        // Take first 3 letters of the first word
        String[] words = displayName.split("\\s+");
        String firstWord = words[0].replaceAll("[^a-zA-Z]", "");
        return firstWord.substring(0, Math.min(3, firstWord.length())).toUpperCase();
    }

    public Color getColor() {
        return color;
    }

    /**
     * Gets the color as a hex string (e.g., "#FF0000").
     * Used for database storage.
     */
    public String getColorHex() {
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }

    public String getSpawnWorld() {
        return spawnWorld;
    }

    public double getSpawnX() {
        return spawnX;
    }

    public double getSpawnY() {
        return spawnY;
    }

    public double getSpawnZ() {
        return spawnZ;
    }

    /**
     * Gets the RTP spawn X coordinate (where players spawn after starter area).
     * Returns null if not set.
     */
    public Double getRtpSpawnX() {
        return rtpSpawnX;
    }

    /**
     * Gets the RTP spawn Y coordinate (where players spawn after starter area).
     * Returns null if not set.
     */
    public Double getRtpSpawnY() {
        return rtpSpawnY;
    }

    /**
     * Gets the RTP spawn Z coordinate (where players spawn after starter area).
     * Returns null if not set.
     */
    public Double getRtpSpawnZ() {
        return rtpSpawnZ;
    }

    /**
     * Check if this faction has RTP spawn coordinates configured.
     */
    public boolean hasRtpSpawn() {
        return rtpSpawnX != null && rtpSpawnY != null && rtpSpawnZ != null;
    }

    /**
     * Checks if this faction is an enemy of the given faction.
     * In this system, any faction that is not the same is an enemy.
     */
    public boolean isEnemy(Faction other) {
        if (other == null) return false;
        return !this.id.equals(other.id);
    }

    @Override
    public String toString() {
        return "Faction{id='" + id + "', displayName='" + displayName + "'}";
    }
}
