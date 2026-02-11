package com.hcfactions.gui;

import com.hcfactions.HC_FactionsPlugin;
import com.hcfactions.events.FactionSelectEvent;
import com.hcfactions.models.Faction;
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
import com.hypixel.hytale.server.core.util.EventTitleUtil;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.HytaleServer;
import com.starterarea.managers.StarterAreaManager;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import java.awt.Color;
import java.util.List;
import java.util.UUID;

/**
 * Faction selection GUI shown to players who haven't chosen a faction,
 * or as a confirmation screen for existing players completing the tutorial.
 */
public class FactionSelectionGui extends InteractiveCustomUIPage<FactionSelectionGui.FactionSelectionData> {

    private final HC_FactionsPlugin plugin;
    private final Runnable onFactionSelected;

    /** If non-null, player already has this faction and this is just a confirmation */
    private final String existingFactionId;

    public FactionSelectionGui(@NonNullDecl HC_FactionsPlugin plugin, @NonNullDecl PlayerRef playerRef) {
        this(plugin, playerRef, null);
    }

    public FactionSelectionGui(@NonNullDecl HC_FactionsPlugin plugin, @NonNullDecl PlayerRef playerRef, Runnable onFactionSelected) {
        super(playerRef, CustomPageLifetime.CanDismiss, FactionSelectionData.CODEC);
        this.plugin = plugin;
        this.onFactionSelected = onFactionSelected;

        // Check if player already has a faction (for tutorial completion confirmation)
        PlayerData existingData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
        this.existingFactionId = (existingData != null && existingData.hasChosenFaction())
            ? existingData.getFactionId()
            : null;
    }

    @Override
    public void handleDataEvent(@NonNullDecl Ref<EntityStore> ref, @NonNullDecl Store<EntityStore> store, @NonNullDecl FactionSelectionData data) {
        super.handleDataEvent(ref, store, data);

        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) return;

        String selectedFaction = null;

        if (data.joinAlliance != null) {
            selectedFaction = "alliance";
        } else if (data.joinHorde != null) {
            selectedFaction = "horde";
        }

        if (selectedFaction != null) {
            // If this is a confirmation, verify they clicked their own faction
            if (existingFactionId != null && !existingFactionId.equalsIgnoreCase(selectedFaction)) {
                // Clicked wrong faction button (shouldn't happen if UI is set up correctly)
                return;
            }

            Faction faction = plugin.getFactionManager().getFaction(selectedFaction);
            if (faction != null) {
                boolean isConfirmation = (existingFactionId != null);

                if (!isConfirmation) {
                    // New faction selection - save the choice
                    PlayerData playerData = plugin.getPlayerDataRepository().getOrCreatePlayerData(playerRef.getUuid());
                    playerData.setPlayerName(playerRef.getUsername());
                    playerData.setFactionId(selectedFaction);
                    plugin.getPlayerDataRepository().savePlayerData(playerData);

                    // Fire FactionSelectEvent for PlayerTracker (only for new selections)
                    HytaleServer.get().getEventBus().dispatchFor(FactionSelectEvent.class).dispatch(new FactionSelectEvent(
                        playerRef.getUuid(),
                        playerRef.getUsername(),
                        selectedFaction,
                        faction.getDisplayName()
                    ));

                    // Announce faction choice to all players (flavor text per faction)
                    String announcementText;
                    if (selectedFaction.equalsIgnoreCase("alliance") || selectedFaction.equalsIgnoreCase("valor")) {
                        announcementText = playerRef.getUsername() + " has pledged allegiance to the Kingdom of " + faction.getDisplayName() + ".";
                    } else {
                        announcementText = playerRef.getUsername() + " has joined the ranks of " + faction.getDisplayName() + ".";
                    }
                    Message announcement = Message.raw(announcementText).color(faction.getColor());
                    for (PlayerRef p : com.hypixel.hytale.server.core.universe.Universe.get().getPlayers()) {
                        try { p.sendMessage(announcement); } catch (Exception ignored) {}
                    }

                    // Update nameplate to show faction tag
                    World world = store.getExternalData().getWorld();
                    plugin.getNameplateManager().updateNameplateAsync(playerRef, world);
                }

                // Mark starter area as completed (for both new selection and confirmation)
                StarterAreaManager starterManager = StarterAreaManager.getInstance();
                if (starterManager != null) {
                    starterManager.completeStarterArea(playerRef);
                }

                // Send appropriate message
                Color factionColor = faction.getColor();
                playerRef.sendMessage(Message.raw("").color(Color.WHITE));
                playerRef.sendMessage(Message.raw("========================================").color(factionColor));
                if (isConfirmation) {
                    playerRef.sendMessage(Message.raw("  Tutorial Complete!").color(factionColor));
                } else {
                    playerRef.sendMessage(Message.raw("  Welcome to " + faction.getDisplayName() + "!").color(factionColor));
                }
                playerRef.sendMessage(Message.raw("========================================").color(factionColor));
                playerRef.sendMessage(Message.raw("").color(Color.WHITE));

                if (!isConfirmation) {
                    playerRef.sendMessage(Message.raw("You can now create or join a guild!").color(Color.GRAY));
                    playerRef.sendMessage(Message.raw("").color(Color.WHITE));
                    playerRef.sendMessage(Message.raw("Use /guild create <name> to create a guild").color(Color.YELLOW));
                    playerRef.sendMessage(Message.raw("Use /guild list to see guilds in your faction").color(Color.YELLOW));
                }

                // Show welcome banner title
                String titleText = isConfirmation ? "Tutorial Complete!" : "Welcome to " + faction.getDisplayName() + "!";
                String subtitleText = isConfirmation ? "Returning to " + faction.getDisplayName() : "Your destiny awaits";
                EventTitleUtil.showEventTitleToPlayer(
                    playerRef,
                    Message.raw(titleText).color(factionColor),
                    Message.raw(subtitleText).color(Color.WHITE),
                    true,       // isMajor - large display
                    null,       // icon
                    3.0f,       // duration
                    0.5f,       // fadeIn
                    1.0f        // fadeOut
                );

                // Play celebratory sound
                int soundIndex = SoundEvent.getAssetMap().getIndex("SFX_Discovery_Z1_Medium");
                if (soundIndex != Integer.MIN_VALUE) {
                    SoundUtil.playSoundEvent2dToPlayer(playerRef, soundIndex, SoundCategory.UI);
                }

                // Close the UI after effects
                this.close();

                // Fire callback if provided
                // Note: completeStarterArea handles teleporting to faction spawn,
                // so we don't need to call RandomTeleportUtil here
                if (onFactionSelected != null) {
                    onFactionSelected.run();
                }
            }
        }
    }

    @Override
    public void build(@NonNullDecl Ref<EntityStore> ref, @NonNullDecl UICommandBuilder cmd, @NonNullDecl UIEventBuilder events, @NonNullDecl Store<EntityStore> store) {
        // Load the faction selection UI
        cmd.append("Pages/FactionGuilds_FactionSelection.ui");

        // Check if this is a confirmation for an existing player
        boolean isConfirmation = (existingFactionId != null);

        if (isConfirmation) {
            // Update title and subtitle for confirmation mode
            cmd.set("#TitleLabel.Text", "Reaffirm Your Allegiance");
            cmd.set("#Subtitle.Text", "Recommit your loyalty to complete the trial");
            cmd.set("#Warning.Text", ""); // Hide the "permanent" warning

            // Determine which faction the player belongs to
            boolean isAlliance = existingFactionId.equalsIgnoreCase("alliance") ||
                                 existingFactionId.equalsIgnoreCase("valor");

            if (isAlliance) {
                // Hide the Horde card, update Alliance button text
                cmd.set("#HordeCard.Visible", false);
                cmd.set("#JoinValorButton.Text", "Reaffirm Loyalty to Valor");
                // Only bind the Valor button
                events.addEventBinding(CustomUIEventBindingType.Activating, "#JoinValorButton",
                    EventData.of("JoinAlliance", "true"), false);
            } else {
                // Hide the Alliance card, update Horde button text
                cmd.set("#AllianceCard.Visible", false);
                cmd.set("#JoinShadowButton.Text", "Reaffirm Loyalty to the Legion");
                // Only bind the Iron Legion button
                events.addEventBinding(CustomUIEventBindingType.Activating, "#JoinShadowButton",
                    EventData.of("JoinHorde", "true"), false);
            }
        } else {
            // Normal faction selection - show both options
            // Update member counts for each faction
            updateFactionMemberCount(cmd, "alliance", "#AllianceMembers");
            updateFactionMemberCount(cmd, "horde", "#HordeMembers");

            // Bind both button click events
            events.addEventBinding(CustomUIEventBindingType.Activating, "#JoinValorButton",
                EventData.of("JoinAlliance", "true"), false);
            events.addEventBinding(CustomUIEventBindingType.Activating, "#JoinShadowButton",
                EventData.of("JoinHorde", "true"), false);
        }
    }

    private void updateFactionMemberCount(UICommandBuilder cmd, String factionId, String labelId) {
        List<UUID> members = plugin.getPlayerDataRepository().getFactionMembers(factionId);
        cmd.set(labelId + ".Text", members.size() + " members");
    }

    /**
     * Data class for UI events
     */
    public static class FactionSelectionData {
        public static final BuilderCodec<FactionSelectionData> CODEC = BuilderCodec.<FactionSelectionData>builder(
                FactionSelectionData.class, FactionSelectionData::new)
            .addField(new KeyedCodec<>("JoinAlliance", Codec.STRING),
                (d, s) -> d.joinAlliance = s, d -> d.joinAlliance)
            .addField(new KeyedCodec<>("JoinHorde", Codec.STRING),
                (d, s) -> d.joinHorde = s, d -> d.joinHorde)
            .build();

        private String joinAlliance;
        private String joinHorde;
    }
}
