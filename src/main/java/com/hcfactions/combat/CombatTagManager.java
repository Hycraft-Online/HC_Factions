package com.hcfactions.combat;

import com.hccore.api.HC_CoreAPI;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.awt.Color;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manager for combat tagging.
 *
 * Provides methods to tag players, check tag status, and read configuration
 * from HC_CoreAPI mod_settings.
 *
 * Maintains an in-memory mirror of tagged player UUIDs (via {@link #recentTags})
 * so that the disconnect handler can check tag status without needing ECS store access
 * (which is unavailable during disconnect events).
 */
public class CombatTagManager {

    private static final String PLUGIN = "HC_Factions";

    private static final Message MSG_COMBAT_TAGGED =
            Message.raw("[Combat] You are now in combat! Do not log out.").color(Color.RED);

    /**
     * In-memory mirror: maps player UUID to the timestamp when they were last tagged.
     * Updated whenever a tag is applied or refreshed. Cleared when tag expires naturally
     * (via {@link CombatTagSystem}) or after disconnect penalty is processed.
     *
     * Thread-safe -- written from world threads, read from scheduled executor.
     */
    private final ConcurrentHashMap<UUID, Long> recentTags = new ConcurrentHashMap<>();

    // ─── Configuration (live from HC_CoreAPI) ───

    public int getTagDurationSeconds() {
        return HC_CoreAPI.getSettingInt(PLUGIN, "combat.tagDurationSeconds", 15);
    }

    public boolean isLogoutPenaltyEnabled() {
        return HC_CoreAPI.getSettingBool(PLUGIN, "combat.logoutPenaltyEnabled", true);
    }

    public String getTaggedLogoutMessage() {
        return HC_CoreAPI.getSetting(PLUGIN, "combat.taggedLogoutMessage", "combat logged!");
    }

    // ─── Tag operations ───

    /**
     * Tags a player as in-combat. If already tagged, refreshes the timestamp and attacker.
     *
     * @param store         the entity store
     * @param buffer        command buffer for safe component mutation
     * @param playerRef     ref to the player entity
     * @param attackerUuid  UUID of the opponent who triggered the tag
     * @param notify        whether to send the "in combat" message
     */
    public void tagPlayer(Store<EntityStore> store, CommandBuffer<EntityStore> buffer,
                          Ref<EntityStore> playerRef, UUID attackerUuid, boolean notify) {
        CombatTagComponent existing = store.getComponent(playerRef, CombatTagComponent.getComponentType());

        // Get the player UUID for the in-memory tracker
        PlayerRef pRef = store.getComponent(playerRef, PlayerRef.getComponentType());
        UUID playerUuid = pRef != null ? pRef.getUuid() : null;

        if (existing != null) {
            // Refresh -- update timestamp and attacker
            long now = System.currentTimeMillis();
            existing.setTagStartTime(now);
            existing.setAttackerUuid(attackerUuid);

            // Update in-memory tracker
            if (playerUuid != null) {
                recentTags.put(playerUuid, now);
            }
        } else {
            // New tag
            long now = System.currentTimeMillis();
            CombatTagComponent tag = new CombatTagComponent(now, attackerUuid);
            buffer.putComponent(playerRef, CombatTagComponent.getComponentType(), tag);

            // Update in-memory tracker
            if (playerUuid != null) {
                recentTags.put(playerUuid, now);
            }

            if (notify && pRef != null) {
                pRef.sendMessage(MSG_COMBAT_TAGGED);
            }
        }
    }

    /**
     * Checks whether a player entity currently has a combat tag (via ECS store).
     */
    public boolean isTagged(Store<EntityStore> store, Ref<EntityStore> playerRef) {
        CombatTagComponent tag = store.getComponent(playerRef, CombatTagComponent.getComponentType());
        if (tag == null) return false;

        // Also check if the tag has already expired (system may not have ticked yet)
        long elapsed = System.currentTimeMillis() - tag.getTagStartTime();
        return elapsed < (getTagDurationSeconds() * 1000L);
    }

    /**
     * Returns remaining tag time in milliseconds, or 0 if not tagged / expired.
     */
    public long getTagTimeRemainingMs(Store<EntityStore> store, Ref<EntityStore> playerRef) {
        CombatTagComponent tag = store.getComponent(playerRef, CombatTagComponent.getComponentType());
        if (tag == null) return 0;

        long elapsed = System.currentTimeMillis() - tag.getTagStartTime();
        long durationMs = getTagDurationSeconds() * 1000L;
        long remaining = durationMs - elapsed;
        return Math.max(remaining, 0);
    }

    // ─── In-memory tag tracking (for disconnect handler) ───

    /**
     * Checks if a player was recently tagged and the tag has NOT yet expired.
     * Safe to call from any thread (uses ConcurrentHashMap).
     */
    public boolean wasRecentlyTagged(UUID playerUuid) {
        Long tagTime = recentTags.get(playerUuid);
        if (tagTime == null) return false;

        long elapsed = System.currentTimeMillis() - tagTime;
        return elapsed < (getTagDurationSeconds() * 1000L);
    }

    /**
     * Clears the in-memory tag for a player. Called when:
     * - The ECS CombatTagSystem expires the tag naturally
     * - The disconnect penalty has been processed
     */
    public void clearRecentTag(UUID playerUuid) {
        recentTags.remove(playerUuid);
    }
}
