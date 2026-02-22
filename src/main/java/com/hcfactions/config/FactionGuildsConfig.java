package com.hcfactions.config;

/**
 * Configuration for FactionGuilds plugin.
 * All settings are stored in the database and loaded at startup.
 * Default values are used if no database entry exists.
 */
public class FactionGuildsConfig {

    // ═══════════════════════════════════════════════════════
    // GUILD SETTINGS
    // ═══════════════════════════════════════════════════════

    /** Maximum length for guild names */
    private int guildMaxNameLength = 16;

    /** Minimum length for guild names */
    private int guildMinNameLength = 3;

    /** Base number of claims for a guild (with 1 member) */
    private int guildBaseClaimsPerGuild = 4;

    /** Additional claims per extra member beyond the first */
    private int guildClaimsPerAdditionalMember = 4;

    /** Maximum number of members a guild can have */
    private int guildMaxMembers = 30;

    /** Default power for new guilds / power per member */
    private int guildDefaultPower = 10;

    /** Power required per claim */
    private int guildPowerPerClaim = 1;

    /** Cooldown in seconds for /guild home command (30 minutes = 1800 seconds) */
    private int guildHomeCooldownSeconds = 1800;

    // ═══════════════════════════════════════════════════════
    // PROTECTION SETTINGS
    // ═══════════════════════════════════════════════════════

    /** Whether enemies can destroy blocks in claimed territory */
    private boolean protectionEnemyCanDestroy = false;

    /** Whether enemies can build blocks in claimed territory */
    private boolean protectionEnemyCanBuild = false;

    /** Whether same-faction guilds have access to each other's claims */
    private boolean protectionSameFactionGuildAccess = false;

    // ═══════════════════════════════════════════════════════
    // PVP SETTINGS
    // ═══════════════════════════════════════════════════════

    /** Whether players in the same faction can damage each other */
    private boolean pvpAllowSameFactionPvp = false;

    /** Whether players without a faction are protected from PvP */
    private boolean pvpProtectNoFaction = true;

    // ═══════════════════════════════════════════════════════
    // SPAWN SUPPRESSION SETTINGS
    // ═══════════════════════════════════════════════════════

    /** Whether spawn suppression is enabled in claimed territories */
    private boolean spawnSuppressionEnabled = false;

    // ═══════════════════════════════════════════════════════
    // CONSTRUCTORS
    // ═══════════════════════════════════════════════════════

    /**
     * Creates a config with default values.
     */
    public FactionGuildsConfig() {
        // Default constructor uses field initializers
    }

    // ═══════════════════════════════════════════════════════
    // GUILD GETTERS
    // ═══════════════════════════════════════════════════════

    public int getGuildMaxNameLength() {
        return guildMaxNameLength;
    }

    public int getGuildMinNameLength() {
        return guildMinNameLength;
    }

    public int getGuildBaseClaimsPerGuild() {
        return guildBaseClaimsPerGuild;
    }

    public int getGuildClaimsPerAdditionalMember() {
        return guildClaimsPerAdditionalMember;
    }

    public int getGuildMaxMembers() {
        return guildMaxMembers;
    }

    public int getGuildDefaultPower() {
        return guildDefaultPower;
    }

    public int getGuildPowerPerClaim() {
        return guildPowerPerClaim;
    }

    public int getGuildHomeCooldownSeconds() {
        return guildHomeCooldownSeconds;
    }

    // ═══════════════════════════════════════════════════════
    // PROTECTION GETTERS
    // ═══════════════════════════════════════════════════════

    public boolean isProtectionEnemyCanDestroy() {
        return protectionEnemyCanDestroy;
    }

    public boolean isProtectionEnemyCanBuild() {
        return protectionEnemyCanBuild;
    }

    public boolean isProtectionSameFactionGuildAccess() {
        return protectionSameFactionGuildAccess;
    }

    // ═══════════════════════════════════════════════════════
    // PVP GETTERS
    // ═══════════════════════════════════════════════════════

    public boolean isPvpAllowSameFactionPvp() {
        return pvpAllowSameFactionPvp;
    }

    public boolean isPvpProtectNoFaction() {
        return pvpProtectNoFaction;
    }

    // ═══════════════════════════════════════════════════════
    // SPAWN SUPPRESSION GETTERS
    // ═══════════════════════════════════════════════════════

    public boolean isSpawnSuppressionEnabled() {
        return spawnSuppressionEnabled;
    }

    // ═══════════════════════════════════════════════════════
    // GUILD SETTERS (for loading from database)
    // ═══════════════════════════════════════════════════════

    public void setGuildMaxNameLength(int value) {
        this.guildMaxNameLength = value;
    }

    public void setGuildMinNameLength(int value) {
        this.guildMinNameLength = value;
    }

    public void setGuildBaseClaimsPerGuild(int value) {
        this.guildBaseClaimsPerGuild = value;
    }

    public void setGuildClaimsPerAdditionalMember(int value) {
        this.guildClaimsPerAdditionalMember = value;
    }

    public void setGuildMaxMembers(int value) {
        this.guildMaxMembers = value;
    }

    public void setGuildDefaultPower(int value) {
        this.guildDefaultPower = value;
    }

    public void setGuildPowerPerClaim(int value) {
        this.guildPowerPerClaim = value;
    }

    public void setGuildHomeCooldownSeconds(int value) {
        this.guildHomeCooldownSeconds = value;
    }

    // ═══════════════════════════════════════════════════════
    // PROTECTION SETTERS
    // ═══════════════════════════════════════════════════════

    public void setProtectionEnemyCanDestroy(boolean value) {
        this.protectionEnemyCanDestroy = value;
    }

    public void setProtectionEnemyCanBuild(boolean value) {
        this.protectionEnemyCanBuild = value;
    }

    public void setProtectionSameFactionGuildAccess(boolean value) {
        this.protectionSameFactionGuildAccess = value;
    }

    // ═══════════════════════════════════════════════════════
    // PVP SETTERS
    // ═══════════════════════════════════════════════════════

    public void setPvpAllowSameFactionPvp(boolean value) {
        this.pvpAllowSameFactionPvp = value;
    }

    public void setPvpProtectNoFaction(boolean value) {
        this.pvpProtectNoFaction = value;
    }

    // ═══════════════════════════════════════════════════════
    // SPAWN SUPPRESSION SETTERS
    // ═══════════════════════════════════════════════════════

    public void setSpawnSuppressionEnabled(boolean value) {
        this.spawnSuppressionEnabled = value;
    }

    @Override
    public String toString() {
        return "FactionGuildsConfig{" +
                "guildMaxNameLength=" + guildMaxNameLength +
                ", guildMinNameLength=" + guildMinNameLength +
                ", guildBaseClaimsPerGuild=" + guildBaseClaimsPerGuild +
                ", guildClaimsPerAdditionalMember=" + guildClaimsPerAdditionalMember +
                ", guildMaxMembers=" + guildMaxMembers +
                ", guildDefaultPower=" + guildDefaultPower +
                ", guildPowerPerClaim=" + guildPowerPerClaim +
                ", guildHomeCooldownSeconds=" + guildHomeCooldownSeconds +
                ", protectionEnemyCanDestroy=" + protectionEnemyCanDestroy +
                ", protectionEnemyCanBuild=" + protectionEnemyCanBuild +
                ", protectionSameFactionGuildAccess=" + protectionSameFactionGuildAccess +
                ", pvpAllowSameFactionPvp=" + pvpAllowSameFactionPvp +
                ", pvpProtectNoFaction=" + pvpProtectNoFaction +
                ", spawnSuppressionEnabled=" + spawnSuppressionEnabled +
                '}';
    }
}
