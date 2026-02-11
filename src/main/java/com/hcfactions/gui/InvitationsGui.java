package com.hcfactions.gui;

import com.hcfactions.HC_FactionsPlugin;
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
import java.util.List;
import java.util.UUID;

/**
 * Invitations panel for viewing and managing pending guild invites.
 */
public class InvitationsGui extends InteractiveCustomUIPage<InvitationsGui.InvitationsEventData> {

    private final HC_FactionsPlugin plugin;
    private final InteractiveCustomUIPage<?> parent;

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

        // Get pending invitations for this player
        List<GuildInvitation> invitations = plugin.getGuildManager().getPendingInvitations(playerRef.getUuid());

        if (invitations.isEmpty()) {
            cmd.set("#InvitationsContainer.Visible", false);
            cmd.set("#NoInvitationsMessage.Visible", true);
        } else {
            cmd.set("#InvitationsContainer.Visible", true);
            cmd.set("#NoInvitationsMessage.Visible", false);

            // Clear and build invitation list
            cmd.clear("#InvitationsList");

            int index = 0;
            for (GuildInvitation invitation : invitations) {
                buildInviteRow(cmd, events, invitation, index);
                index++;
            }
        }

        // Bind back button
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BackButton",
            EventData.of("Action", "Back"), false);
    }

    private void buildInviteRow(UICommandBuilder cmd, UIEventBuilder events, GuildInvitation invitation, int index) {
        // Append invite row template
        cmd.append("#InvitationsList", "Pages/FactionGuilds_InviteRow.ui");
        String rowPrefix = "#InvitationsList[" + index + "]";

        // Get guild info
        Guild guild = plugin.getGuildManager().getGuild(invitation.getGuildId());
        if (guild != null) {
            cmd.set(rowPrefix + " #GuildName.Text", guild.getName());
        } else {
            cmd.set(rowPrefix + " #GuildName.Text", "Unknown Guild");
        }

        // Set inviter info
        String inviterName = invitation.getInviterName();
        if (inviterName != null) {
            cmd.set(rowPrefix + " #InviterName.Text", "Invited by: " + inviterName);
        } else {
            cmd.set(rowPrefix + " #InviterName.Text", "Invited by: Unknown");
        }

        // Bind accept/decline buttons
        String guildIdStr = invitation.getGuildId().toString();
        events.addEventBinding(CustomUIEventBindingType.Activating, rowPrefix + " #AcceptButton",
            EventData.of("Action", "Accept:" + guildIdStr), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, rowPrefix + " #DeclineButton",
            EventData.of("Action", "Decline:" + guildIdStr), false);
    }

    @Override
    public void handleDataEvent(@NonNullDecl Ref<EntityStore> ref, @NonNullDecl Store<EntityStore> store, 
                                @NonNullDecl InvitationsEventData data) {
        super.handleDataEvent(ref, store, data);

        if (data.action == null) return;

        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) return;

        if (data.action.equals("Back")) {
            if (parent != null) {
                player.getPageManager().openCustomPage(ref, store, parent);
            } else {
                this.close();
            }
            return;
        }

        // Handle Accept:guildId and Decline:guildId
        if (data.action.startsWith("Accept:") || data.action.startsWith("Decline:")) {
            String[] parts = data.action.split(":", 2);
            if (parts.length != 2) return;

            String actionType = parts[0];
            UUID guildId;
            try {
                guildId = UUID.fromString(parts[1]);
            } catch (IllegalArgumentException e) {
                return;
            }

            if (actionType.equals("Accept")) {
                // Try to join the guild
                boolean success = plugin.getGuildManager().joinGuild(guildId, playerRef.getUuid());
                if (success) {
                    Guild guild = plugin.getGuildManager().getGuild(guildId);
                    String guildName = guild != null ? guild.getName() : "the guild";
                    playerRef.sendMessage(Message.raw("You have joined " + guildName + "!").color(Color.GREEN));
                } else {
                    playerRef.sendMessage(Message.raw("Failed to join guild. It may be full or you're already in a guild.").color(Color.RED));
                }
                
                // Close and return to menu after joining
                this.close();
            } else if (actionType.equals("Decline")) {
                // Remove the invitation
                plugin.getGuildManager().removeInvitation(guildId, playerRef.getUuid());
                playerRef.sendMessage(Message.raw("Invitation declined.").color(Color.YELLOW));
                
                // Refresh the GUI
                this.rebuild();
            }
        }
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
