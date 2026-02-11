package com.hcfactions.map;

import com.hcfactions.HC_FactionsPlugin;
import com.hcfactions.managers.FactionManager;
import com.hcfactions.models.Faction;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.packets.worldmap.ContextMenuItem;
import com.hypixel.hytale.protocol.packets.worldmap.MapMarker;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MapMarkerTracker;
import com.hypixel.hytale.server.core.util.PositionUtil;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Map marker provider that displays faction capital city markers on the world map and compass.
 * Based on FactionWars' TerritoryMapMarkerProvider.
 *
 * Icons are located in resources/Common/UI/WorldMap/MapMarkers/ and will display
 * both on the world map and compass when within range.
 */
public class FactionCapitalMarkerProvider implements WorldMapManager.MarkerProvider {

    public static final String PROVIDER_ID = "factionCapitalMarkers";

    // Capital city icon located in resources/Common/UI/WorldMap/MapMarkers/
    private static final String ICON_CAPITAL = "FactionGuilds_Capital.png";

    // Log only once to avoid spam (reset on reload)
    private static final AtomicBoolean hasLoggedUpdate = new AtomicBoolean(false);
    private static final AtomicBoolean hasLoggedWorldMatch = new AtomicBoolean(false);

    @Override
    public void update(@Nonnull World world, @Nonnull MapMarkerTracker tracker,
                       int chunkViewRadius, int playerChunkX, int playerChunkZ) {

        String worldName = world.getName();
        Player viewingPlayer = tracker.getPlayer();
        int markersSent = 0;
        int factionsChecked = 0;

        // Debug: log world name once
        if (!hasLoggedWorldMatch.getAndSet(true)) {
            HC_FactionsPlugin.getInstance().getLogger().at(Level.INFO).log(
                "[FactionGuilds] FactionCapitalMarkerProvider checking world: '" + worldName + "'"
            );
        }

        FactionManager factionManager = HC_FactionsPlugin.getInstance().getFactionManager();
        if (factionManager == null) {
            return;
        }

        for (Faction faction : factionManager.getFactions()) {
            factionsChecked++;
            
            // Check if faction capital matches this world using flexible matching
            if (!factionMatchesWorld(faction, worldName)) {
                if (!hasLoggedUpdate.get()) {
                    HC_FactionsPlugin.getInstance().getLogger().at(Level.INFO).log(
                        "[FactionGuilds] Faction " + faction.getId() + " (world: '" + faction.getSpawnWorld() +
                        "') does NOT match current world: '" + worldName + "'"
                    );
                }
                continue;
            }

            // Get capital position from faction spawn point
            double centerX = faction.getSpawnX();
            double centerY = faction.getSpawnY() + 50; // Raise marker above ground for visibility
            double centerZ = faction.getSpawnZ();

            // Debug log for first update
            if (!hasLoggedUpdate.get()) {
                HC_FactionsPlugin.getInstance().getLogger().at(Level.INFO).log(
                    "[FactionGuilds] Faction " + faction.getId() +
                    " capital at: (" + centerX + ", " + faction.getSpawnY() + ", " + centerZ + ")"
                );
            }

            Vector3d position = new Vector3d(centerX, centerY, centerZ);

            String markerId = "FactionCapital-" + faction.getId();
            String markerName = buildMarkerName(faction);

            // Create context menu items (right-click actions)
            ContextMenuItem[] contextMenu = createContextMenu(faction, viewingPlayer);

            tracker.trySendMarker(
                -1,  // -1 = always visible regardless of distance
                playerChunkX,
                playerChunkZ,
                position,
                0f,  // yaw
                markerId,
                markerName,
                position,  // Pass position as context object
                (id, name, pos) -> new MapMarker(
                    id,
                    name,
                    ICON_CAPITAL,
                    PositionUtil.toTransformPacket(new Transform(pos)),
                    contextMenu
                )
            );

            markersSent++;
        }

        // Log once to confirm update is being called
        if (!hasLoggedUpdate.getAndSet(true)) {
            HC_FactionsPlugin.getInstance().getLogger().at(Level.INFO).log(
                "[FactionGuilds] FactionCapitalMarkerProvider.update - checked " + factionsChecked +
                " factions, sent " + markersSent + " markers for world: '" + worldName + "'"
            );
        }
    }

    /**
     * Create context menu items for right-click actions.
     */
    private ContextMenuItem[] createContextMenu(Faction faction, Player player) {
        List<ContextMenuItem> items = new ArrayList<>();

        // Add teleport option for admins
        if (player != null && player.hasPermission("factionguilds.admin.teleport")) {
            items.add(new ContextMenuItem(
                "Teleport to Capital",
                "fadmin teleport " + faction.getId()
            ));
        }

        // Add info option for everyone
        items.add(new ContextMenuItem(
            "View Faction Info",
            "faction info " + faction.getId()
        ));

        if (items.isEmpty()) {
            return null;
        }

        return items.toArray(new ContextMenuItem[0]);
    }

    /**
     * Check if faction capital matches the world using flexible matching.
     * Uses case-insensitive matching for robustness.
     */
    private boolean factionMatchesWorld(Faction faction, String worldName) {
        String factionWorld = faction.getSpawnWorld();
        if (factionWorld == null) return false;

        String factionWorldLower = factionWorld.toLowerCase();
        String worldNameLower = worldName.toLowerCase();

        // Exact match (case-insensitive)
        if (factionWorldLower.equals(worldNameLower)) return true;

        // "default" or "overworld" matches any world containing "default"
        if ((factionWorldLower.equals("default") || factionWorldLower.equals("overworld"))
            && worldNameLower.contains("default")) {
            return true;
        }

        // Partial match (case-insensitive)
        if (worldNameLower.contains(factionWorldLower) || factionWorldLower.contains(worldNameLower)) {
            return true;
        }

        return false;
    }

    /**
     * Builds a display name for the faction capital marker.
     */
    private String buildMarkerName(Faction faction) {
        return faction.getDisplayName() + " Capital";
    }
}
