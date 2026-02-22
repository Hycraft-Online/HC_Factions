package com.hcfactions.gui;

import com.hcfactions.HC_FactionsPlugin;
import com.hcfactions.managers.ClaimManager;
import com.hcfactions.models.Faction;
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
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import java.awt.Color;
import java.util.List;
import java.util.UUID;

/**
 * Main menu hub for faction/guild features.
 * Provides navigation to all player-facing GUIs.
 */
public class FactionMenuGui extends InteractiveCustomUIPage<FactionMenuGui.MenuEventData> {

    private final HC_FactionsPlugin plugin;
    private final PlayerData playerData;
    private final Guild guild;
    private final Faction faction;
    private String pendingGuildName = "";
    private String pendingGuildTag = "";

    public FactionMenuGui(@NonNullDecl HC_FactionsPlugin plugin, @NonNullDecl PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismiss, MenuEventData.CODEC);
        this.plugin = plugin;
        
        // Load player data
        this.playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
        
        // Load guild if player is in one
        if (playerData != null && playerData.isInGuild()) {
            this.guild = plugin.getGuildManager().getGuild(playerData.getGuildId());
        } else {
            this.guild = null;
        }
        
        // Load faction
        if (playerData != null && playerData.getFactionId() != null) {
            this.faction = plugin.getFactionManager().getFaction(playerData.getFactionId());
        } else {
            this.faction = null;
        }
    }

    @Override
    public void build(@NonNullDecl Ref<EntityStore> ref, @NonNullDecl UICommandBuilder cmd, 
                     @NonNullDecl UIEventBuilder events, @NonNullDecl Store<EntityStore> store) {
        cmd.append("Pages/FactionGuilds_Menu.ui");

        // Set player info
        if (faction != null) {
            cmd.set("#FactionTag.TextSpans",
                Message.raw("[" + faction.getShortName() + "]").color(Color.decode(faction.getColorHex())));
        } else {
            cmd.set("#FactionTag.Text", "[???]");
        }
        
        cmd.set("#PlayerName.Text", playerRef.getUsername());

        // Set guild info
        if (guild != null) {
            cmd.set("#GuildName.Text", "Guild: " + guild.getName());
            
            // Set power
            cmd.set("#PowerValue.Text", String.valueOf(guild.getPower()));
            
            // Set rank
            GuildRole role = playerData.getGuildRole();
            if (role != null) {
                cmd.set("#RankValue.TextSpans",
                    Message.raw(role.getDisplayName()).color(getRoleColor(role)));
            }
            
            // Show/hide buttons based on guild status
            cmd.set("#MyGuildButton.Visible", true);
            cmd.set("#CreateGuildSection.Visible", false);

            // Show manage members only for officers+
            if (role != null && role.hasAtLeast(GuildRole.OFFICER)) {
                cmd.set("#ManageMembersButton.Visible", true);
            } else {
                cmd.set("#ManageMembersButton.Visible", false);
            }

            // Land claims visible for all guild members
            cmd.set("#LandClaimsButton.Visible", true);

            // Show leave button (hidden for leaders)
            if (role != GuildRole.LEADER) {
                cmd.set("#LeaveGuildSection.Visible", true);
            } else {
                cmd.set("#LeaveGuildSection.Visible", false);
            }
        } else {
            cmd.set("#GuildName.Text", "No Guild - Create or join one!");
            cmd.set("#PowerValue.Text", "0");
            cmd.set("#RankValue.Text", "None");

            // Hide guild-specific buttons
            cmd.set("#MyGuildButton.Visible", false);
            cmd.set("#ManageMembersButton.Visible", false);
            cmd.set("#LeaveGuildSection.Visible", false);

            // Land claims still available for solo players
            cmd.set("#LandClaimsButton.Visible", faction != null);

            // Show create guild section
            cmd.set("#CreateGuildSection.Visible", true);
        }

        // Set invitation badge count
        int inviteCount = plugin.getGuildManager().getPendingInvitationCount(playerRef.getUuid());
        if (inviteCount > 0) {
            cmd.set("#InviteBadge.Visible", true);
            cmd.set("#InviteCount.Text", String.valueOf(inviteCount));
        } else {
            cmd.set("#InviteBadge.Visible", false);
        }

        // Bind sidebar navigation events
        events.addEventBinding(CustomUIEventBindingType.Activating, "#MyGuildButton",
            EventData.of("Action", "OpenGuildInfo"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ManageMembersButton",
            EventData.of("Action", "OpenGuildManagement"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BrowseGuildsButton",
            EventData.of("Action", "OpenGuildBrowser"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FactionOverviewButton",
            EventData.of("Action", "OpenFactionOverview"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#LandClaimsButton",
            EventData.of("Action", "OpenLandClaims"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#InvitationsButton",
            EventData.of("Action", "OpenInvitations"), false);

        // Bind guild name and tag inputs
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#GuildNameInput",
            EventData.of("@GuildNameInput", "#GuildNameInput.Value"), false);
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#GuildTagInput",
            EventData.of("@GuildTagInput", "#GuildTagInput.Value"), false);

        // Bind action button events
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CreateGuildButton",
            EventData.of("Action", "CreateGuild"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#LeaveGuildButton",
            EventData.of("Action", "LeaveGuild"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
            EventData.of("Action", "Close"), false);
    }

    @Override
    public void handleDataEvent(@NonNullDecl Ref<EntityStore> ref, @NonNullDecl Store<EntityStore> store,
                                @NonNullDecl MenuEventData data) {
        super.handleDataEvent(ref, store, data);

        // Track guild name and tag inputs
        if (data.guildNameInput != null) {
            this.pendingGuildName = data.guildNameInput;
        }
        if (data.guildTagInput != null) {
            this.pendingGuildTag = data.guildTagInput;
        }

        if (data.action == null) return;

        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) return;

        switch (data.action) {
            case "OpenGuildInfo" -> {
                if (guild != null) {
                    player.getPageManager().openCustomPage(ref, store, 
                        new GuildInfoGui(plugin, playerRef, this));
                }
            }
            case "OpenGuildManagement" -> {
                if (guild != null) {
                    player.getPageManager().openCustomPage(ref, store, 
                        new GuildManagementGui(plugin, playerRef, this));
                }
            }
            case "OpenGuildBrowser" -> {
                player.getPageManager().openCustomPage(ref, store, 
                    new GuildBrowserGui(plugin, playerRef, this));
            }
            case "OpenFactionOverview" -> {
                player.getPageManager().openCustomPage(ref, store, 
                    new FactionOverviewGui(plugin, playerRef, this));
            }
            case "OpenInvitations" -> {
                player.getPageManager().openCustomPage(ref, store,
                    new InvitationsGui(plugin, playerRef, this));
            }
            case "OpenLandClaims" -> {
                if (faction != null) {
                    TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
                    if (transform != null) {
                        String worldName = store.getExternalData().getWorld().getName();
                        int chunkX = ClaimManager.toChunkCoord((int) transform.getPosition().getX());
                        int chunkZ = ClaimManager.toChunkCoord((int) transform.getPosition().getZ());
                        if (guild != null) {
                            player.getPageManager().openCustomPage(ref, store,
                                new GuildClaimGui(plugin, playerRef, guild.getId(), faction.getId(), worldName, chunkX, chunkZ));
                        } else {
                            player.getPageManager().openCustomPage(ref, store,
                                GuildClaimGui.forSoloPlayer(plugin, playerRef, faction.getId(), worldName, chunkX, chunkZ));
                        }
                    }
                }
            }
            case "CreateGuild" -> {
                String guildName = this.pendingGuildName;
                if (guildName == null || guildName.trim().isEmpty()) {
                    playerRef.sendMessage(Message.raw("Please enter a guild name.").color(Color.RED));
                    return;
                }

                guildName = guildName.trim();

                // Validate tag if provided
                String guildTag = this.pendingGuildTag != null ? this.pendingGuildTag.trim().toUpperCase() : null;
                if (guildTag != null && !guildTag.isEmpty()) {
                    if (guildTag.length() > 4) {
                        playerRef.sendMessage(Message.raw("Guild tag must be 1-4 letters.").color(Color.RED));
                        return;
                    }
                    if (!guildTag.matches("[A-Z]+")) {
                        playerRef.sendMessage(Message.raw("Guild tag must contain only letters (A-Z).").color(Color.RED));
                        return;
                    }
                } else {
                    guildTag = null; // Treat empty as no tag
                }

                // Check if player is in a faction
                if (playerData == null || playerData.getFactionId() == null) {
                    playerRef.sendMessage(Message.raw("You must choose a faction before creating a guild.").color(Color.RED));
                    return;
                }

                // Check if already in a guild
                if (playerData.isInGuild()) {
                    playerRef.sendMessage(Message.raw("You are already in a guild.").color(Color.RED));
                    return;
                }

                // Try to create the guild
                Guild newGuild = plugin.getGuildManager().createGuild(guildName, playerRef.getUuid(), playerData.getFactionId(), guildTag);
                if (newGuild != null) {
                    playerRef.sendMessage(Message.raw("Guild '" + guildName + "' created successfully!").color(Color.GREEN));
                    this.close();
                } else {
                    playerRef.sendMessage(Message.raw("Failed to create guild. The name may be taken or invalid.").color(Color.RED));
                }
            }
            case "LeaveGuild" -> {
                // Leave guild and refresh
                if (guild != null && playerData != null) {
                    boolean success = plugin.getGuildManager().leaveGuild(playerRef.getUuid());
                    if (success) {
                        playerRef.sendMessage(Message.raw("You have left " + guild.getName() + ".").color(Color.YELLOW));
                    } else {
                        playerRef.sendMessage(Message.raw("Failed to leave guild.").color(Color.RED));
                    }
                    this.close();
                }
            }
            case "Close" -> this.close();
        }
    }

    private Color getRoleColor(GuildRole role) {
        return switch (role) {
            case LEADER -> Color.decode("#FFD700");
            case OFFICER -> Color.decode("#4FC3F7");
            case SENIOR -> Color.decode("#FFA726");
            case MEMBER -> Color.decode("#81C784");
            case RECRUIT -> Color.decode("#BDBDBD");
        };
    }

    /**
     * Data class for UI events
     */
    public static class MenuEventData {
        public static final BuilderCodec<MenuEventData> CODEC = BuilderCodec.<MenuEventData>builder(
                MenuEventData.class, MenuEventData::new)
            .addField(new KeyedCodec<>("Action", Codec.STRING),
                (d, s) -> d.action = s, d -> d.action)
            .addField(new KeyedCodec<>("@GuildNameInput", Codec.STRING),
                (d, s) -> d.guildNameInput = s, d -> d.guildNameInput)
            .addField(new KeyedCodec<>("@GuildTagInput", Codec.STRING),
                (d, s) -> d.guildTagInput = s, d -> d.guildTagInput)
            .build();

        private String action;
        private String guildNameInput;
        private String guildTagInput;
    }
}
