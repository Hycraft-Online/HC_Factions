package com.hcfactions.gui;

import com.hcfactions.HC_FactionsPlugin;
import com.hcfactions.models.Guild;
import com.hcfactions.models.GuildRole;
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
 * Guild management panel for viewing and managing members.
 */
public class GuildManagementGui extends InteractiveCustomUIPage<GuildManagementGui.ManagementEventData> {

    private final HC_FactionsPlugin plugin;
    private final InteractiveCustomUIPage<?> parent;
    private final Guild guild;
    private final PlayerData currentPlayerData;
    private final GuildRole currentPlayerRole;
    private String pendingInviteName = "";

    public GuildManagementGui(@NonNullDecl HC_FactionsPlugin plugin, @NonNullDecl PlayerRef playerRef,
                              InteractiveCustomUIPage<?> parent) {
        super(playerRef, CustomPageLifetime.CanDismiss, ManagementEventData.CODEC);
        this.plugin = plugin;
        this.parent = parent;
        
        // Load current player's data
        this.currentPlayerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
        
        if (currentPlayerData != null && currentPlayerData.isInGuild()) {
            this.guild = plugin.getGuildManager().getGuild(currentPlayerData.getGuildId());
            this.currentPlayerRole = currentPlayerData.getGuildRole();
        } else {
            this.guild = null;
            this.currentPlayerRole = null;
        }
    }

    @Override
    public void build(@NonNullDecl Ref<EntityStore> ref, @NonNullDecl UICommandBuilder cmd, 
                     @NonNullDecl UIEventBuilder events, @NonNullDecl Store<EntityStore> store) {
        cmd.append("Pages/FactionGuilds_GuildManagement.ui");

        if (guild == null) {
            cmd.set("#GuildNameLabel.Text", "No Guild");
            cmd.set("#MemberCountLabel.Text", "");
            return;
        }

        // Set guild header
        cmd.set("#GuildNameLabel.Text", guild.getName());

        // Get members
        List<PlayerData> members = plugin.getGuildManager().getGuildMembers(guild.getId());
        cmd.set("#MemberCountLabel.Text", members.size() + " members");

        // Clear and build member list
        cmd.clear("#MembersList");

        int index = 0;
        for (PlayerData member : members) {
            buildMemberRow(cmd, events, member, index);
            index++;
        }

        // Show/hide leader actions
        if (currentPlayerRole == GuildRole.LEADER) {
            cmd.set("#TransferLeadershipButton.Visible", true);
            cmd.set("#DisbandGuildButton.Visible", true);
        } else {
            cmd.set("#TransferLeadershipButton.Visible", false);
            cmd.set("#DisbandGuildButton.Visible", false);
        }

        // Show invite section only for Officers and Leaders
        boolean canInvite = currentPlayerRole != null && currentPlayerRole.hasAtLeast(GuildRole.OFFICER);
        cmd.set("#InviteSection.Visible", canInvite);

        // Show join requests for Officers and Leaders
        if (canInvite) {
            List<UUID> joinRequests = plugin.getGuildManager().getGuildJoinRequests(guild.getId());
            if (!joinRequests.isEmpty()) {
                cmd.set("#JoinRequestsSection.Visible", true);
                cmd.set("#JoinRequestCount.Text", "(" + joinRequests.size() + " pending)");
                cmd.clear("#JoinRequestsList");

                int requestIndex = 0;
                for (UUID requestPlayerUuid : joinRequests) {
                    buildJoinRequestRow(cmd, events, requestPlayerUuid, requestIndex);
                    requestIndex++;
                }
            } else {
                cmd.set("#JoinRequestsSection.Visible", false);
            }
        } else {
            cmd.set("#JoinRequestsSection.Visible", false);
        }

        // Bind invite events
        if (canInvite) {
            // Track text field changes
            events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#InvitePlayerName",
                EventData.of("@InvitePlayerName", "#InvitePlayerName.Value"), false);
            // Button click
            events.addEventBinding(CustomUIEventBindingType.Activating, "#InviteButton",
                EventData.of("Action", "InvitePlayer"), false);
        }

        // Bind main action events
        events.addEventBinding(CustomUIEventBindingType.Activating, "#TransferLeadershipButton",
            EventData.of("Action", "TransferLeadership"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#DisbandGuildButton",
            EventData.of("Action", "DisbandGuild"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BackButton",
            EventData.of("Action", "Back"), false);
    }

    private void buildJoinRequestRow(UICommandBuilder cmd, UIEventBuilder events, UUID requestPlayerUuid, int index) {
        // Append join request row template
        cmd.append("#JoinRequestsList", "Pages/FactionGuilds_JoinRequestRow.ui");
        String rowPrefix = "#JoinRequestsList[" + index + "]";

        // Get player data for the requester
        PlayerData requesterData = plugin.getPlayerDataRepository().getPlayerData(requestPlayerUuid);
        String playerName = requesterData != null ? requesterData.getPlayerName() : "Unknown Player";
        cmd.set(rowPrefix + " #PlayerName.Text", playerName);

        // Bind accept button
        events.addEventBinding(CustomUIEventBindingType.Activating, rowPrefix + " #AcceptButton",
            EventData.of("Action", "AcceptRequest:" + requestPlayerUuid.toString()), false);

        // Bind decline button
        events.addEventBinding(CustomUIEventBindingType.Activating, rowPrefix + " #DeclineButton",
            EventData.of("Action", "DeclineRequest:" + requestPlayerUuid.toString()), false);
    }

    private void buildMemberRow(UICommandBuilder cmd, UIEventBuilder events, PlayerData member, int index) {
        // Append member row template
        cmd.append("#MembersList", "Pages/FactionGuilds_MemberRow.ui");
        String rowPrefix = "#MembersList[" + index + "]";

        // Set player name
        cmd.set(rowPrefix + " #PlayerName.Text", member.getPlayerName());

        // Set role badge
        GuildRole role = member.getGuildRole();
        if (role != null) {
            cmd.set(rowPrefix + " #RoleBadge.TextSpans",
                Message.raw("[" + role.getDisplayName() + "]").color(getRoleColor(role)));
        }

        // Set online indicator - simplified (would need world access for accurate status)
        // For now, just show gray - the full implementation would track online status
        cmd.set(rowPrefix + " #OnlineIndicator.Background", "#757575(1.0)"); // Gray - status unknown

        // Determine if current player can manage this member
        boolean canManage = currentPlayerRole != null && 
                           role != null && 
                           currentPlayerRole.canManage(role) &&
                           !member.getPlayerUuid().equals(playerRef.getUuid()); // Can't manage self

        if (canManage) {
            // Show action buttons
            cmd.set(rowPrefix + " #ActionButtons.Visible", true);

            // Bind promote button (can't promote to leader, that's transfer)
            if (role != GuildRole.OFFICER) { // Can promote up to officer
                cmd.set(rowPrefix + " #PromoteButton.Visible", true);
                events.addEventBinding(CustomUIEventBindingType.Activating, rowPrefix + " #PromoteButton",
                    EventData.of("Action", "Promote:" + member.getPlayerUuid().toString()), false);
            } else {
                cmd.set(rowPrefix + " #PromoteButton.Visible", false);
            }

            // Bind demote button (can't demote recruits)
            if (role != GuildRole.RECRUIT) {
                cmd.set(rowPrefix + " #DemoteButton.Visible", true);
                events.addEventBinding(CustomUIEventBindingType.Activating, rowPrefix + " #DemoteButton",
                    EventData.of("Action", "Demote:" + member.getPlayerUuid().toString()), false);
            } else {
                cmd.set(rowPrefix + " #DemoteButton.Visible", false);
            }

            // Bind kick button
            events.addEventBinding(CustomUIEventBindingType.Activating, rowPrefix + " #KickButton",
                EventData.of("Action", "Kick:" + member.getPlayerUuid().toString()), false);
        } else {
            // Hide action buttons for self or non-manageable members
            cmd.set(rowPrefix + " #ActionButtons.Visible", false);
        }
    }

    @Override
    public void handleDataEvent(@NonNullDecl Ref<EntityStore> ref, @NonNullDecl Store<EntityStore> store,
                                @NonNullDecl ManagementEventData data) {
        super.handleDataEvent(ref, store, data);

        // Track invite name changes
        if (data.invitePlayerName != null) {
            this.pendingInviteName = data.invitePlayerName;
        }

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

        if (data.action.equals("TransferLeadership")) {
            // Close and prompt for transfer via command
            this.close();
            playerRef.sendMessage(Message.raw("To transfer leadership, use: /guild promote <player>").color(Color.YELLOW));
            playerRef.sendMessage(Message.raw("Promoting an Officer to Leader will transfer ownership.").color(Color.GRAY));
            return;
        }

        if (data.action.equals("DisbandGuild")) {
            // Close and prompt for disband via command
            this.close();
            playerRef.sendMessage(Message.raw("To disband your guild, use: /guild disband").color(Color.YELLOW));
            playerRef.sendMessage(Message.raw("WARNING: This action is permanent!").color(Color.RED));
            return;
        }

        if (data.action.equals("InvitePlayer")) {
            String targetName = this.pendingInviteName;
            if (targetName == null || targetName.trim().isEmpty()) {
                playerRef.sendMessage(Message.raw("Please enter a player name to invite.").color(Color.RED));
                return;
            }

            targetName = targetName.trim();

            // Try to find the player by name
            PlayerData targetData = plugin.getPlayerDataRepository().getPlayerDataByName(targetName);
            if (targetData == null) {
                playerRef.sendMessage(Message.raw("Player '" + targetName + "' not found.").color(Color.RED));
                return;
            }

            // Check if they're already in a guild
            if (targetData.isInGuild()) {
                playerRef.sendMessage(Message.raw(targetName + " is already in a guild.").color(Color.RED));
                return;
            }

            // Check if they're in the same faction
            if (!guild.getFactionId().equals(targetData.getFactionId())) {
                playerRef.sendMessage(Message.raw(targetName + " is not in your faction.").color(Color.RED));
                return;
            }

            // Send the invitation
            boolean success = plugin.getGuildManager().invitePlayer(guild.getId(), targetData.getPlayerUuid());
            if (success) {
                playerRef.sendMessage(Message.raw("Invitation sent to " + targetName + "!").color(Color.GREEN));
                // Clear the pending name
                this.pendingInviteName = "";
            } else {
                playerRef.sendMessage(Message.raw("Failed to invite " + targetName + ". They may already have an invitation.").color(Color.RED));
            }
            return;
        }

        // Handle join request actions
        if (data.action.startsWith("AcceptRequest:") || data.action.startsWith("DeclineRequest:")) {
            String[] parts = data.action.split(":", 2);
            if (parts.length != 2) return;

            String actionType = parts[0];
            UUID targetUuid;
            try {
                targetUuid = UUID.fromString(parts[1]);
            } catch (IllegalArgumentException e) {
                return;
            }

            // Get player name for feedback
            PlayerData targetData = plugin.getPlayerDataRepository().getPlayerData(targetUuid);
            String targetName = targetData != null ? targetData.getPlayerName() : "player";

            if (actionType.equals("AcceptRequest")) {
                boolean success = plugin.getGuildManager().acceptJoinRequest(guild.getId(), targetUuid);
                if (success) {
                    playerRef.sendMessage(Message.raw(targetName + " has joined the guild!").color(Color.GREEN));
                } else {
                    playerRef.sendMessage(Message.raw("Failed to accept join request from " + targetName + ".").color(Color.RED));
                }
            } else {
                plugin.getGuildManager().declineJoinRequest(guild.getId(), targetUuid);
                playerRef.sendMessage(Message.raw("Declined join request from " + targetName + ".").color(Color.YELLOW));
            }

            // Refresh the GUI
            this.rebuild();
            return;
        }

        // Handle member actions (Promote:uuid, Demote:uuid, Kick:uuid)
        if (data.action.startsWith("Promote:") || data.action.startsWith("Demote:") || data.action.startsWith("Kick:")) {
            String[] parts = data.action.split(":", 2);
            if (parts.length != 2) return;

            String actionType = parts[0];
            UUID targetUuid;
            try {
                targetUuid = UUID.fromString(parts[1]);
            } catch (IllegalArgumentException e) {
                return;
            }

            // Get target player name for feedback
            PlayerData targetData = plugin.getPlayerDataRepository().getPlayerData(targetUuid);
            String targetName = targetData != null ? targetData.getPlayerName() : "player";

            boolean success;
            String message;
            switch (actionType) {
                case "Promote" -> {
                    success = plugin.getGuildManager().promotePlayer(guild.getId(), targetUuid, playerRef.getUuid());
                    message = success ? targetName + " has been promoted!" : "Failed to promote " + targetName;
                }
                case "Demote" -> {
                    success = plugin.getGuildManager().demotePlayer(guild.getId(), targetUuid, playerRef.getUuid());
                    message = success ? targetName + " has been demoted." : "Failed to demote " + targetName;
                }
                case "Kick" -> {
                    success = plugin.getGuildManager().kickPlayer(guild.getId(), targetUuid, playerRef.getUuid());
                    message = success ? targetName + " has been kicked from the guild." : "Failed to kick " + targetName;
                }
                default -> { return; }
            }

            playerRef.sendMessage(Message.raw(message).color(success ? Color.YELLOW : Color.RED));

            // Refresh the GUI
            this.rebuild();
        }
    }

    private Color getRoleColor(GuildRole role) {
        return switch (role) {
            case LEADER -> Color.decode("#FFD700");
            case OFFICER -> Color.decode("#4FC3F7");
            case MEMBER -> Color.decode("#81C784");
            case RECRUIT -> Color.decode("#BDBDBD");
        };
    }

    /**
     * Data class for UI events
     */
    public static class ManagementEventData {
        public static final BuilderCodec<ManagementEventData> CODEC = BuilderCodec.<ManagementEventData>builder(
                ManagementEventData.class, ManagementEventData::new)
            .addField(new KeyedCodec<>("Action", Codec.STRING),
                (d, s) -> d.action = s, d -> d.action)
            .addField(new KeyedCodec<>("@InvitePlayerName", Codec.STRING),
                (d, s) -> d.invitePlayerName = s, d -> d.invitePlayerName)
            .build();

        private String action;
        private String invitePlayerName;
    }
}
