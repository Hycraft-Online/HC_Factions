package com.hcfactions.config;

import com.hccore.api.HC_CoreAPI;
import com.hccore.models.SettingDef;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for FactionGuilds plugin.
 * Thin wrapper over HC_CoreAPI settings — reads live from the mod_settings table.
 * Setter methods write through to the database immediately.
 */
public class FactionGuildsConfig {

    private static final String PLUGIN = "HC_Factions";

    /**
     * Registers all default settings with HC_Core.
     * Call once during plugin setup.
     */
    public static void registerDefaults() {
        Map<String, SettingDef> defaults = new LinkedHashMap<>();

        // Guild settings
        defaults.put("guild.maxNameLength", new SettingDef("16", "INT", "Maximum length for guild names"));
        defaults.put("guild.minNameLength", new SettingDef("3", "INT", "Minimum length for guild names"));
        defaults.put("guild.baseClaimsPerGuild", new SettingDef("4", "INT", "Base number of claims for a guild (with 1 member)"));
        defaults.put("guild.claimsPerAdditionalMember", new SettingDef("4", "INT", "Additional claims per extra member beyond the first"));
        defaults.put("guild.maxMembers", new SettingDef("30", "INT", "Maximum number of members a guild can have"));
        defaults.put("guild.defaultPower", new SettingDef("10", "INT", "Default power for new guilds / power per member"));
        defaults.put("guild.powerPerClaim", new SettingDef("1", "INT", "Power required per claim"));
        defaults.put("guild.homeCooldownSeconds", new SettingDef("1800", "INT", "Cooldown in seconds for /guild home command"));

        // Protection settings
        defaults.put("protection.enemyCanDestroy", new SettingDef("false", "BOOLEAN", "Whether enemies can destroy blocks in claimed territory"));
        defaults.put("protection.enemyCanBuild", new SettingDef("false", "BOOLEAN", "Whether enemies can build blocks in claimed territory"));
        defaults.put("protection.sameFactionGuildAccess", new SettingDef("false", "BOOLEAN", "Whether same-faction guilds have access to each other's claims"));

        // PvP settings
        defaults.put("pvp.allowSameFactionPvp", new SettingDef("false", "BOOLEAN", "Whether players in the same faction can damage each other"));
        defaults.put("pvp.protectNoFaction", new SettingDef("true", "BOOLEAN", "Whether players without a faction are protected from PvP"));

        // Combat tag settings
        defaults.put("combat.tagDurationSeconds", new SettingDef("15", "INT", "Duration in seconds a player stays combat-tagged after PvP"));
        defaults.put("combat.logoutPenaltyEnabled", new SettingDef("true", "BOOLEAN", "Whether logging out while combat-tagged triggers a penalty"));
        defaults.put("combat.taggedLogoutMessage", new SettingDef("combat logged!", "STRING", "Message broadcast when a player combat logs"));

        // Spawn suppression settings
        defaults.put("spawnSuppression.enabled", new SettingDef("false", "BOOLEAN", "Whether spawn suppression is enabled in claimed territories"));

        // Perimeter reservation settings
        defaults.put("perimeter.reservationEnabled", new SettingDef("true", "BOOLEAN", "Whether perimeter reservation is enabled"));

        // Claim decay settings
        defaults.put("claimDecay.enabled", new SettingDef("false", "BOOLEAN", "Whether inactive guild claims are gradually removed"));
        defaults.put("claimDecay.daysInactive", new SettingDef("30", "INT", "Days of guild inactivity before claims start decaying"));
        defaults.put("claimDecay.claimsPerDay", new SettingDef("1", "INT", "Number of claims removed per decay cycle (daily)"));

        HC_CoreAPI.registerDefaults(PLUGIN, defaults);
    }

    // ═══════════════════════════════════════════════════════
    // GUILD GETTERS
    // ═══════════════════════════════════════════════════════

    public int getGuildMaxNameLength() {
        return HC_CoreAPI.getSettingInt(PLUGIN, "guild.maxNameLength", 16);
    }

    public int getGuildMinNameLength() {
        return HC_CoreAPI.getSettingInt(PLUGIN, "guild.minNameLength", 3);
    }

    public int getGuildBaseClaimsPerGuild() {
        return HC_CoreAPI.getSettingInt(PLUGIN, "guild.baseClaimsPerGuild", 4);
    }

    public int getGuildClaimsPerAdditionalMember() {
        return HC_CoreAPI.getSettingInt(PLUGIN, "guild.claimsPerAdditionalMember", 4);
    }

    public int getGuildMaxMembers() {
        return HC_CoreAPI.getSettingInt(PLUGIN, "guild.maxMembers", 30);
    }

    public int getGuildDefaultPower() {
        return HC_CoreAPI.getSettingInt(PLUGIN, "guild.defaultPower", 10);
    }

    public int getGuildPowerPerClaim() {
        return HC_CoreAPI.getSettingInt(PLUGIN, "guild.powerPerClaim", 1);
    }

    public int getGuildHomeCooldownSeconds() {
        return HC_CoreAPI.getSettingInt(PLUGIN, "guild.homeCooldownSeconds", 1800);
    }

    // ═══════════════════════════════════════════════════════
    // PROTECTION GETTERS
    // ═══════════════════════════════════════════════════════

    public boolean isProtectionEnemyCanDestroy() {
        return HC_CoreAPI.getSettingBool(PLUGIN, "protection.enemyCanDestroy", false);
    }

    public boolean isProtectionEnemyCanBuild() {
        return HC_CoreAPI.getSettingBool(PLUGIN, "protection.enemyCanBuild", false);
    }

    public boolean isProtectionSameFactionGuildAccess() {
        return HC_CoreAPI.getSettingBool(PLUGIN, "protection.sameFactionGuildAccess", false);
    }

    // ═══════════════════════════════════════════════════════
    // PVP GETTERS
    // ═══════════════════════════════════════════════════════

    public boolean isPvpAllowSameFactionPvp() {
        return HC_CoreAPI.getSettingBool(PLUGIN, "pvp.allowSameFactionPvp", false);
    }

    public boolean isPvpProtectNoFaction() {
        return HC_CoreAPI.getSettingBool(PLUGIN, "pvp.protectNoFaction", true);
    }

    // ═══════════════════════════════════════════════════════
    // COMBAT TAG GETTERS
    // ═══════════════════════════════════════════════════════

    public int getCombatTagDurationSeconds() {
        return HC_CoreAPI.getSettingInt(PLUGIN, "combat.tagDurationSeconds", 15);
    }

    public boolean isCombatLogoutPenaltyEnabled() {
        return HC_CoreAPI.getSettingBool(PLUGIN, "combat.logoutPenaltyEnabled", true);
    }

    public String getCombatTaggedLogoutMessage() {
        return HC_CoreAPI.getSetting(PLUGIN, "combat.taggedLogoutMessage", "combat logged!");
    }

    // ═══════════════════════════════════════════════════════
    // SPAWN SUPPRESSION GETTERS
    // ═══════════════════════════════════════════════════════

    public boolean isSpawnSuppressionEnabled() {
        return HC_CoreAPI.getSettingBool(PLUGIN, "spawnSuppression.enabled", false);
    }

    // ═══════════════════════════════════════════════════════
    // PERIMETER RESERVATION GETTERS
    // ═══════════════════════════════════════════════════════

    public boolean isPerimeterReservationEnabled() {
        return HC_CoreAPI.getSettingBool(PLUGIN, "perimeter.reservationEnabled", true);
    }

    // ═══════════════════════════════════════════════════════
    // CLAIM DECAY GETTERS
    // ═══════════════════════════════════════════════════════

    public boolean isClaimDecayEnabled() {
        return HC_CoreAPI.getSettingBool(PLUGIN, "claimDecay.enabled", false);
    }

    public int getClaimDecayDaysInactive() {
        return HC_CoreAPI.getSettingInt(PLUGIN, "claimDecay.daysInactive", 30);
    }

    public int getClaimDecayClaimsPerDay() {
        return HC_CoreAPI.getSettingInt(PLUGIN, "claimDecay.claimsPerDay", 1);
    }

    // ═══════════════════════════════════════════════════════
    // SETTERS (write-through to HC_CoreAPI)
    // ═══════════════════════════════════════════════════════

    public void setGuildMaxNameLength(int value) {
        HC_CoreAPI.setSetting(PLUGIN, "guild.maxNameLength", String.valueOf(value));
    }

    public void setGuildMinNameLength(int value) {
        HC_CoreAPI.setSetting(PLUGIN, "guild.minNameLength", String.valueOf(value));
    }

    public void setGuildBaseClaimsPerGuild(int value) {
        HC_CoreAPI.setSetting(PLUGIN, "guild.baseClaimsPerGuild", String.valueOf(value));
    }

    public void setGuildClaimsPerAdditionalMember(int value) {
        HC_CoreAPI.setSetting(PLUGIN, "guild.claimsPerAdditionalMember", String.valueOf(value));
    }

    public void setGuildMaxMembers(int value) {
        HC_CoreAPI.setSetting(PLUGIN, "guild.maxMembers", String.valueOf(value));
    }

    public void setGuildDefaultPower(int value) {
        HC_CoreAPI.setSetting(PLUGIN, "guild.defaultPower", String.valueOf(value));
    }

    public void setGuildPowerPerClaim(int value) {
        HC_CoreAPI.setSetting(PLUGIN, "guild.powerPerClaim", String.valueOf(value));
    }

    public void setGuildHomeCooldownSeconds(int value) {
        HC_CoreAPI.setSetting(PLUGIN, "guild.homeCooldownSeconds", String.valueOf(value));
    }

    public void setProtectionEnemyCanDestroy(boolean value) {
        HC_CoreAPI.setSetting(PLUGIN, "protection.enemyCanDestroy", String.valueOf(value));
    }

    public void setProtectionEnemyCanBuild(boolean value) {
        HC_CoreAPI.setSetting(PLUGIN, "protection.enemyCanBuild", String.valueOf(value));
    }

    public void setProtectionSameFactionGuildAccess(boolean value) {
        HC_CoreAPI.setSetting(PLUGIN, "protection.sameFactionGuildAccess", String.valueOf(value));
    }

    public void setPvpAllowSameFactionPvp(boolean value) {
        HC_CoreAPI.setSetting(PLUGIN, "pvp.allowSameFactionPvp", String.valueOf(value));
    }

    public void setPvpProtectNoFaction(boolean value) {
        HC_CoreAPI.setSetting(PLUGIN, "pvp.protectNoFaction", String.valueOf(value));
    }

    public void setCombatTagDurationSeconds(int value) {
        HC_CoreAPI.setSetting(PLUGIN, "combat.tagDurationSeconds", String.valueOf(value));
    }

    public void setCombatLogoutPenaltyEnabled(boolean value) {
        HC_CoreAPI.setSetting(PLUGIN, "combat.logoutPenaltyEnabled", String.valueOf(value));
    }

    public void setCombatTaggedLogoutMessage(String value) {
        HC_CoreAPI.setSetting(PLUGIN, "combat.taggedLogoutMessage", value);
    }

    public void setSpawnSuppressionEnabled(boolean value) {
        HC_CoreAPI.setSetting(PLUGIN, "spawnSuppression.enabled", String.valueOf(value));
    }

    public void setPerimeterReservationEnabled(boolean value) {
        HC_CoreAPI.setSetting(PLUGIN, "perimeter.reservationEnabled", String.valueOf(value));
    }

    public void setClaimDecayEnabled(boolean value) {
        HC_CoreAPI.setSetting(PLUGIN, "claimDecay.enabled", String.valueOf(value));
    }

    public void setClaimDecayDaysInactive(int value) {
        HC_CoreAPI.setSetting(PLUGIN, "claimDecay.daysInactive", String.valueOf(value));
    }

    public void setClaimDecayClaimsPerDay(int value) {
        HC_CoreAPI.setSetting(PLUGIN, "claimDecay.claimsPerDay", String.valueOf(value));
    }

    @Override
    public String toString() {
        return "FactionGuildsConfig{via HC_CoreAPI mod_settings}";
    }
}
