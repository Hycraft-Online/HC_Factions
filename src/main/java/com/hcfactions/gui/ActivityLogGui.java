package com.hcfactions.gui;

import com.hcfactions.HC_FactionsPlugin;
import com.hcfactions.managers.GuildLogEntry;
import com.hcfactions.managers.GuildLogType;
import com.hcfactions.models.Guild;
import com.hcfactions.models.PlayerData;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Activity log page showing guild event history.
 * Displays color-coded log entries with timestamps and actor names.
 * Capped at 25 entries per page to stay within the 256 UI node limit.
 */
public class ActivityLogGui extends InteractiveCustomUIPage<ActivityLogGui.ActivityLogEventData> {

    private static final int MAX_ENTRIES = 25;
    private static final DateTimeFormatter TIME_FORMAT =
        DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault());

    private final HC_FactionsPlugin plugin;
    private final InteractiveCustomUIPage<?> parent;
    private final Guild guild;

    public ActivityLogGui(@NonNullDecl HC_FactionsPlugin plugin, @NonNullDecl PlayerRef playerRef,
                          InteractiveCustomUIPage<?> parent) {
        super(playerRef, CustomPageLifetime.CanDismiss, ActivityLogEventData.CODEC);
        this.plugin = plugin;
        this.parent = parent;

        // Load player's guild
        PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
        if (playerData != null && playerData.isInGuild()) {
            this.guild = plugin.getGuildManager().getGuild(playerData.getGuildId());
        } else {
            this.guild = null;
        }
    }

    @Override
    public void build(@NonNullDecl Ref<EntityStore> ref, @NonNullDecl UICommandBuilder cmd,
                     @NonNullDecl UIEventBuilder events, @NonNullDecl Store<EntityStore> store) {
        cmd.append("Pages/FactionGuilds_ActivityLog.ui");

        if (guild == null) {
            cmd.set("#NoLogsMessage.Text", "You are not in a guild.");
            cmd.set("#NoLogsMessage.Visible", true);
            cmd.set("#LogContainer.Visible", false);
        } else {
            // Fetch recent logs
            List<GuildLogEntry> entries = plugin.getGuildLogManager().getRecentLogs(guild.getId(), MAX_ENTRIES);

            if (entries.isEmpty()) {
                cmd.set("#NoLogsMessage.Visible", true);
                cmd.set("#LogContainer.Visible", false);
                cmd.set("#EntryCountLabel.Text", "");
            } else {
                cmd.set("#NoLogsMessage.Visible", false);
                cmd.set("#LogContainer.Visible", true);
                cmd.set("#EntryCountLabel.Text", entries.size() + " entries");

                cmd.clear("#LogList");

                int index = 0;
                for (GuildLogEntry entry : entries) {
                    buildLogRow(cmd, entry, index);
                    index++;
                }
            }
        }

        // Bind back/close button
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BackButton",
            EventData.of("Action", "Back"), false);
    }

    private void buildLogRow(UICommandBuilder cmd, GuildLogEntry entry, int index) {
        cmd.append("#LogList", "Pages/FactionGuilds_LogRow.ui");
        String rowPrefix = "#LogList[" + index + "]";

        // Timestamp
        if (entry.getCreatedAt() != null) {
            cmd.set(rowPrefix + " #LogTimestamp.Text", formatTimestamp(entry.getCreatedAt()));
        }

        // Color indicator dot based on event type
        Color dotColor = getEventColor(entry.getEventType());
        cmd.set(rowPrefix + " #LogDot.Background", colorToHex(dotColor));

        // Actor name
        String actorName = resolvePlayerName(entry.getActorUuid());
        cmd.set(rowPrefix + " #LogActor.TextSpans",
            Message.raw(actorName).color(dotColor));

        // Event description
        String description = formatEventDescription(entry);
        cmd.set(rowPrefix + " #LogDescription.TextSpans",
            Message.raw(description).color(Color.decode("#96a9be")));
    }

    /**
     * Resolves a player UUID to a display name.
     */
    private String resolvePlayerName(UUID playerUuid) {
        if (playerUuid == null) return "System";
        PlayerData data = plugin.getPlayerDataRepository().getPlayerData(playerUuid);
        if (data != null && data.getPlayerName() != null) {
            return data.getPlayerName();
        }
        return playerUuid.toString().substring(0, 8);
    }

    /**
     * Formats a log entry's event type into a human-readable description.
     */
    private String formatEventDescription(GuildLogEntry entry) {
        GuildLogType type = entry.getEventType();
        if (type == null) {
            return entry.getDetails() != null ? entry.getDetails() : "unknown event";
        }

        String targetName = resolvePlayerName(entry.getTargetUuid());
        String details = entry.getDetails();

        return switch (type) {
            case MEMBER_JOIN -> "joined the guild";
            case MEMBER_LEAVE -> "left the guild";
            case MEMBER_KICK -> "kicked " + targetName;
            case MEMBER_PROMOTE -> "promoted " + targetName +
                (details != null ? " to " + details : "");
            case MEMBER_DEMOTE -> "demoted " + targetName +
                (details != null ? " to " + details : "");
            case CHUNK_CLAIM -> "claimed a chunk" +
                (details != null ? " at " + details : "");
            case CHUNK_UNCLAIM -> "unclaimed a chunk" +
                (details != null ? " at " + details : "");
            case HOME_SET -> "set guild home" +
                (details != null ? " at " + details : "");
            case PERMISSION_CHANGE -> "changed permissions" +
                (details != null ? " - " + details : "");
            case GUILD_CREATE -> "created the guild";
            case BANK_DEPOSIT -> "deposited" +
                (details != null ? " " + details : "");
            case BANK_WITHDRAW -> "withdrew" +
                (details != null ? " " + details : "");
            case CLAIM_DECAY -> "claim decayed" +
                (details != null ? " - " + details : "");
        };
    }

    /**
     * Returns a color for each event type category.
     */
    private static Color getEventColor(GuildLogType type) {
        if (type == null) return Color.decode("#96a9be");

        return switch (type) {
            // Green: positive membership
            case MEMBER_JOIN, GUILD_CREATE -> Color.decode("#44cc44");
            // Red: negative membership
            case MEMBER_LEAVE, MEMBER_KICK -> Color.decode("#ff5555");
            // Blue: territory
            case CHUNK_CLAIM, CHUNK_UNCLAIM -> Color.decode("#4FC3F7");
            // Yellow: role changes
            case MEMBER_PROMOTE, MEMBER_DEMOTE -> Color.decode("#FFAA00");
            // Gray: settings/misc
            case HOME_SET, PERMISSION_CHANGE -> Color.decode("#96a9be");
            // Orange: economy
            case BANK_DEPOSIT, BANK_WITHDRAW -> Color.decode("#FFA726");
            // Dark red: decay
            case CLAIM_DECAY -> Color.decode("#CC3344");
        };
    }

    /**
     * Formats an Instant into a compact timestamp string.
     */
    private static String formatTimestamp(Instant timestamp) {
        Duration elapsed = Duration.between(timestamp, Instant.now());
        long minutes = elapsed.toMinutes();
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + "m ago";
        long hours = elapsed.toHours();
        if (hours < 24) return hours + "h ago";
        long days = elapsed.toDays();
        if (days < 7) return days + "d ago";
        // For older entries, show the date
        return TIME_FORMAT.format(timestamp);
    }

    /**
     * Converts a Color to a hex string like "#rrggbb".
     */
    private static String colorToHex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    @Override
    public void handleDataEvent(@NonNullDecl Ref<EntityStore> ref, @NonNullDecl Store<EntityStore> store,
                                @NonNullDecl ActivityLogEventData data) {
        super.handleDataEvent(ref, store, data);

        if (data.action == null) return;

        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) return;

        if ("Back".equals(data.action)) {
            if (parent != null) {
                player.getPageManager().openCustomPage(ref, store, parent);
            } else {
                this.close();
            }
        }
    }

    /**
     * Data class for UI events
     */
    public static class ActivityLogEventData {
        public static final BuilderCodec<ActivityLogEventData> CODEC = BuilderCodec.<ActivityLogEventData>builder(
                ActivityLogEventData.class, ActivityLogEventData::new)
            .addField(new KeyedCodec<>("Action", Codec.STRING),
                (d, s) -> d.action = s, d -> d.action)
            .build();

        private String action;
    }
}
