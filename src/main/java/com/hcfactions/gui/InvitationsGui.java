package com.hcfactions.gui;

import com.hcfactions.HC_FactionsPlugin;
import com.hcfactions.models.Faction;
import com.hcfactions.models.Guild;
import com.hcfactions.models.GuildInvitation;
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
import java.util.List;
import java.util.UUID;

/**
 * Invitations panel with two tabs:
 * - Received Invites: guild invitations sent to this player
 * - Sent Requests: join requests this player has sent to guilds
 */
public class InvitationsGui extends InteractiveCustomUIPage<InvitationsGui.InvitationsEventData> {

    private final HC_FactionsPlugin plugin;
    private final InteractiveCustomUIPage<?> parent;
    private String currentTab = "received";

    public InvitationsGui(@NonNullDecl HC_FactionsPlugin plugin, @NonNullDecl PlayerRef playerRef,
                          InteractiveCustomUIPage<?> parent) {
        super(playerRef, CustomPageLifetime.CanDismiss, InvitationsEventData.CODEC);
        this.plugin = plugin;
        this.parent = parent;
    }

    @Override
    public void build(@NonNullDecl Ref<EntityStore> ref, @NonNullDecl UICommandBuilder cmd,
                     @NonNullDecl UIEventBuilder events, @NonNullDecl Store<EntityStore> store) {
        cmd.append("Pages/FactionGuilds_Invitations.ui");

        // Build both tabs but show only the active one
        buildReceivedTab(cmd, events);
        buildSentTab(cmd, events);

        // Set initial tab visibility
        applyTabVisibility(cmd);

        // Bind tab buttons
        events.addEventBinding(CustomUIEventBindingType.Activating, "#TabReceivedButton",
            EventData.of("Action", "TabReceived"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#TabSentButton",
            EventData.of("Action", "TabSent"), false);

        // Bind back/close button
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BackButton",
            EventData.of("Action", "Back"), false);
    }

    private void buildReceivedTab(UICommandBuilder cmd, UIEventBuilder events) {
        List<GuildInvitation> invitations = plugin.getGuildManager().getPendingInvitations(playerRef.getUuid());

        if (invitations.isEmpty()) {
            cmd.set("#InvitationsContainer.Visible", false);
            cmd.set("#NoInvitationsMessage.Visible", true);
            cmd.set("#InviteCountLabel.Text", "");
        } else {
            cmd.set("#InvitationsContainer.Visible", true);
            cmd.set("#NoInvitationsMessage.Visible", false);
            cmd.set("#InviteCountLabel.Text", invitations.size() + " pending");

            cmd.clear("#InvitationsList");

            int index = 0;
            for (GuildInvitation invitation : invitations) {
                buildInviteRow(cmd, events, invitation, index);
                index++;
            }
        }
    }

    private void buildInviteRow(UICommandBuilder cmd, UIEventBuilder events, GuildInvitation invitation, int index) {
        cmd.append("#InvitationsList", "Pages/FactionGuilds_InviteRow.ui");
        String rowPrefix = "#InvitationsList[" + index + "]";

        // Guild name with faction color
        Guild guild = plugin.getGuildManager().getGuild(invitation.getGuildId());
        if (guild != null) {
            Faction faction = plugin.getFactionManager().getFaction(guild.getFactionId());
            if (faction != null) {
                cmd.set(rowPrefix + " #GuildName.TextSpans",
                    Message.raw(guild.getName()).color(Color.decode(faction.getColorHex())));
            } else {
                cmd.set(rowPrefix + " #GuildName.Text", guild.getName());
            }
        } else {
            cmd.set(rowPrefix + " #GuildName.Text", "Unknown Guild");
        }

        // Inviter name
        String inviterName = invitation.getInviterName();
        cmd.set(rowPrefix + " #InviterName.Text",
            "Invited by: " + (inviterName != null ? inviterName : "Unknown"));

        // Relative timestamp
        if (invitation.getCreatedAt() != null) {
            cmd.set(rowPrefix + " #InviteTimestamp.Text", formatRelativeTime(invitation.getCreatedAt()));
        }

        // Bind accept/decline buttons
        String guildIdStr = invitation.getGuildId().toString();
        events.addEventBinding(CustomUIEventBindingType.Activating, rowPrefix + " #AcceptButton",
            EventData.of("Action", "Accept:" + guildIdStr), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, rowPrefix + " #DeclineButton",
            EventData.of("Action", "Decline:" + guildIdStr), false);
    }

    private void buildSentTab(UICommandBuilder cmd, UIEventBuilder events) {
        List<UUID> sentRequestGuildIds = plugin.getGuildManager().getPlayerJoinRequests(playerRef.getUuid());

        if (sentRequestGuildIds.isEmpty()) {
            cmd.set("#RequestsContainer.Visible", false);
            cmd.set("#NoRequestsMessage.Visible", true);
            cmd.set("#RequestCountLabel.Text", "");
        } else {
            cmd.set("#RequestsContainer.Visible", true);
            cmd.set("#NoRequestsMessage.Visible", false);
            cmd.set("#RequestCountLabel.Text", sentRequestGuildIds.size() + " pending");

            cmd.clear("#RequestsList");

            int index = 0;
            for (UUID guildId : sentRequestGuildIds) {
                buildSentRequestRow(cmd, events, guildId, index);
                index++;
            }
        }
    }

    private void buildSentRequestRow(UICommandBuilder cmd, UIEventBuilder events, UUID guildId, int index) {
        // Reuse a simple inline row for sent requests (player-facing: guild name + cancel button)
        String rowContent = "Group { Anchor: (Height: 50, Bottom: 6); Background: #1a2a3a(0.9); " +
            "LayoutMode: Left; Padding: (Left: 14, Right: 14, Top: 10, Bottom: 10); " +
            "Label #SentGuildName { Text: \"Guild\"; FlexWeight: 1; " +
            "Style: (FontSize: 14, TextColor: #ffffff, RenderBold: true, VerticalAlignment: Center); } " +
            "Label #SentStatus { Text: \"Pending\"; Anchor: (Width: 80); " +
            "Style: (FontSize: 12, TextColor: #FFAA00, RenderBold: true, HorizontalAlignment: Center, VerticalAlignment: Center); } " +
            "Group { Anchor: (Width: 8); } " +
            "TextButton #CancelRequestButton { Text: \"CANCEL\"; Anchor: (Width: 80, Height: 28); " +
            "Style: TextButtonStyle(" +
            "Default: (Background: #8b3a3a, LabelStyle: (FontSize: 11, TextColor: #ffffff, RenderBold: true, HorizontalAlignment: Center, VerticalAlignment: Center)), " +
            "Hovered: (Background: #9b4a4a, LabelStyle: (FontSize: 11, TextColor: #ffffff, RenderBold: true, HorizontalAlignment: Center, VerticalAlignment: Center)), " +
            "Pressed: (Background: #7b2a2a, LabelStyle: (FontSize: 11, TextColor: #ffffff, RenderBold: true, HorizontalAlignment: Center, VerticalAlignment: Center))); } }";

        cmd.appendInline("#RequestsList", rowContent);
        String rowPrefix = "#RequestsList[" + index + "]";

        // Set guild name with faction color
        Guild guild = plugin.getGuildManager().getGuild(guildId);
        if (guild != null) {
            Faction faction = plugin.getFactionManager().getFaction(guild.getFactionId());
            if (faction != null) {
                cmd.set(rowPrefix + " #SentGuildName.TextSpans",
                    Message.raw(guild.getName()).color(Color.decode(faction.getColorHex())));
            } else {
                cmd.set(rowPrefix + " #SentGuildName.Text", guild.getName());
            }
        } else {
            cmd.set(rowPrefix + " #SentGuildName.Text", "Unknown Guild");
        }

        // Bind cancel button
        events.addEventBinding(CustomUIEventBindingType.Activating, rowPrefix + " #CancelRequestButton",
            EventData.of("Action", "CancelRequest:" + guildId.toString()), false);
    }

    private void applyTabVisibility(UICommandBuilder cmd) {
        if ("received".equals(currentTab)) {
            cmd.set("#ReceivedTab.Visible", true);
            cmd.set("#SentTab.Visible", false);
        } else {
            cmd.set("#ReceivedTab.Visible", false);
            cmd.set("#SentTab.Visible", true);
        }
    }

    @Override
    public void handleDataEvent(@NonNullDecl Ref<EntityStore> ref, @NonNullDecl Store<EntityStore> store,
                                @NonNullDecl InvitationsEventData data) {
        super.handleDataEvent(ref, store, data);

        if (data.action == null) return;

        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) return;

        switch (data.action) {
            case "Back" -> {
                if (parent != null) {
                    player.getPageManager().openCustomPage(ref, store, parent);
                } else {
                    this.close();
                }
            }
            case "TabReceived" -> {
                this.currentTab = "received";
                this.rebuild();
            }
            case "TabSent" -> {
                this.currentTab = "sent";
                this.rebuild();
            }
            default -> {
                // Handle invite actions
                if (data.action.startsWith("Accept:") || data.action.startsWith("Decline:")) {
                    handleInviteAction(data.action);
                } else if (data.action.startsWith("CancelRequest:")) {
                    handleCancelRequest(data.action);
                }
            }
        }
    }

    private void handleInviteAction(String action) {
        String[] parts = action.split(":", 2);
        if (parts.length != 2) return;

        String actionType = parts[0];
        UUID guildId;
        try {
            guildId = UUID.fromString(parts[1]);
        } catch (IllegalArgumentException e) {
            return;
        }

        if (actionType.equals("Accept")) {
            boolean success = plugin.getGuildManager().joinGuild(guildId, playerRef.getUuid());
            if (success) {
                Guild guild = plugin.getGuildManager().getGuild(guildId);
                String guildName = guild != null ? guild.getName() : "the guild";
                playerRef.sendMessage(Message.raw("You have joined " + guildName + "!").color(Color.GREEN));
            } else {
                playerRef.sendMessage(Message.raw("Failed to join guild. It may be full or you're already in a guild.").color(Color.RED));
            }
            this.close();
        } else if (actionType.equals("Decline")) {
            plugin.getGuildManager().removeInvitation(guildId, playerRef.getUuid());
            playerRef.sendMessage(Message.raw("Invitation declined.").color(Color.YELLOW));
            this.rebuild();
        }
    }

    private void handleCancelRequest(String action) {
        String guildIdStr = action.substring("CancelRequest:".length());
        UUID guildId;
        try {
            guildId = UUID.fromString(guildIdStr);
        } catch (IllegalArgumentException e) {
            return;
        }

        plugin.getGuildManager().declineJoinRequest(guildId, playerRef.getUuid());
        Guild guild = plugin.getGuildManager().getGuild(guildId);
        String guildName = guild != null ? guild.getName() : "the guild";
        playerRef.sendMessage(Message.raw("Cancelled join request to " + guildName + ".").color(Color.YELLOW));
        this.rebuild();
    }

    /**
     * Formats an Instant as a relative time string (e.g., "2h ago", "3d ago").
     */
    private static String formatRelativeTime(Instant timestamp) {
        Duration elapsed = Duration.between(timestamp, Instant.now());
        long minutes = elapsed.toMinutes();
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + "m ago";
        long hours = elapsed.toHours();
        if (hours < 24) return hours + "h ago";
        long days = elapsed.toDays();
        if (days < 30) return days + "d ago";
        return days / 30 + "mo ago";
    }

    /**
     * Data class for UI events
     */
    public static class InvitationsEventData {
        public static final BuilderCodec<InvitationsEventData> CODEC = BuilderCodec.<InvitationsEventData>builder(
                InvitationsEventData.class, InvitationsEventData::new)
            .addField(new KeyedCodec<>("Action", Codec.STRING),
                (d, s) -> d.action = s, d -> d.action)
            .build();

        private String action;
    }
}
