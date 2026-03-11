package com.hcfactions.chat;

import com.hcfactions.HC_FactionsPlugin;
import com.hcfactions.managers.FactionManager;
import com.hcfactions.managers.GuildManager;
import com.hcfactions.database.repositories.PlayerDataRepository;
import com.hcfactions.models.Faction;
import com.hcfactions.models.Guild;
import com.hcfactions.models.PlayerData;

import com.github.heroslender.herochat.data.User;
import com.github.heroslender.herochat.event.ChannelChatEvent;

import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.event.IEventRegistry;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import com.hypixel.hytale.server.core.permissions.PermissionsModule;

import java.awt.Color;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Formats chat messages with faction colors and guild tags.
 *
 * When HeroChat is installed, intercepts ChannelChatEvent and sends faction-styled
 * messages to all recipients. When HeroChat is not available, falls back to vanilla
 * PlayerChatEvent formatter.
 *
 * Format: [Channel][FactionName | GuildTag] PlayerName: message
 *         (faction name, guild tag, player name, and message all in faction color)
 */
public class FactionChatFormatter {

    // Short faction names for chat display (instead of full "Valorian Alliance" / "Iron Legion")
    private static final Map<String, String> CHAT_NAMES = Map.of(
        "alliance", "Valor",
        "horde", "Legion"
    );

    private final HC_FactionsPlugin plugin;

    public FactionChatFormatter(HC_FactionsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Register HeroChat ChannelChatEvent listener at LATE priority.
     * Runs after HeroChat's NORMAL handlers (spam, cooldown, capslock, user color).
     *
     * ChannelChatEvent implements IAsyncEvent<String>, so we use registerAsync with
     * a null String key (no key filtering).
     */
    public void registerHeroChat(IEventRegistry eventRegistry) {
        eventRegistry.registerAsync(EventPriority.LATE, ChannelChatEvent.class, (String) null, (futureEvent) -> {
            return futureEvent.thenApply(event -> {
                onChannelChat(event);
                return event;
            });
        });
    }

    /**
     * Register vanilla PlayerChatEvent formatter (fallback when HeroChat is absent).
     *
     * PlayerChatEvent implements IAsyncEvent<String>, so we use registerAsync with
     * a null String key (no key filtering).
     */
    public void registerVanilla(IEventRegistry eventRegistry) {
        eventRegistry.registerAsync(PlayerChatEvent.class, (String) null, (futureEvent) -> {
            return futureEvent.thenApply(event -> {
                onPlayerChat(event);
                return event;
            });
        });
    }

    private void onChannelChat(ChannelChatEvent event) {
        if (event.isCancelled()) return;

        User sender = event.getSender();
        UUID senderUuid = sender.getUuid();

        PlayerDataRepository playerDataRepo = plugin.getPlayerDataRepository();
        PlayerData playerData = playerDataRepo.getPlayerData(senderUuid);

        // No faction = let HeroChat handle normally
        if (playerData == null || !playerData.hasChosenFaction()) {
            return;
        }

        FactionManager factionManager = plugin.getFactionManager();
        Faction faction = factionManager.getFaction(playerData.getFactionId());
        if (faction == null) return;

        String channelName = event.getChannel().getName();
        String messageText = event.getMessage();
        String playerName = sender.getUsername();

        Message formatted = buildFactionMessage(channelName, faction, playerData, playerName, messageText);

        // Cancel HeroChat's default formatting
        event.setCancelled(true);

        // Send to all recipients
        for (User recipient : event.getRecipients()) {
            try {
                recipient.sendMessage(formatted);
            } catch (Exception e) {
                plugin.getLogger().at(Level.WARNING).log(
                    "[FactionChat] Failed to send to " + recipient.getUsername() + ": " + e.getMessage());
            }
        }

        // Ensure sender receives the message too (they may not be in recipients)
        boolean senderInRecipients = false;
        for (User recipient : event.getRecipients()) {
            if (recipient.getUuid().equals(senderUuid)) {
                senderInRecipients = true;
                break;
            }
        }
        if (!senderInRecipients) {
            try {
                sender.sendMessage(formatted);
            } catch (Exception e) {
                plugin.getLogger().at(Level.WARNING).log(
                    "[FactionChat] Failed to send to sender: " + e.getMessage());
            }
        }
    }

    private void onPlayerChat(PlayerChatEvent event) {
        if (event.isCancelled()) return;

        PlayerRef sender = event.getSender();
        UUID senderUuid = sender.getUuid();

        PlayerDataRepository playerDataRepo = plugin.getPlayerDataRepository();
        PlayerData playerData = playerDataRepo.getPlayerData(senderUuid);

        // No faction = use default formatter
        if (playerData == null || !playerData.hasChosenFaction()) {
            return;
        }

        FactionManager factionManager = plugin.getFactionManager();
        Faction faction = factionManager.getFaction(playerData.getFactionId());
        if (faction == null) return;

        // Set a custom formatter that includes faction/guild info
        event.setFormatter((playerRef, msg) ->
            buildFactionMessage("Global", faction, playerData, playerRef.getUsername(), msg)
        );
    }

    /**
     * Builds a formatted chat message with faction color and guild tag.
     *
     * Format: [channelName][FactionName | GuildTag] PlayerName: message
     *    or:  [channelName][FactionName] PlayerName: message  (no guild)
     */
    private Message buildFactionMessage(String channelName, Faction faction,
                                        PlayerData playerData, String playerName,
                                        String messageText) {
        // Operators get [ADMIN] tag with gradient between faction colors
        if (PermissionsModule.get().hasPermission(playerData.getPlayerUuid(), "*")) {
            List<Faction> factions = List.copyOf(plugin.getFactionManager().getFactions());
            Color c1 = factions.size() > 1 ? factions.get(1).getColor() : Color.WHITE;
            Color c2 = factions.size() > 0 ? factions.get(0).getColor() : c1;

            String text = "[ADMIN]";
            Message gradient = Message.empty();
            for (int i = 0; i < text.length(); i++) {
                float t = text.length() > 1 ? (float) i / (text.length() - 1) : 0f;
                int r = Math.round(c1.getRed() + t * (c2.getRed() - c1.getRed()));
                int g = Math.round(c1.getGreen() + t * (c2.getGreen() - c1.getGreen()));
                int b = Math.round(c1.getBlue() + t * (c2.getBlue() - c1.getBlue()));
                gradient = gradient.insert(
                    Message.raw(String.valueOf(text.charAt(i)))
                        .color(String.format("#%02X%02X%02X", r, g, b)).bold(true));
            }

            return Message.empty()
                .insert(Message.raw("[" + channelName + "]").color("#888888"))
                .insert(gradient)
                .insert(Message.raw(" " + playerName).color("#ffffff").bold(true))
                .insert(Message.raw(": ").color("#AAAAAA"))
                .insert(Message.raw(messageText).color("#ffffff"));
        }

        String factionName = CHAT_NAMES.getOrDefault(faction.getId(), faction.getDisplayName());
        String factionColor = faction.getColorHex();

        // Build tag with only the faction name colored, rest in white
        // Format: [Channel][FactionName | GuildTag] PlayerName: message
        //                   ^^^^^^^^^^^^ colored     rest is white
        Message tag;
        if (playerData.isInGuild()) {
            GuildManager guildManager = plugin.getGuildManager();
            Guild guild = guildManager.getPlayerGuild(playerData.getPlayerUuid());
            if (guild != null) {
                tag = Message.empty()
                    .insert(Message.raw("[").color("#AAAAAA"))
                    .insert(Message.raw(factionName).color(factionColor).bold(true))
                    .insert(Message.raw(" | " + guild.getDisplayTag() + "]").color("#AAAAAA"));
            } else {
                tag = Message.empty()
                    .insert(Message.raw("[").color("#AAAAAA"))
                    .insert(Message.raw(factionName).color(factionColor).bold(true))
                    .insert(Message.raw("]").color("#AAAAAA"));
            }
        } else {
            tag = Message.empty()
                .insert(Message.raw("[").color("#AAAAAA"))
                .insert(Message.raw(factionName).color(factionColor).bold(true))
                .insert(Message.raw("]").color("#AAAAAA"));
        }

        return Message.empty()
            .insert(Message.raw("[" + channelName + "]").color("#888888"))
            .insert(tag)
            .insert(Message.raw(" " + playerName).color("#ffffff").bold(true))
            .insert(Message.raw(": ").color("#AAAAAA"))
            .insert(Message.raw(messageText).color("#ffffff"));
    }
}
