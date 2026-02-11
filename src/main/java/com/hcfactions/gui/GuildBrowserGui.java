package com.hcfactions.gui;

import com.hcfactions.HC_FactionsPlugin;
import com.hcfactions.models.Faction;
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
import java.util.List;
import java.util.UUID;

/**
 * Guild browser for viewing and joining guilds in your faction.
 */
public class GuildBrowserGui extends InteractiveCustomUIPage<GuildBrowserGui.BrowserEventData> {

    private final HC_FactionsPlugin plugin;
    private final InteractiveCustomUIPage<?> parent;
    private final PlayerData playerData;
    private final Faction faction;

    public GuildBrowserGui(@NonNullDecl HC_FactionsPlugin plugin, @NonNullDecl PlayerRef playerRef,
                           InteractiveCustomUIPage<?> parent) {
        super(playerRef, CustomPageLifetime.CanDismiss, BrowserEventData.CODEC);
        this.plugin = plugin;
        this.parent = parent;
        
        this.playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
        
        if (playerData != null && playerData.getFactionId() != null) {
            this.faction = plugin.getFactionManager().getFaction(playerData.getFactionId());
        } else {
            this.faction = null;
        }
    }

    @Override
    public void build(@NonNullDecl Ref<EntityStore> ref, @NonNullDecl UICommandBuilder cmd, 
                     @NonNullDecl UIEventBuilder events, @NonNullDecl Store<EntityStore> store) {
        cmd.append("Pages/FactionGuilds_GuildBrowser.ui");

        if (faction == null) {
            cmd.set("#FactionHeader.Text", "You must choose a faction first!");
            cmd.set("#GuildsContainer.Visible", false);
            cmd.set("#CreateGuildButton.Visible", false);
            return;
        }

        // Set faction header
        cmd.set("#FactionHeader.TextSpans",
            Message.raw("Guilds in " + faction.getDisplayName()).color(Color.decode(faction.getColorHex())));

        // Get guilds in this faction
        List<Guild> guilds = plugin.getGuildRepository().getGuildsByFaction(faction.getId());

        if (guilds.isEmpty()) {
            cmd.set("#GuildsContainer.Visible", false);
            cmd.set("#NoGuildsMessage.Visible", true);
        } else {
            cmd.set("#GuildsContainer.Visible", true);
            cmd.set("#NoGuildsMessage.Visible", false);

            // Clear and build guild list
            cmd.clear("#GuildsList");

            int index = 0;
            for (Guild guild : guilds) {
                buildGuildCard(cmd, events, guild, index);
                index++;
            }
        }

        // Show create guild button only if not in a guild
        if (playerData != null && !playerData.isInGuild()) {
            cmd.set("#CreateGuildButton.Visible", true);
        } else {
            cmd.set("#CreateGuildButton.Visible", false);
        }

        // Bind events
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CreateGuildButton",
            EventData.of("Action", "CreateGuild"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BackButton",
            EventData.of("Action", "Back"), false);
    }

    private void buildGuildCard(UICommandBuilder cmd, UIEventBuilder events, Guild guild, int index) {
        // Append guild card template
        cmd.append("#GuildsList", "Pages/FactionGuilds_GuildCard.ui");
        String cardPrefix = "#GuildsList[" + index + "]";

        // Set guild name
        cmd.set(cardPrefix + " #GuildName.Text", guild.getName());

        // Set member count
        int memberCount = plugin.getGuildManager().getMemberCount(guild.getId());
        cmd.set(cardPrefix + " #MembersLabel.Text", "Members: " + memberCount);

        // Set power
        cmd.set(cardPrefix + " #PowerLabel.Text", "Power: " + guild.getPower());

        // Show/hide request join button based on player's guild status
        if (playerData != null && !playerData.isInGuild()) {
            // Check if player already has an invitation
            if (plugin.getGuildManager().hasInvitation(guild.getId(), playerRef.getUuid())) {
                cmd.set(cardPrefix + " #RequestJoinButton.Text", "Invited!");
                cmd.set(cardPrefix + " #RequestJoinButton.Visible", true);
                // Don't bind an event - player should use invitations page
            } else if (plugin.getGuildManager().hasJoinRequest(guild.getId(), playerRef.getUuid())) {
                // Already has a pending request
                cmd.set(cardPrefix + " #RequestJoinButton.Text", "Requested");
                cmd.set(cardPrefix + " #RequestJoinButton.Visible", true);
                // Don't bind an event - already requested
            } else {
                // Bind request join event
                cmd.set(cardPrefix + " #RequestJoinButton.Visible", true);
                events.addEventBinding(CustomUIEventBindingType.Activating, cardPrefix + " #RequestJoinButton",
                    EventData.of("Action", "RequestJoin:" + guild.getId().toString()), false);
            }
        } else {
            // Already in a guild, hide button
            cmd.set(cardPrefix + " #RequestJoinButton.Visible", false);
        }
    }

    @Override
    public void handleDataEvent(@NonNullDecl Ref<EntityStore> ref, @NonNullDecl Store<EntityStore> store, 
                                @NonNullDecl BrowserEventData data) {
        super.handleDataEvent(ref, store, data);

        if (data.action == null) return;

        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) return;

        switch (data.action) {
            case "CreateGuild" -> {
                this.close();
                playerRef.sendMessage(Message.raw("To create a guild, use: /guild create <name>").color(Color.YELLOW));
            }
            case "Back" -> {
                if (parent != null) {
                    player.getPageManager().openCustomPage(ref, store, parent);
                } else {
                    this.close();
                }
            }
            default -> {
                // Handle RequestJoin:guildId
                if (data.action.startsWith("RequestJoin:")) {
                    String guildIdStr = data.action.substring("RequestJoin:".length());
                    try {
                        UUID guildId = UUID.fromString(guildIdStr);
                        Guild guild = plugin.getGuildManager().getGuild(guildId);

                        if (guild != null) {
                            boolean success = plugin.getGuildManager().createJoinRequest(guildId, playerRef.getUuid());
                            if (success) {
                                playerRef.sendMessage(Message.raw("Join request sent to " + guild.getName() + "!").color(Color.GREEN));
                                playerRef.sendMessage(Message.raw("Guild officers will review your request.").color(Color.GRAY));
                                // Refresh the page to show updated button state
                                player.getPageManager().openCustomPage(ref, store,
                                    new GuildBrowserGui(plugin, playerRef, parent));
                            } else {
                                playerRef.sendMessage(Message.raw("Could not send join request. You may already have one pending.").color(Color.RED));
                            }
                        }
                    } catch (IllegalArgumentException e) {
                        // Invalid UUID, ignore
                    }
                }
            }
        }
    }

    /**
     * Data class for UI events
     */
    public static class BrowserEventData {
        public static final BuilderCodec<BrowserEventData> CODEC = BuilderCodec.<BrowserEventData>builder(
                BrowserEventData.class, BrowserEventData::new)
            .addField(new KeyedCodec<>("Action", Codec.STRING),
                (d, s) -> d.action = s, d -> d.action)
            .build();

        private String action;
    }
}
