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
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Faction overview showing faction stats and guild leaderboard.
 */
public class FactionOverviewGui extends InteractiveCustomUIPage<FactionOverviewGui.OverviewEventData> {

    private final HC_FactionsPlugin plugin;
    private final InteractiveCustomUIPage<?> parent;
    private final PlayerData playerData;
    private final Faction faction;

    public FactionOverviewGui(@NonNullDecl HC_FactionsPlugin plugin, @NonNullDecl PlayerRef playerRef,
                              InteractiveCustomUIPage<?> parent) {
        super(playerRef, CustomPageLifetime.CanDismiss, OverviewEventData.CODEC);
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
        cmd.append("Pages/FactionGuilds_FactionOverview.ui");

        if (faction == null) {
            cmd.set("#FactionName.Text", "No Faction");
            cmd.set("#FactionMotto.Text", "You must choose a faction first!");
            return;
        }

        // Set faction header
        cmd.set("#FactionName.TextSpans",
            Message.raw(faction.getDisplayName()).color(Color.decode(faction.getColorHex())));

        // Set motto based on faction
        String motto = getMotto(faction.getId());
        cmd.set("#FactionMotto.Text", "\"" + motto + "\"");

        // Get faction statistics
        List<UUID> factionMembers = plugin.getPlayerDataRepository().getFactionMembers(faction.getId());
        List<Guild> factionGuilds = plugin.getGuildRepository().getGuildsByFaction(faction.getId());
        
        cmd.set("#MembersValue.Text", "Members: " + factionMembers.size());
        cmd.set("#GuildsValue.Text", "Guilds: " + factionGuilds.size());

        // Count territory claims for this faction
        int totalClaims = 0;
        for (Guild guild : factionGuilds) {
            totalClaims += plugin.getClaimManager().getClaimCount(guild.getId());
        }
        // Also count direct faction claims
        totalClaims += plugin.getClaimManager().getFactionOnlyClaims(faction.getId()).size();
        cmd.set("#TerritoryValue.Text", "Territory: " + totalClaims + " claims");

        // Build guild leaderboard (sorted by power)
        List<Guild> topGuilds = factionGuilds.stream()
            .sorted(Comparator.comparingInt(Guild::getPower).reversed())
            .limit(5)
            .toList();

        for (int i = 0; i < 5; i++) {
            int rank = i + 1;
            if (i < topGuilds.size()) {
                Guild guild = topGuilds.get(i);
                cmd.set("#Name" + rank + ".Text", guild.getName());
                cmd.set("#Power" + rank + ".Text", guild.getPower() + " power");
                cmd.set("#LeaderEntry" + rank + ".Visible", true);
            } else {
                cmd.set("#LeaderEntry" + rank + ".Visible", false);
            }
        }

        // Bind events
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ViewMembersButton",
            EventData.of("Action", "ViewMembers"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BackButton",
            EventData.of("Action", "Back"), false);
    }

    private String getMotto(String factionId) {
        return switch (factionId.toLowerCase()) {
            case "alliance" -> "Honor. Justice. Brotherhood.";
            case "horde" -> "Strength. Industry. Dominion.";
            default -> "Unity in purpose.";
        };
    }

    @Override
    public void handleDataEvent(@NonNullDecl Ref<EntityStore> ref, @NonNullDecl Store<EntityStore> store, 
                                @NonNullDecl OverviewEventData data) {
        super.handleDataEvent(ref, store, data);

        if (data.action == null) return;

        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) return;

        switch (data.action) {
            case "ViewMembers" -> {
                player.getPageManager().openCustomPage(ref, store,
                    new FactionMembersGui(plugin, playerRef, this));
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

    /**
     * Data class for UI events
     */
    public static class OverviewEventData {
        public static final BuilderCodec<OverviewEventData> CODEC = BuilderCodec.<OverviewEventData>builder(
                OverviewEventData.class, OverviewEventData::new)
            .addField(new KeyedCodec<>("Action", Codec.STRING),
                (d, s) -> d.action = s, d -> d.action)
            .build();

        private String action;
    }
}
