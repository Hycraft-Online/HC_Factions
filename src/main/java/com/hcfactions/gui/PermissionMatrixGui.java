package com.hcfactions.gui;

import com.hcfactions.HC_FactionsPlugin;
import com.hcfactions.models.Claim;
import com.hcfactions.models.Guild;
import com.hcfactions.models.GuildChunkRoleAccess;
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
 * Visual permission matrix GUI for managing guild claim default permissions.
 * Displays a checkbox grid mapping permission types to guild roles.
 *
 * The underlying data model uses two minimum-role thresholds per chunk:
 * - minEditRole: the lowest role that can break/place blocks
 * - minChestRole: the lowest role that can interact with objects
 *
 * This GUI presents a user-friendly checkbox matrix and translates
 * the checked state into the appropriate minimum-role thresholds,
 * then applies them across all guild-claimed chunks.
 */
public class PermissionMatrixGui extends InteractiveCustomUIPage<PermissionMatrixGui.PermissionEventData> {

    /**
     * The five role columns displayed in the matrix, ordered from lowest to highest.
     * "Outsider" is a virtual role meaning "anyone not in the guild" (below RECRUIT).
     */
    private static final String[] ROLE_COLUMNS = {"Outsider", "Recruit", "Member", "Senior", "Officer"};

    /**
     * Permission row identifiers. The first two map to minEditRole, the rest to minChestRole.
     */
    private static final String[] PERMISSION_ROWS = {
        "Break", "Place",
        "Interact", "Doors", "Chests", "Benches", "Processing", "Seats", "Transport"
    };

    private final HC_FactionsPlugin plugin;
    private final InteractiveCustomUIPage<?> parent;
    private final Guild guild;
    private final Claim targetClaim; // null = all-claims mode, non-null = single-chunk mode

    // Permission state: true = this role has this permission.
    // Indexed as [permissionIndex][roleIndex] matching PERMISSION_ROWS and ROLE_COLUMNS.
    private final boolean[][] permissionState;

    /**
     * Creates a permission matrix for all guild claims (default/global mode).
     */
    public PermissionMatrixGui(@NonNullDecl HC_FactionsPlugin plugin,
                               @NonNullDecl PlayerRef playerRef,
                               InteractiveCustomUIPage<?> parent) {
        this(plugin, playerRef, parent, null);
    }

    /**
     * Creates a permission matrix scoped to a single chunk claim.
     * When targetClaim is non-null, permissions are loaded from and saved to only that chunk.
     */
    public PermissionMatrixGui(@NonNullDecl HC_FactionsPlugin plugin,
                               @NonNullDecl PlayerRef playerRef,
                               InteractiveCustomUIPage<?> parent,
                               Claim targetClaim) {
        super(playerRef, CustomPageLifetime.CanDismiss, PermissionEventData.CODEC);
        this.plugin = plugin;
        this.parent = parent;
        this.targetClaim = targetClaim;

        PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
        if (playerData != null && playerData.isInGuild()) {
            this.guild = plugin.getGuildManager().getGuild(playerData.getGuildId());
        } else {
            this.guild = null;
        }

        this.permissionState = new boolean[PERMISSION_ROWS.length][ROLE_COLUMNS.length];
        loadCurrentPermissions();
    }

    /**
     * Loads the current permission state from existing guild claims.
     * Each of the 9 rows loads independently from its own DB field.
     */
    private void loadCurrentPermissions() {
        // Initialize defaults: Officer column (index 4) always on, lower roles off
        for (int p = 0; p < PERMISSION_ROWS.length; p++) {
            permissionState[p][4] = true; // Officer always allowed
        }

        if (guild == null) {
            return;
        }

        // Single-chunk mode: load permissions from the specific target claim
        if (targetClaim != null) {
            GuildChunkRoleAccess access = plugin.getGuildChunkRoleAccessManager().getAccess(
                guild.getId(), targetClaim.getWorld(), targetClaim.getChunkX(), targetClaim.getChunkZ()
            );
            loadPermissionsFromAccess(access);
            return;
        }

        // All-claims mode: sample the first claim that has role access
        List<Claim> guildClaims = plugin.getClaimManager().getGuildClaims(guild.getId());
        if (guildClaims.isEmpty()) {
            // No claims yet - set sensible defaults (Member+ for everything)
            for (int p = 0; p < PERMISSION_ROWS.length; p++) {
                setPermissionsFromRole(p, GuildRole.MEMBER);
            }
            return;
        }

        GuildChunkRoleAccess sampleAccess = null;
        for (Claim claim : guildClaims) {
            sampleAccess = plugin.getGuildChunkRoleAccessManager().getAccess(
                guild.getId(), claim.getWorld(), claim.getChunkX(), claim.getChunkZ()
            );
            if (sampleAccess != null) break;
        }

        loadPermissionsFromAccess(sampleAccess);
    }

    /**
     * Loads each permission row independently from the access object's per-field roles.
     */
    private void loadPermissionsFromAccess(GuildChunkRoleAccess access) {
        for (int p = 0; p < PERMISSION_ROWS.length; p++) {
            GuildRole role = null;
            if (access != null) {
                role = access.getMinRoleFor(PERMISSION_ROWS[p]);
            }
            setPermissionsFromRole(p, role != null ? role : GuildRole.MEMBER);
        }
    }

    /**
     * Sets the permission state for a given row based on a minimum GuildRole.
     * All roles at or above the minimum get checked.
     */
    private void setPermissionsFromRole(int permissionIndex, GuildRole minRole) {
        // Role columns: 0=Outsider, 1=Recruit, 2=Member, 3=Senior, 4=Officer
        // Outsider is never set by guild roles (it would require a different mechanism)
        permissionState[permissionIndex][0] = false; // Outsider never auto-set

        for (int r = 1; r < ROLE_COLUMNS.length; r++) {
            GuildRole colRole = columnToGuildRole(r);
            if (colRole != null) {
                permissionState[permissionIndex][r] = colRole.hasAtLeast(minRole);
            }
        }
    }

    /**
     * Maps a column index to a GuildRole. Column 0 (Outsider) has no GuildRole equivalent.
     */
    private GuildRole columnToGuildRole(int colIndex) {
        return switch (colIndex) {
            case 1 -> GuildRole.RECRUIT;
            case 2 -> GuildRole.MEMBER;
            case 3 -> GuildRole.SENIOR;
            case 4 -> GuildRole.OFFICER;
            default -> null; // Outsider
        };
    }

    /**
     * Determines the minimum GuildRole that has access for a given permission row.
     * Returns null if no role has access (or only Outsider, which isn't a guild role).
     */
    private GuildRole getMinRoleForRow(int permissionIndex) {
        // Walk from lowest role (Recruit=1) to highest (Officer=4)
        // Return the first one that's checked
        for (int r = 1; r < ROLE_COLUMNS.length; r++) {
            if (permissionState[permissionIndex][r]) {
                return columnToGuildRole(r);
            }
        }
        return null; // No role has access
    }

    @Override
    public void build(@NonNullDecl Ref<EntityStore> ref, @NonNullDecl UICommandBuilder cmd,
                      @NonNullDecl UIEventBuilder events, @NonNullDecl Store<EntityStore> store) {
        cmd.append("Pages/FactionGuilds_PermissionMatrix.ui");

        if (guild == null) {
            cmd.set("#ChunkLabel.Text", "You must be in a guild to manage permissions.");
            cmd.set("#SaveCloseButton.Visible", false);
            events.addEventBinding(CustomUIEventBindingType.Activating, "#BackButton",
                EventData.of("Action", "Back"), false);
            return;
        }

        if (targetClaim != null) {
            cmd.set("#ChunkLabel.Text", "Chunk (" + targetClaim.getChunkX() + ", " + targetClaim.getChunkZ() + ") Permissions");
        } else {
            cmd.set("#ChunkLabel.Text", "Default permissions for all " + guild.getName() + " claims");
        }

        // Set checkbox states from the in-memory permission state
        setCheckboxStates(cmd);

        // Bind all checkbox ValueChanged events
        bindCheckboxEvents(events);

        // Bind button events
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BackButton",
            EventData.of("Action", "Back"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#SaveCloseButton",
            EventData.of("Action", "SaveClose"), false);
    }

    /**
     * Sets the visual checked state of all toggle buttons from the in-memory state.
     * Uses a checkmark for checked and "-" for unchecked.
     */
    private void setCheckboxStates(UICommandBuilder cmd) {
        String[][] checkboxIds = getCheckboxIds();

        for (int p = 0; p < PERMISSION_ROWS.length; p++) {
            for (int r = 0; r < ROLE_COLUMNS.length; r++) {
                cmd.set("#" + checkboxIds[p][r] + ".Text", permissionState[p][r] ? "+" : "-");
            }
        }
    }

    /**
     * Binds ValueChanged events to all checkboxes.
     */
    private void bindCheckboxEvents(UIEventBuilder events) {
        String[][] checkboxIds = getCheckboxIds();

        for (int p = 0; p < PERMISSION_ROWS.length; p++) {
            for (int r = 0; r < ROLE_COLUMNS.length; r++) {
                String id = checkboxIds[p][r];
                events.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    "#" + id,
                    EventData.of("Action", "Toggle:" + p + ":" + r),
                    false
                );
            }
        }
    }

    /**
     * Returns the checkbox element IDs as a 2D array [permission][role].
     */
    private String[][] getCheckboxIds() {
        return new String[][] {
            // Break: Outsider, Recruit, Member, Senior, Officer
            {"ChkBreakOutsider", "ChkBreakRecruit", "ChkBreakMember", "ChkBreakSenior", "ChkBreakOfficer"},
            // Place
            {"ChkPlaceOutsider", "ChkPlaceRecruit", "ChkPlaceMember", "ChkPlaceSenior", "ChkPlaceOfficer"},
            // All Interactions
            {"ChkInteractOutsider", "ChkInteractRecruit", "ChkInteractMember", "ChkInteractSenior", "ChkInteractOfficer"},
            // Doors
            {"ChkDoorsOutsider", "ChkDoorsRecruit", "ChkDoorsMember", "ChkDoorsSenior", "ChkDoorsOfficer"},
            // Chests
            {"ChkChestsOutsider", "ChkChestsRecruit", "ChkChestsMember", "ChkChestsSenior", "ChkChestsOfficer"},
            // Benches
            {"ChkBenchesOutsider", "ChkBenchesRecruit", "ChkBenchesMember", "ChkBenchesSenior", "ChkBenchesOfficer"},
            // Processing
            {"ChkProcessingOutsider", "ChkProcessingRecruit", "ChkProcessingMember", "ChkProcessingSenior", "ChkProcessingOfficer"},
            // Seats
            {"ChkSeatsOutsider", "ChkSeatsRecruit", "ChkSeatsMember", "ChkSeatsSenior", "ChkSeatsOfficer"},
            // Transport
            {"ChkTransportOutsider", "ChkTransportRecruit", "ChkTransportMember", "ChkTransportSenior", "ChkTransportOfficer"},
        };
    }

    @Override
    public void handleDataEvent(@NonNullDecl Ref<EntityStore> ref, @NonNullDecl Store<EntityStore> store,
                                @NonNullDecl PermissionEventData data) {
        super.handleDataEvent(ref, store, data);

        if (data.action == null) {
            sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
            return;
        }

        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
            return;
        }

        if (data.action.equals("Back")) {
            if (parent != null) {
                player.getPageManager().openCustomPage(ref, store, parent);
            } else {
                this.close();
            }
            return;
        }

        if (data.action.equals("SaveClose")) {
            savePermissions();
            if (targetClaim != null) {
                playerRef.sendMessage(Message.raw("Permissions saved for chunk (" + targetClaim.getChunkX() + ", " + targetClaim.getChunkZ() + ")!").color(Color.GREEN));
            } else {
                playerRef.sendMessage(Message.raw("Permissions saved to all guild claims!").color(Color.GREEN));
            }
            if (parent != null) {
                player.getPageManager().openCustomPage(ref, store, parent);
            } else {
                this.close();
            }
            return;
        }

        if (data.action.startsWith("Toggle:")) {
            handleToggle(data.action);
            this.rebuild();
            return;
        }

        sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
    }

    /**
     * Handles a checkbox toggle action.
     * Format: "Toggle:permissionIndex:roleIndex"
     *
     * When a checkbox is toggled ON, all higher roles in that row are also turned on
     * (since higher roles should always have at least as many permissions as lower roles).
     *
     * When toggled OFF, all lower roles in that row are also turned off.
     *
     * The "All Interactions" parent row (index 2) toggles all child interaction rows (indices 3-8).
     */
    private void handleToggle(String action) {
        String[] parts = action.split(":");
        if (parts.length != 3) return;

        int permIdx;
        int roleIdx;
        try {
            permIdx = Integer.parseInt(parts[1]);
            roleIdx = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            return;
        }

        if (permIdx < 0 || permIdx >= PERMISSION_ROWS.length) return;
        if (roleIdx < 0 || roleIdx >= ROLE_COLUMNS.length) return;

        // Toggle the clicked checkbox
        boolean newValue = !permissionState[permIdx][roleIdx];
        permissionState[permIdx][roleIdx] = newValue;

        if (newValue) {
            // Turned ON: all higher roles must also be ON
            for (int r = roleIdx + 1; r < ROLE_COLUMNS.length; r++) {
                permissionState[permIdx][r] = true;
            }
        } else {
            // Turned OFF: all lower roles must also be OFF
            for (int r = roleIdx - 1; r >= 0; r--) {
                permissionState[permIdx][r] = false;
            }
        }

        // If toggling the "All Interactions" parent row (index 2), propagate to children (indices 3-8)
        if (permIdx == 2) {
            for (int childIdx = 3; childIdx < PERMISSION_ROWS.length; childIdx++) {
                for (int r = 0; r < ROLE_COLUMNS.length; r++) {
                    permissionState[childIdx][r] = permissionState[2][r];
                }
            }
        }

        // If a child interaction row (3-8) is toggled ON, ensure the parent "All" is also on for that role
        if (permIdx >= 3 && permIdx < PERMISSION_ROWS.length && newValue) {
            // Check if all children are on for this role; if so, turn on the parent
            boolean allChildrenOn = true;
            for (int childIdx = 3; childIdx < PERMISSION_ROWS.length; childIdx++) {
                if (!permissionState[childIdx][roleIdx]) {
                    allChildrenOn = false;
                    break;
                }
            }
            if (allChildrenOn) {
                permissionState[2][roleIdx] = true;
                // Also cascade the parent's higher-role rule
                for (int r = roleIdx + 1; r < ROLE_COLUMNS.length; r++) {
                    permissionState[2][r] = true;
                }
            }
        }

        // If a child interaction row is toggled OFF, the parent "All" must also be off for that role
        if (permIdx >= 3 && permIdx < PERMISSION_ROWS.length && !newValue) {
            permissionState[2][roleIdx] = false;
            // Cascade: lower roles also off for parent
            for (int r = roleIdx - 1; r >= 0; r--) {
                permissionState[2][r] = false;
            }
        }
    }

    /**
     * Saves the current permission state to guild-claimed chunks.
     * Each of the 9 rows maps 1:1 to its own DB column.
     */
    private void savePermissions() {
        if (guild == null) return;

        if (targetClaim != null) {
            // Single-chunk mode: apply to only this chunk
            savePermissionsToChunk(guild.getId(), targetClaim.getWorld(),
                targetClaim.getChunkX(), targetClaim.getChunkZ());
        } else {
            // All-claims mode: apply to every guild claim
            List<Claim> guildClaims = plugin.getClaimManager().getGuildClaims(guild.getId());
            for (Claim claim : guildClaims) {
                savePermissionsToChunk(guild.getId(), claim.getWorld(),
                    claim.getChunkX(), claim.getChunkZ());
            }
        }
    }

    private void savePermissionsToChunk(UUID guildId, String world, int chunkX, int chunkZ) {
        GuildChunkRoleAccess access = new GuildChunkRoleAccess(
            guildId, world, chunkX, chunkZ,
            getMinRoleForRow(0),  // Break
            getMinRoleForRow(1),  // Place
            getMinRoleForRow(2),  // Interact (All)
            getMinRoleForRow(3),  // Doors
            getMinRoleForRow(4),  // Chests
            getMinRoleForRow(5),  // Benches
            getMinRoleForRow(6),  // Processing
            getMinRoleForRow(7),  // Seats
            getMinRoleForRow(8),  // Transport
            playerRef.getUuid(),
            System.currentTimeMillis()
        );
        plugin.getGuildChunkRoleAccessManager().setRoleRequirements(guildId, world, chunkX, chunkZ, access);
    }

    /**
     * Data class for UI events.
     */
    public static class PermissionEventData {
        public static final BuilderCodec<PermissionEventData> CODEC = BuilderCodec.<PermissionEventData>builder(
                PermissionEventData.class, PermissionEventData::new)
            .addField(new KeyedCodec<>("Action", Codec.STRING),
                (d, s) -> d.action = s, d -> d.action)
            .build();

        private String action;
    }
}
