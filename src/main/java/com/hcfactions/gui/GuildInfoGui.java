package com.hcfactions.gui;

import com.hcfactions.HC_FactionsPlugin;
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
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import java.awt.Color;
import java.util.List;
import java.util.UUID;

/**
 * Guild info panel showing detailed guild statistics.
 */
public class GuildInfoGui extends InteractiveCustomUIPage<GuildInfoGui.GuildInfoEventData> {

    private final HC_FactionsPlugin plugin;
    private final InteractiveCustomUIPage<?> parent;
    private final Guild guild;
    private final Faction faction;

    public GuildInfoGui(@NonNullDecl HC_FactionsPlugin plugin, @NonNullDecl PlayerRef playerRef,
                        InteractiveCustomUIPage<?> parent) {
        super(playerRef, CustomPageLifetime.CanDismiss, GuildInfoEventData.CODEC);
        this.plugin = plugin;
        this.parent = parent;
        
        // Load player's guild
        PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
        if (playerData != null && playerData.isInGuild()) {
            this.guild = plugin.getGuildManager().getGuild(playerData.getGuildId());
            if (this.guild != null) {
                this.faction = plugin.getFactionManager().getFaction(this.guild.getFactionId());
            } else {
                this.faction = null;
            }
        } else {
            this.guild = null;
            this.faction = null;
        }
    }

    @Override
    public void build(@NonNullDecl Ref<EntityStore> ref, @NonNullDecl UICommandBuilder cmd, 
                     @NonNullDecl UIEventBuilder events, @NonNullDecl Store<EntityStore> store) {
        cmd.append("Pages/FactionGuilds_GuildInfo.ui");

        if (guild == null) {
            cmd.set("#GuildNameLabel.Text", "No Guild");
            cmd.set("#FactionLabel.Text", "You are not in a guild");
            return;
        }

        // Set guild header
        cmd.set("#GuildNameLabel.Text", guild.getName());
        if (faction != null) {
            cmd.set("#FactionLabel.TextSpans",
                Message.raw("Faction: " + faction.getDisplayName()).color(Color.decode(faction.getColorHex())));
        }

        // Set statistics (stat card headers handle the labels)
        int memberCount = plugin.getGuildManager().getMemberCount(guild.getId());
        int maxMembers = plugin.getConfig().getGuildMaxMembers();
        cmd.set("#MembersValue.Text", memberCount + " / " + maxMembers);

        cmd.set("#PowerValue.Text", String.valueOf(guild.getPower()));

        int claimCount = plugin.getClaimManager().getClaimCount(guild.getId());
        int maxClaims = plugin.getClaimManager().getMaxClaims(guild.getId());
        int powerPerClaim = plugin.getClaimManager().getPowerPerClaim();
        int effectiveMax = powerPerClaim > 0 ? Math.min(maxClaims, guild.getPower() / powerPerClaim) : maxClaims;
        cmd.set("#ClaimsValue.Text", claimCount + " / " + effectiveMax);

        // Set guild home (label prefix is in the .ui file)
        if (guild.getHomeWorld() != null) {
            cmd.set("#HomeLocation.Text", String.format("%s @ %.0f, %.0f, %.0f",
                guild.getHomeWorld(), guild.getHomeX(), guild.getHomeY(), guild.getHomeZ()));
        } else {
            cmd.set("#HomeLocation.Text", "Not set");
        }

        // Set top members preview (by role)
        List<PlayerData> members = plugin.getGuildManager().getGuildMembers(guild.getId());
        // Sort by role (leaders first)
        members.sort((a, b) -> {
            GuildRole roleA = a.getGuildRole();
            GuildRole roleB = b.getGuildRole();
            if (roleA == null) return 1;
            if (roleB == null) return -1;
            int cmp = Integer.compare(roleB.getLevel(), roleA.getLevel());
            if (cmp != 0) return cmp;
            // Tiebreak by name for deterministic ordering
            String nameA = a.getPlayerName() != null ? a.getPlayerName() : "";
            String nameB = b.getPlayerName() != null ? b.getPlayerName() : "";
            return nameA.compareToIgnoreCase(nameB);
        });

        int maxLeadership = 5; // leader + up to 4 officers
        int memberIndex = 0;
        for (PlayerData member : members) {
            if (memberIndex >= maxLeadership) break;

            GuildRole role = member.getGuildRole();
            String rolePrefix = role != null ? "[" + role.getDisplayName() + "] " : "";
            Color roleColor = getRoleColor(role);
            cmd.set("#OnlineMember" + (memberIndex + 1) + ".TextSpans",
                Message.raw(rolePrefix + member.getPlayerName()).color(roleColor));
            cmd.set("#OnlineMember" + (memberIndex + 1) + ".Visible", true);
            memberIndex++;
        }

        // Hide unused member slots
        for (int i = memberIndex; i < maxLeadership; i++) {
            if (memberIndex == 0 && i == 0) {
                cmd.set("#OnlineMember1.Text", "No members yet");
            } else {
                cmd.set("#OnlineMember" + (i + 1) + ".Visible", false);
            }
        }

        // Bind events
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ViewMembersButton",
            EventData.of("Action", "ViewMembers"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BackButton",
            EventData.of("Action", "Back"), false);
    }

    @Override
    public void handleDataEvent(@NonNullDecl Ref<EntityStore> ref, @NonNullDecl Store<EntityStore> store, 
                                @NonNullDecl GuildInfoEventData data) {
        super.handleDataEvent(ref, store, data);

        if (data.action == null) return;

        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) return;

        switch (data.action) {
            case "ViewMembers" -> {
                player.getPageManager().openCustomPage(ref, store, 
                    new GuildManagementGui(plugin, playerRef, parent));
            }
            case "Back" -> {
                if (parent != null) {
                    player.getPageManager().openCustomPage(ref, store, parent);
                } else {
                    this.close();
                }
            }
        }
    }

    private Color getRoleColor(GuildRole role) {
        if (role == null) return Color.decode("#AAAAAA");
        return switch (role) {
            case LEADER -> Color.decode("#FFD700");
            case OFFICER -> Color.decode("#87CEEB");
            case SENIOR -> Color.decode("#FFA726");
            case MEMBER -> Color.decode("#81C784");
            case RECRUIT -> Color.decode("#BDBDBD");
        };
    }

    /**
     * Data class for UI events
     */
    public static class GuildInfoEventData {
        public static final BuilderCodec<GuildInfoEventData> CODEC = BuilderCodec.<GuildInfoEventData>builder(
                GuildInfoEventData.class, GuildInfoEventData::new)
            .addField(new KeyedCodec<>("Action", Codec.STRING),
                (d, s) -> d.action = s, d -> d.action)
            .build();

        private String action;
    }
}
