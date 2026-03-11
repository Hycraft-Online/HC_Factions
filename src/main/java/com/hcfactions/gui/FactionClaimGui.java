package com.hcfactions.gui;

import com.hcfactions.HC_FactionsPlugin;
import com.hcfactions.managers.ClaimManager;
import com.hcfactions.models.Claim;
import com.hcfactions.models.Faction;
import com.hcfactions.models.Guild;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.util.ColorParseUtil;
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
import java.util.concurrent.CompletableFuture;

/**
 * Admin GUI for claiming chunks for a faction.
 * Displays a 17x17 grid of chunks centered on the player's location.
 * Left-click to claim for faction, right-click to unclaim.
 * Highway toggle: when enabled, new claims are highways and clicking existing claims toggles their type.
 */
public class FactionClaimGui extends InteractiveCustomUIPage<FactionClaimGui.FactionClaimData> {

    private static final int GRID_RADIUS = 8; // 8 chunks in each direction = 17x17 grid
    private static final String HYTALE_GOLD = "#93844c";
    private static final Color HIGHWAY_COLOR = new Color(255, 200, 50); // Gold for highways

    private final HC_FactionsPlugin plugin;
    private final String factionId;
    private final String dimension;
    private final int centerChunkX;
    private final int centerChunkZ;

    // Highway mode toggle state
    private boolean highwayMode = false;

    // Map background asset
    private CompletableFuture<ClaimMapAsset> mapAsset = null;

    public FactionClaimGui(@NonNullDecl HC_FactionsPlugin plugin,
                           @NonNullDecl PlayerRef playerRef,
                           @NonNullDecl String factionId,
                           @NonNullDecl String dimension,
                           int centerChunkX,
                           int centerChunkZ) {
        super(playerRef, CustomPageLifetime.CanDismiss, FactionClaimData.CODEC);
        this.plugin = plugin;
        this.factionId = factionId;
        this.dimension = dimension;
        this.centerChunkX = centerChunkX;
        this.centerChunkZ = centerChunkZ;
    }

    @Override
    public void handleDataEvent(@NonNullDecl Ref<EntityStore> ref,
                                @NonNullDecl Store<EntityStore> store,
                                @NonNullDecl FactionClaimData data) {
        super.handleDataEvent(ref, store, data);

        if (data.action == null) {
            this.sendUpdate();
            return;
        }

        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            this.sendUpdate();
            return;
        }

        // Handle highway toggle
        if ("ToggleHighway".equals(data.action)) {
            highwayMode = !highwayMode;
            UICommandBuilder commandBuilder = new UICommandBuilder();
            UIEventBuilder eventBuilder = new UIEventBuilder();
            this.build(ref, commandBuilder, eventBuilder, store);
            this.sendUpdate(commandBuilder, eventBuilder, true);
            return;
        }

        // Parse action: "LeftClicking:x:z" or "RightClicking:x:z"
        String[] parts = data.action.split(":");
        if (parts.length != 3) {
            this.sendUpdate();
            return;
        }

        String action = parts[0];
        int chunkX = Integer.parseInt(parts[1]);
        int chunkZ = Integer.parseInt(parts[2]);

        ClaimManager claimManager = plugin.getClaimManager();

        if ("LeftClicking".equals(action)) {
            Claim existing = claimManager.getClaim(dimension, chunkX, chunkZ);

            if (existing != null && existing.isFactionClaim() && existing.getFactionId().equals(factionId)) {
                // Clicking existing own faction claim: toggle highway status
                String newType = existing.isHighwayClaim() ? Claim.TYPE_STANDARD : Claim.TYPE_HIGHWAY;
                boolean success = claimManager.setClaimType(dimension, chunkX, chunkZ, newType);
                if (success) {
                    String label = Claim.TYPE_HIGHWAY.equals(newType) ? "highway" : "standard";
                    player.sendMessage(Message.raw("Chunk [" + chunkX + ", " + chunkZ + "] set to " + label + ".").color(
                        Claim.TYPE_HIGHWAY.equals(newType) ? HIGHWAY_COLOR : Color.GREEN));
                } else {
                    player.sendMessage(Message.raw("Failed to update claim type.").color(Color.RED));
                }
            } else if (existing == null) {
                // Unclaimed: claim it (highway or standard based on toggle)
                boolean success;
                if (highwayMode) {
                    success = claimManager.claimChunkAsHighway(factionId, dimension, chunkX, chunkZ);
                } else {
                    success = claimManager.claimChunkForFaction(factionId, dimension, chunkX, chunkZ);
                }
                if (success) {
                    String label = highwayMode ? "highway" : "faction";
                    player.sendMessage(Message.raw("Claimed chunk [" + chunkX + ", " + chunkZ + "] as " + label + ".").color(
                        highwayMode ? HIGHWAY_COLOR : Color.GREEN));
                } else {
                    player.sendMessage(Message.raw("Failed to claim chunk.").color(Color.RED));
                }
            }
        } else if ("RightClicking".equals(action)) {
            // Unclaim faction chunk
            Claim claim = claimManager.getClaim(dimension, chunkX, chunkZ);
            if (claim != null && claim.isFactionClaim() && claim.getFactionId().equals(factionId)) {
                boolean success = claimManager.unclaimFactionChunk(dimension, chunkX, chunkZ);
                if (success) {
                    player.sendMessage(Message.raw("Unclaimed chunk [" + chunkX + ", " + chunkZ + "].").color(Color.YELLOW));
                } else {
                    player.sendMessage(Message.raw("Failed to unclaim chunk.").color(Color.RED));
                }
            }
        }

        // Rebuild and refresh UI
        UICommandBuilder commandBuilder = new UICommandBuilder();
        UIEventBuilder eventBuilder = new UIEventBuilder();
        this.build(ref, commandBuilder, eventBuilder, store);
        this.sendUpdate(commandBuilder, eventBuilder, true);
    }

    @Override
    public void build(@NonNullDecl Ref<EntityStore> ref,
                      @NonNullDecl UICommandBuilder cmd,
                      @NonNullDecl UIEventBuilder events,
                      @NonNullDecl Store<EntityStore> store) {
        // Load the chunk visualizer UI
        cmd.append("Pages/FactionGuilds_ChunkVisualizer.ui");

        // Generate and send map background asset
        if (this.mapAsset == null) {
            // Send empty placeholder first
            ClaimMapAsset.sendToPlayer(this.playerRef.getPacketHandler(), ClaimMapAsset.empty());

            // Generate the actual map asynchronously
            this.mapAsset = ClaimMapAsset.generate(
                this.playerRef,
                centerChunkX - GRID_RADIUS,
                centerChunkZ - GRID_RADIUS,
                centerChunkX + GRID_RADIUS,
                centerChunkZ + GRID_RADIUS
            );

            if (this.mapAsset != null) {
                this.mapAsset.thenAccept(asset -> {
                    if (asset != null) {
                        ClaimMapAsset.sendToPlayer(this.playerRef.getPacketHandler(), asset);
                        this.sendUpdate();
                    }
                });
            }
        }

        // Set title with faction name
        Faction faction = plugin.getFactionManager().getFaction(factionId);
        String factionName = faction != null ? faction.getDisplayName() : factionId;
        cmd.set("#TitleText.Text", "Faction Claim Manager - " + factionName);

        // Set faction name in header
        if (faction != null) {
            cmd.set("#FactionName.TextSpans", Message.raw(factionName).color(faction.getColor()));
        } else {
            cmd.set("#FactionName.Text", factionName);
        }

        // Get all claims in the visible area
        List<Claim> nearbyClaims = plugin.getClaimManager().getClaimsInRadius(
            dimension, centerChunkX, centerChunkZ, GRID_RADIUS + 1);

        // Count faction claims for this faction
        int factionClaimCount = (int) nearbyClaims.stream()
            .filter(c -> c.isFactionClaim() && c.getFactionId().equals(factionId))
            .count();
        int highwayClaimCount = (int) nearbyClaims.stream()
            .filter(c -> c.isHighwayClaim() && c.getFactionId().equals(factionId))
            .count();
        cmd.set("#ClaimCount.Text", factionClaimCount + " (" + highwayClaimCount + " highway)");

        // Highway toggle button - update text based on state
        cmd.set("#HighwayToggleButton.Text", highwayMode ? "Highway: ON" : "Highway: OFF");
        events.addEventBinding(CustomUIEventBindingType.Activating, "#HighwayToggleButton",
            EventData.of("Action", "ToggleHighway"));

        // Update instructions based on mode
        if (highwayMode) {
            cmd.set("#InstructionsText.Text", "Highway mode: Left-click to claim as highway. Click existing claim to toggle type. Right-click to unclaim.");
        } else {
            cmd.set("#InstructionsText.Text", "Left-click to claim. Click existing claim to toggle highway. Right-click to unclaim.");
        }

        // Build the 17x17 grid
        for (int z = 0; z <= GRID_RADIUS * 2; z++) {
            // Create row group
            cmd.appendInline("#ChunkCards", "Group { LayoutMode: Left; Anchor: (Bottom: 0); }");

            for (int x = 0; x <= GRID_RADIUS * 2; x++) {
                // Append chunk entry
                cmd.append("#ChunkCards[" + z + "]", "Pages/FactionGuilds_ChunkEntry.ui");

                int chunkX = centerChunkX + x - GRID_RADIUS;
                int chunkZ = centerChunkZ + z - GRID_RADIUS;
                String cellSelector = "#ChunkCards[" + z + "][" + x + "]";

                // Mark center chunk with "+"
                if (x == GRID_RADIUS && z == GRID_RADIUS) {
                    cmd.set(cellSelector + ".Text", "+");
                }

                // Check if this chunk is claimed
                Claim claim = findClaim(nearbyClaims, chunkX, chunkZ);

                if (claim != null) {
                    // Chunk is claimed - color it based on owner
                    Color chunkColor = getClaimColor(claim);
                    Color bgColor = new Color(chunkColor.getRed(), chunkColor.getGreen(), chunkColor.getBlue(), 128);

                    cmd.set(cellSelector + ".Background.Color", ColorParseUtil.colorToHexAlpha(bgColor));
                    cmd.set(cellSelector + ".OutlineColor", ColorParseUtil.colorToHexAlpha(chunkColor));
                    cmd.set(cellSelector + ".OutlineSize", 1);

                    // Highway claims get a distinct marker
                    if (claim.isHighwayClaim()) {
                        // Show "H" marker on highway chunks (unless center chunk with "+")
                        if (!(x == GRID_RADIUS && z == GRID_RADIUS)) {
                            cmd.set(cellSelector + ".Text", "H");
                        }
                    }

                    // Build tooltip
                    String ownerName = getClaimOwnerName(claim);
                    String tooltipText = buildClaimedTooltip(claim, ownerName);
                    cmd.set(cellSelector + ".TooltipText", tooltipText);

                    // Own faction claims: left-click to toggle highway, right-click to unclaim
                    if (claim.isFactionClaim() && claim.getFactionId().equals(factionId)) {
                        events.addEventBinding(CustomUIEventBindingType.Activating, cellSelector,
                            EventData.of("Action", "LeftClicking:" + chunkX + ":" + chunkZ));
                        events.addEventBinding(CustomUIEventBindingType.RightClicking, cellSelector,
                            EventData.of("Action", "RightClicking:" + chunkX + ":" + chunkZ));
                    }
                } else {
                    // Unclaimed chunk - show wilderness tooltip and allow claiming
                    String tooltipText = buildUnclaimedTooltip();
                    cmd.set(cellSelector + ".TooltipText", tooltipText);

                    events.addEventBinding(CustomUIEventBindingType.Activating, cellSelector,
                        EventData.of("Action", "LeftClicking:" + chunkX + ":" + chunkZ));
                }
            }
        }
    }

    private Claim findClaim(List<Claim> claims, int chunkX, int chunkZ) {
        for (Claim claim : claims) {
            if (claim.getChunkX() == chunkX && claim.getChunkZ() == chunkZ) {
                return claim;
            }
        }
        return null;
    }

    private Color getClaimColor(Claim claim) {
        // Highway claims always show gold regardless of faction
        if (claim.isHighwayClaim()) {
            return HIGHWAY_COLOR;
        }
        // Other claims use faction color
        Faction faction = plugin.getFactionManager().getFaction(claim.getFactionId());
        if (faction != null) {
            return faction.getColor();
        }
        return Color.GRAY;
    }

    private String getClaimOwnerName(Claim claim) {
        if (claim.isHighwayClaim()) {
            Faction faction = plugin.getFactionManager().getFaction(claim.getFactionId());
            return faction != null ? faction.getDisplayName() + " Highway" : claim.getFactionId() + " Highway";
        } else if (claim.isFactionClaim()) {
            Faction faction = plugin.getFactionManager().getFaction(claim.getFactionId());
            return faction != null ? faction.getDisplayName() + " (Faction)" : claim.getFactionId();
        } else {
            Guild guild = plugin.getGuildManager().getGuild(claim.getGuildId());
            return guild != null ? guild.getName() + " (Guild)" : "Unknown Guild";
        }
    }

    private String buildClaimedTooltip(Claim claim, String ownerName) {
        StringBuilder sb = new StringBuilder();
        sb.append("Owner: ").append(ownerName);

        if (claim.isHighwayClaim()) {
            sb.append("\nType: Highway (1.5x sprint speed)");
        } else if (claim.isFactionClaim()) {
            sb.append("\nType: Protected");
        }

        if (claim.isFactionClaim() && claim.getFactionId().equals(factionId)) {
            sb.append("\n\nLeft Click to toggle highway");
            sb.append("\nRight Click to unclaim");
        }

        return sb.toString();
    }

    private String buildUnclaimedTooltip() {
        if (highwayMode) {
            return "Wilderness\n\nLeft Click to claim as highway";
        }
        return "Wilderness\n\nLeft Click to claim";
    }

    /**
     * Data class for UI events
     */
    public static class FactionClaimData {
        public static final BuilderCodec<FactionClaimData> CODEC = BuilderCodec.<FactionClaimData>builder(
                FactionClaimData.class, FactionClaimData::new)
            .addField(new KeyedCodec<>("Action", Codec.STRING),
                (d, s) -> d.action = s, d -> d.action)
            .build();

        private String action;
    }
}
