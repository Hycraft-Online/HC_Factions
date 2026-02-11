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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Faction members list showing all players in the faction.
 */
public class FactionMembersGui extends InteractiveCustomUIPage<FactionMembersGui.MembersEventData> {

    private final HC_FactionsPlugin plugin;
    private final InteractiveCustomUIPage<?> parent;
    private final PlayerData playerData;
    private final Faction faction;

    public FactionMembersGui(@NonNullDecl HC_FactionsPlugin plugin, @NonNullDecl PlayerRef playerRef,
                             InteractiveCustomUIPage<?> parent) {
        super(playerRef, CustomPageLifetime.CanDismiss, MembersEventData.CODEC);
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
        cmd.append("Pages/FactionGuilds_FactionMembers.ui");

        if (faction == null) {
            cmd.set("#FactionNameLabel.Text", "No Faction");
            cmd.set("#MemberCountLabel.Text", "0 members");
            return;
        }

        // Set faction header
        cmd.set("#FactionNameLabel.TextSpans",
            Message.raw(faction.getDisplayName()).color(Color.decode(faction.getColorHex())));

        // Get all faction members
        List<UUID> memberUuids = plugin.getPlayerDataRepository().getFactionMembers(faction.getId());
        List<PlayerData> members = new ArrayList<>();

        for (UUID uuid : memberUuids) {
            PlayerData data = plugin.getPlayerDataRepository().getPlayerData(uuid);
            if (data != null) {
                members.add(data);
            }
        }

        // Sort members: by guild (leaders first), then by name
        members.sort((a, b) -> {
            // First sort by whether they're in a guild
            boolean aInGuild = a.isInGuild();
            boolean bInGuild = b.isInGuild();
            if (aInGuild != bInGuild) {
                return aInGuild ? -1 : 1;
            }

            // Then by role level (if in guild)
            if (aInGuild && bInGuild) {
                GuildRole roleA = a.getGuildRole();
                GuildRole roleB = b.getGuildRole();
                if (roleA != null && roleB != null) {
                    int roleCmp = Integer.compare(roleB.getLevel(), roleA.getLevel());
                    if (roleCmp != 0) return roleCmp;
                }
            }

            // Finally by name
            String nameA = a.getPlayerName() != null ? a.getPlayerName() : "";
            String nameB = b.getPlayerName() != null ? b.getPlayerName() : "";
            return nameA.compareToIgnoreCase(nameB);
        });

        // Set member count
        cmd.set("#MemberCountLabel.Text", members.size() + " members");

        // Clear and build member list
        cmd.clear("#MembersList");

        int index = 0;
        for (PlayerData member : members) {
            buildMemberRow(cmd, member, index);
            index++;
        }

        // Bind events
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BackButton",
            EventData.of("Action", "Back"), false);
    }

    private void buildMemberRow(UICommandBuilder cmd, PlayerData member, int index) {
        // Append member row template
        cmd.append("#MembersList", "Pages/FactionGuilds_FactionMemberRow.ui");
        String rowPrefix = "#MembersList[" + index + "]";

        // Set player name
        String playerName = member.getPlayerName() != null ? member.getPlayerName() : "Unknown";
        cmd.set(rowPrefix + " #PlayerName.Text", playerName);

        // Set guild info if member is in a guild
        if (member.isInGuild()) {
            Guild guild = plugin.getGuildManager().getGuild(member.getGuildId());
            if (guild != null) {
                cmd.set(rowPrefix + " #GuildName.Text", guild.getName());
            }

            GuildRole role = member.getGuildRole();
            if (role != null) {
                cmd.set(rowPrefix + " #RoleBadge.TextSpans",
                    Message.raw("[" + role.getDisplayName() + "]").color(getRoleColor(role)));
            }
        } else {
            cmd.set(rowPrefix + " #GuildName.TextSpans",
                Message.raw("No Guild").color(Color.decode("#757575")));
        }

        // Online indicator - gray for now (would need online tracking)
        cmd.set(rowPrefix + " #OnlineIndicator.Background", "#757575(1.0)");
    }

    private Color getRoleColor(GuildRole role) {
        return switch (role) {
            case LEADER -> Color.decode("#FFD700");
            case OFFICER -> Color.decode("#4FC3F7");
            case MEMBER -> Color.decode("#81C784");
            case RECRUIT -> Color.decode("#BDBDBD");
        };
    }

    @Override
    public void handleDataEvent(@NonNullDecl Ref<EntityStore> ref, @NonNullDecl Store<EntityStore> store,
                                @NonNullDecl MembersEventData data) {
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
        }
    }

    /**
     * Data class for UI events
     */
    public static class MembersEventData {
        public static final BuilderCodec<MembersEventData> CODEC = BuilderCodec.<MembersEventData>builder(
                MembersEventData.class, MembersEventData::new)
            .addField(new KeyedCodec<>("Action", Codec.STRING),
                (d, s) -> d.action = s, d -> d.action)
            .build();

        private String action;
    }
}
