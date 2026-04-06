package com.hcfactions.systems;

import com.hcfactions.HC_FactionsPlugin;
import com.hcfactions.models.Faction;
import com.hcfactions.models.PlayerData;

import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.RootDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.packets.worldmap.MapMarker;
import com.hypixel.hytale.protocol.packets.worldmap.UpdateWorldMap;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.PositionUtil;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ticking system that updates faction player markers on the world map.
 * Runs on the world thread to safely access player transforms.
 *
 * Replaces FactionPlayerMarkerProvider which caused async warnings.
 */
public class FactionPlayerMarkerTicker extends EntityTickingSystem<EntityStore> {

    private static final String MARKER_PREFIX = "FactionPlayer-";
    private static final String MARKER_ICON = "Player.png";

    private static final float UPDATE_INTERVAL = 0.2f;
    private static final double POSITION_THRESHOLD_SQ = 25.0;
    private static final float YAW_THRESHOLD = 0.1f;

    private final HC_FactionsPlugin plugin;
    private float accumulator = 0.0f;

    private final Map<UUID, Map<String, MarkerState>> displayedMarkers = new ConcurrentHashMap<>();

    public FactionPlayerMarkerTicker(HC_FactionsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void tick(float deltaTime, int index, @NonNullDecl ArchetypeChunk<EntityStore> archetypeChunk,
                     @NonNullDecl Store<EntityStore> store,
                     @NonNullDecl CommandBuffer<EntityStore> commandBuffer) {

        accumulator += deltaTime;
        if (accumulator < UPDATE_INTERVAL) {
            return;
        }
        accumulator = 0.0f;

        var externalData = store.getExternalData();
        if (externalData == null || externalData.getWorld() == null) {
            return;
        }
        String worldName = externalData.getWorld().getName();
        if (HC_FactionsPlugin.isArenaWorld(worldName)) {
            return;
        }

        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        if (ref == null || !ref.isValid()) return;

        PlayerRef viewerRef = store.getComponent(ref, PlayerRef.getComponentType());
        Player viewer = store.getComponent(ref, Player.getComponentType());

        if (viewerRef == null || viewer == null) {
            return;
        }

        updateMarkersForPlayer(viewerRef, viewer, store);
    }

    private void updateMarkersForPlayer(PlayerRef viewerRef, Player viewer, Store<EntityStore> store) {
        UUID viewerUuid = viewerRef.getUuid();
        World viewerWorld = viewer.getWorld();
        if (viewerWorld == null) {
            return;
        }

        PlayerData viewerData = plugin.getPlayerDataRepository().getPlayerData(viewerUuid);
        if (viewerData == null || viewerData.getFactionId() == null) {
            removeAllMarkersForPlayer(viewerUuid, viewerRef);
            return;
        }

        String viewerFactionId = viewerData.getFactionId();
        Faction viewerFaction = plugin.getFactionManager().getFaction(viewerFactionId);
        if (viewerFaction == null) {
            removeAllMarkersForPlayer(viewerUuid, viewerRef);
            return;
        }

        // Operators can see all players on the map
        boolean isAdmin = PermissionsModule.get().hasPermission(viewerUuid, "*");

        TransformComponent viewerTransform = viewer.getTransformComponent();
        if (viewerTransform == null) {
            return;
        }
        Vector3d viewerPos = viewerTransform.getTransform().getPosition();

        Map<String, MarkerState> viewerMarkerStates = displayedMarkers.computeIfAbsent(viewerUuid, k -> new ConcurrentHashMap<>());
        Set<String> currentMarkerIds = new HashSet<>();
        List<MapMarker> markersToSend = new ArrayList<>();

        for (PlayerRef otherPlayerRef : viewerWorld.getPlayerRefs()) {
            if (otherPlayerRef == null) continue;

            UUID otherUuid = otherPlayerRef.getUuid();
            if (otherUuid.equals(viewerUuid)) continue;

            try {
                Player otherPlayer = getMemberPlayerSafe(otherPlayerRef, viewerWorld, store);
                if (otherPlayer == null) continue;

                PlayerData otherData = plugin.getPlayerDataRepository().getPlayerData(otherUuid);
                if (otherData == null || otherData.getFactionId() == null) {
                    continue;
                }

                String otherFactionId = otherData.getFactionId();
                Faction otherFaction = plugin.getFactionManager().getFaction(otherFactionId);
                if (otherFaction == null) continue;

                if (!isAdmin && !Objects.equals(viewerFactionId, otherFactionId)) {
                    continue;
                }

                TransformComponent otherTransform = otherPlayer.getTransformComponent();
                if (otherTransform == null) continue;

                Transform transform = otherTransform.getTransform();
                Vector3d otherPos = transform.getPosition();

                double dx = otherPos.x - viewerPos.x;
                double dy = otherPos.y - viewerPos.y;
                double dz = otherPos.z - viewerPos.z;
                int distance = (int) Math.sqrt(dx * dx + dy * dy + dz * dz);

                String factionTag = "[" + otherFaction.getShortName() + "]";
                String playerName = otherPlayerRef.getUsername();
                String markerName = factionTag + " " + playerName + " (" + distance + "m)";
                String markerId = MARKER_PREFIX + otherUuid.toString();

                float memberYaw = transform.getRotation() != null ? transform.getRotation().getYaw() : 0f;

                currentMarkerIds.add(markerId);

                MarkerState existingState = viewerMarkerStates.get(markerId);
                boolean needsUpdate = existingState == null ||
                    existingState.needsUpdate(markerName, otherPos.x, otherPos.y, otherPos.z, memberYaw);

                if (needsUpdate) {
                    if (existingState == null) {
                        viewerMarkerStates.put(markerId, new MarkerState(markerId, markerName,
                            otherPos.x, otherPos.y, otherPos.z, memberYaw));
                    } else {
                        existingState.update(markerName, otherPos.x, otherPos.y, otherPos.z, memberYaw);
                    }

                    MapMarker marker = new MapMarker(markerId, null, markerName, MARKER_ICON,
                        PositionUtil.toTransformPacket(transform), null, null);
                    markersToSend.add(marker);
                }

            } catch (Exception e) {
                // Skip this player on error
            }
        }

        List<String> markersToRemove = new ArrayList<>();
        for (String existingMarkerId : viewerMarkerStates.keySet()) {
            if (!currentMarkerIds.contains(existingMarkerId)) {
                markersToRemove.add(existingMarkerId);
            }
        }
        for (String removedId : markersToRemove) {
            viewerMarkerStates.remove(removedId);
        }

        if (viewerMarkerStates.isEmpty()) {
            displayedMarkers.remove(viewerUuid);
        }

        if (!markersToSend.isEmpty() || !markersToRemove.isEmpty()) {
            sendUpdateWorldMapPacket(viewerRef, markersToSend, markersToRemove);
        }
    }

    private Player getMemberPlayerSafe(PlayerRef memberRef, World viewerWorld, Store<EntityStore> store) {
        Ref<EntityStore> entityRef = memberRef.getReference();
        if (entityRef == null || !entityRef.isValid()) {
            return null;
        }

        Player memberPlayer = store.getComponent(entityRef, Player.getComponentType());
        if (memberPlayer == null) {
            return null;
        }

        World memberWorld = memberPlayer.getWorld();
        if (memberWorld == null || !viewerWorld.equals(memberWorld)) {
            return null;
        }

        return memberPlayer;
    }

    private void removeAllMarkersForPlayer(UUID playerUuid, PlayerRef playerRef) {
        Map<String, MarkerState> existingMarkerStates = displayedMarkers.remove(playerUuid);
        if (existingMarkerStates != null && !existingMarkerStates.isEmpty() && playerRef != null) {
            sendUpdateWorldMapPacket(playerRef, Collections.emptyList(),
                new ArrayList<>(existingMarkerStates.keySet()));
        }
    }

    private void sendUpdateWorldMapPacket(PlayerRef playerRef, List<MapMarker> addedMarkers, List<String> removedMarkerIds) {
        try {
            PacketHandler handler = playerRef.getPacketHandler();
            if (handler == null) {
                return;
            }
            UpdateWorldMap packet = new UpdateWorldMap(null,
                addedMarkers.toArray(new MapMarker[0]),
                removedMarkerIds.toArray(new String[0]));
            handler.writePacket(packet, false);
        } catch (Exception e) {
            // Ignore packet errors
        }
    }

    public void removePlayer(UUID playerUuid) {
        displayedMarkers.remove(playerUuid);
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return PlayerRef.getComponentType();
    }

    @NonNullDecl
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return Collections.singleton(RootDependency.first());
    }

    private static class MarkerState {
        final String id;
        String name;
        double x, y, z;
        float yaw;

        MarkerState(String id, String name, double x, double y, double z, float yaw) {
            this.id = id;
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
        }

        boolean needsUpdate(String newName, double newX, double newY, double newZ, float newYaw) {
            if (!this.name.equals(newName)) {
                return true;
            }
            double dx = newX - this.x;
            double dy = newY - this.y;
            double dz = newZ - this.z;
            if (dx * dx + dy * dy + dz * dz >= POSITION_THRESHOLD_SQ) {
                return true;
            }
            return Math.abs(newYaw - this.yaw) >= YAW_THRESHOLD;
        }

        void update(String newName, double newX, double newY, double newZ, float newYaw) {
            this.name = newName;
            this.x = newX;
            this.y = newY;
            this.z = newZ;
            this.yaw = newYaw;
        }
    }
}
