package com.hcfactions.nameplates;

import com.hcfactions.HC_FactionsPlugin;
import com.hcfactions.models.Faction;
import com.hcfactions.models.Guild;
import com.hcfactions.models.GuildRole;
import com.hcfactions.models.PlayerData;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.DisplayNameComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.awt.Color;
import java.util.UUID;

/**
 * Manages player nameplates (the text displayed above player heads).
 * Shows guild tags and faction colors.
 *
 * Format examples:
 * - Guild member: "[GuildTag] PlayerName" in faction color
 * - Solo player with faction: "PlayerName" in faction color
 * - No faction: "PlayerName" in white
 */
public class NameplateManager {

    private static NameplateManager instance;

    private final HC_FactionsPlugin plugin;

    public NameplateManager(HC_FactionsPlugin plugin) {
        this.plugin = plugin;
        instance = this;
    }

    public static NameplateManager getInstance() {
        return instance;
    }

    /**
     * Updates a player's nameplate based on their faction and guild status.
     * Must be called from the world thread.
     *
     * @param player The player entity
     * @param playerRef The player reference
     * @param store The entity store
     * @param ref The entity reference
     */
    public void updateNameplate(Player player, PlayerRef playerRef, Store<EntityStore> store, Ref<EntityStore> ref) {
        UUID playerUuid = playerRef.getUuid();
        String username = playerRef.getUsername();

        PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerUuid);

        String nameplateText;
        Color nameplateColor;

        if (playerData == null || !playerData.hasChosenFaction()) {
            // No faction - plain white name
            nameplateText = username;
            nameplateColor = Color.WHITE;
        } else if (playerData.isInGuild()) {
            // In a guild - show guild tag
            Guild guild = plugin.getGuildManager().getGuild(playerData.getGuildId());
            Faction faction = plugin.getFactionManager().getFaction(playerData.getFactionId());

            // Use custom tag if set, otherwise truncated name
            String guildTag = guild != null ? guild.getDisplayTag() : "???";

            nameplateText = "[" + guildTag + "] " + username;
            nameplateColor = faction != null ? faction.getColor() : Color.WHITE;
        } else {
            // Has faction but no guild - show just username with faction color
            Faction faction = plugin.getFactionManager().getFaction(playerData.getFactionId());

            nameplateText = username;
            nameplateColor = faction != null ? faction.getColor() : Color.WHITE;
        }

        // Create display name with level postfix (HC_Leveling is optional)
        Message displayMessage = Message.raw(nameplateText).color(nameplateColor);

        try {
            Class<?> levelingApi = Class.forName("com.hcleveling.api.HC_LevelingAPI");
            java.lang.reflect.Method getLevel = levelingApi.getMethod("getPlayerLevel", UUID.class);
            int playerLevel = (int) getLevel.invoke(null, playerUuid);
            displayMessage.insert(Message.raw(" [Lv." + playerLevel + "]").color(Color.GRAY));
        } catch (Exception ignored) {
            // HC_Leveling not available - skip level display
        }

        DisplayNameComponent displayNameComp = new DisplayNameComponent(displayMessage);
        store.putComponent(ref, DisplayNameComponent.getComponentType(), displayNameComp);
    }

    /**
     * Updates a player's nameplate when they connect or their status changes.
     * Handles getting the entity reference and executing on the world thread.
     *
     * @param playerRef The player reference
     * @param world The world the player is in
     */
    public void updateNameplateAsync(PlayerRef playerRef, World world) {
        world.execute(() -> {
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref == null || !ref.isValid()) {
                return;
            }

            Store<EntityStore> store = ref.getStore();
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null) {
                return;
            }

            updateNameplate(player, playerRef, store, ref);
        });
    }

    /**
     * Updates nameplates for all online players.
     * Useful when guild/faction data changes.
     */
    public void updateAllNameplates() {
        Universe universe = Universe.get();
        if (universe == null) return;
        for (PlayerRef playerRef : universe.getPlayers()) {
            if (playerRef == null) continue;
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref == null || !ref.isValid()) continue;
            World world = ref.getStore().getExternalData().getWorld();
            if (world != null) {
                updateNameplateAsync(playerRef, world);
            }
        }
    }

    /**
     * Updates nameplate for a specific player by UUID.
     * Finds the player across all worlds.
     *
     * @param playerUuid The player's UUID
     */
    public void updateNameplateForPlayer(UUID playerUuid) {
        Universe universe = Universe.get();
        if (universe == null) return;
        PlayerRef playerRef = universe.getPlayer(playerUuid);
        if (playerRef == null) return;
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) return;
        World world = ref.getStore().getExternalData().getWorld();
        if (world != null) {
            updateNameplateAsync(playerRef, world);
        }
    }
}
