package com.hcfactions.managers;

import com.hcfactions.HC_FactionsPlugin;
import com.hcfactions.config.FactionGuildsConfig;
import com.hcfactions.database.repositories.ClaimRepository;
import com.hcfactions.database.repositories.GuildRepository;
import com.hcfactions.database.repositories.PlayerDataRepository;
import com.hcfactions.models.Claim;
import com.hcfactions.models.Guild;
import com.hypixel.hytale.logger.HytaleLogger;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Manages claim decay for inactive guilds.
 *
 * When enabled, this system periodically checks all guilds for inactivity.
 * If no member of a guild has logged in for the configured threshold (default 30 days),
 * the guild's oldest claims are removed at a rate of claimsPerDay per cycle.
 *
 * Faction-level claims (admin claims) are exempt from decay.
 * Solo player claims are exempt from decay (they have their own small limit).
 */
public class ClaimDecayManager {

    private static final HytaleLogger LOGGER = HytaleLogger.getLogger().getSubLogger("FactionGuilds-ClaimDecay");

    private final HC_FactionsPlugin plugin;
    private final FactionGuildsConfig config;
    private final ClaimManager claimManager;
    private final ClaimRepository claimRepository;
    private final GuildRepository guildRepository;
    private final PlayerDataRepository playerDataRepository;

    public ClaimDecayManager(HC_FactionsPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfig();
        this.claimManager = plugin.getClaimManager();
        this.claimRepository = plugin.getClaimRepository();
        this.guildRepository = plugin.getGuildRepository();
        this.playerDataRepository = plugin.getPlayerDataRepository();
    }

    /**
     * Runs a single decay cycle. Checks all guilds for inactivity and removes
     * oldest claims from inactive guilds.
     *
     * @return Total number of claims decayed across all guilds
     */
    public int runDecayCycle() {
        if (!config.isClaimDecayEnabled()) {
            LOGGER.at(Level.FINE).log("[ClaimDecay] Decay is disabled, skipping cycle");
            return 0;
        }

        int daysInactive = config.getClaimDecayDaysInactive();
        int claimsPerDay = config.getClaimDecayClaimsPerDay();
        long thresholdMs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(daysInactive);

        LOGGER.at(Level.INFO).log("[ClaimDecay] Running decay cycle: threshold=%d days, claimsPerDay=%d", daysInactive, claimsPerDay);

        List<Guild> allGuilds = guildRepository.getAllGuilds();
        int totalDecayed = 0;

        for (Guild guild : allGuilds) {
            try {
                int decayed = processGuildDecay(guild, thresholdMs, claimsPerDay, daysInactive);
                totalDecayed += decayed;
            } catch (Exception e) {
                LOGGER.at(Level.SEVERE).log("[ClaimDecay] Error processing decay for guild %s: %s",
                        guild.getName(), e.getMessage());
            }
        }

        if (totalDecayed > 0) {
            LOGGER.at(Level.INFO).log("[ClaimDecay] Decay cycle complete: %d claims removed from %d guild(s)",
                    totalDecayed, allGuilds.size());
        } else {
            LOGGER.at(Level.INFO).log("[ClaimDecay] Decay cycle complete: no claims decayed");
        }

        return totalDecayed;
    }

    /**
     * Processes decay for a single guild.
     *
     * @param guild The guild to check
     * @param thresholdMs Cutoff timestamp -- guilds with all members last online before this are eligible
     * @param claimsPerDay Number of claims to remove per cycle
     * @param daysInactive The configured inactive threshold (for logging)
     * @return Number of claims removed
     */
    private int processGuildDecay(Guild guild, long thresholdMs, int claimsPerDay, int daysInactive) {
        UUID guildId = guild.getId();

        // Check if guild has any claims at all (skip DB query if not)
        int claimCount = claimRepository.getGuildClaimCount(guildId);
        if (claimCount == 0) {
            return 0;
        }

        // Find the most recent login among all guild members
        Long lastOnlineMs = playerDataRepository.getGuildLastOnline(guildId);
        if (lastOnlineMs == null) {
            // Guild has no members at all -- should not normally happen, but skip
            LOGGER.at(Level.WARNING).log("[ClaimDecay] Guild %s has claims but no members, skipping", guild.getName());
            return 0;
        }

        // Guild is still active -- skip
        if (lastOnlineMs >= thresholdMs) {
            return 0;
        }

        // Calculate actual days inactive
        long actualDaysInactive = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - lastOnlineMs);

        LOGGER.at(Level.INFO).log("[ClaimDecay] Guild '%s' is inactive (%d days). Removing up to %d claim(s).",
                guild.getName(), actualDaysInactive, claimsPerDay);

        // Get the oldest claims to remove
        List<Claim> oldestClaims = claimRepository.getOldestGuildClaims(guildId, claimsPerDay);
        int decayed = 0;

        for (Claim claim : oldestClaims) {
            boolean unclaimed = claimManager.unclaimChunk(guildId, claim.getWorld(), claim.getChunkX(), claim.getChunkZ());
            if (unclaimed) {
                // Log the decay event
                claimRepository.logClaimDecay(guildId, guild.getName(),
                        claim.getChunkX(), claim.getChunkZ(), claim.getWorld(), (int) actualDaysInactive);
                decayed++;

                LOGGER.at(Level.INFO).log("[ClaimDecay] Removed claim at %s:%d:%d from guild '%s' (inactive %d days)",
                        claim.getWorld(), claim.getChunkX(), claim.getChunkZ(), guild.getName(), actualDaysInactive);
            } else {
                LOGGER.at(Level.WARNING).log("[ClaimDecay] Failed to unclaim %s:%d:%d for guild '%s'",
                        claim.getWorld(), claim.getChunkX(), claim.getChunkZ(), guild.getName());
            }
        }

        return decayed;
    }

    /**
     * Gets the decay status for a guild.
     *
     * @param guildId The guild to check
     * @return A human-readable status string
     */
    public String getDecayStatus(UUID guildId) {
        if (!config.isClaimDecayEnabled()) {
            return "Claim decay is disabled";
        }

        int claimCount = claimRepository.getGuildClaimCount(guildId);
        if (claimCount == 0) {
            return "No claims to decay";
        }

        Long lastOnlineMs = playerDataRepository.getGuildLastOnline(guildId);
        if (lastOnlineMs == null) {
            return "No member activity data";
        }

        int daysThreshold = config.getClaimDecayDaysInactive();
        long daysSinceActive = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - lastOnlineMs);

        if (daysSinceActive >= daysThreshold) {
            return "DECAYING - inactive for " + daysSinceActive + " days (threshold: " + daysThreshold + ")";
        }

        long daysUntilDecay = daysThreshold - daysSinceActive;
        return "Active - " + daysUntilDecay + " day(s) until decay begins";
    }

    /**
     * Gets the number of claims that have been decayed for a guild since a given timestamp.
     * Used to notify players on login about territory lost to inactivity.
     *
     * @param guildId The guild to check
     * @param sinceMs Check for decays since this timestamp (milliseconds)
     * @return Number of claims decayed since the given time
     */
    public int getDecayedClaimsSince(UUID guildId, long sinceMs) {
        return claimRepository.getDecayedClaimCountSince(guildId, sinceMs);
    }
}
