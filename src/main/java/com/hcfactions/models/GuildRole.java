package com.hcfactions.models;

/**
 * Roles within a guild, ordered by permission level.
 */
public enum GuildRole {
    LEADER(4, "Leader"),
    OFFICER(3, "Officer"),
    MEMBER(2, "Member"),
    RECRUIT(1, "Recruit");

    private final int level;
    private final String displayName;

    GuildRole(int level, String displayName) {
        this.level = level;
        this.displayName = displayName;
    }

    public int getLevel() {
        return level;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Check if this role has at least the given permission level.
     */
    public boolean hasAtLeast(GuildRole required) {
        return this.level >= required.level;
    }

    /**
     * Check if this role can manage the given role (promote/demote/kick).
     */
    public boolean canManage(GuildRole target) {
        return this.level > target.level;
    }

    public static GuildRole fromString(String value) {
        if (value == null) return RECRUIT;
        try {
            return GuildRole.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return RECRUIT;
        }
    }
}
