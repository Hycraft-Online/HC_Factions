package com.hcfactions.gui;

import com.hcfactions.HC_FactionsPlugin;
import com.hcfactions.managers.ClaimManager;
import com.hcfactions.managers.ClaimManager.ClaimResult;
import com.hcfactions.managers.GuildChunkRoleAccessManager;
import com.hcfactions.models.Claim;
import com.hcfactions.models.Faction;
import com.hcfactions.models.Guild;
import com.hcfactions.models.GuildChunkAccess;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * GUI for claiming chunks and assigning chunk permissions.
 * Displays a 17x17 grid of chunks centered on the player.
 */
public class GuildClaimGui extends InteractiveCustomUIPage<GuildClaimGui.GuildClaimData> {

    private static final int GRID_RADIUS = 8; // 8 chunks each direction = 17x17

    // Claim colors
    private static final Color COLOR_OWN_GUILD = new Color(0, 200, 0);
    private static final Color COLOR_OWN_PERSONAL = new Color(0, 180, 100);
    private static final Color COLOR_ALLIED_GUILD = new Color(0, 200, 200);
    private static final Color COLOR_FACTION = new Color(100, 100, 200);
    private static final Color COLOR_ENEMY = new Color(200, 50, 50);

    // Overlay colors in permission edit modes
    private static final Color COLOR_MEMBER_OVERLAY = new Color(255, 179, 71);
    private static final Color COLOR_ROLE_OVERLAY = new Color(180, 140, 255);

    private enum EditorMode {
        CLAIMS,
        MEMBER,
        ROLE
    }

    private enum PermissionMode {
        EDIT,
        CHEST,
        BOTH
    }

    private final HC_FactionsPlugin plugin;
    private final UUID guildId;       // null for solo mode
    private final UUID playerOwnerId; // player opening this page
    private final String factionId;
    private final String dimension;
    private final int centerChunkX;
    private final int centerChunkZ;

    private EditorMode editorMode = EditorMode.CLAIMS;
    private PermissionMode memberPermissionMode = PermissionMode.BOTH;
    private PermissionMode rolePermissionMode = PermissionMode.BOTH;

    private final GuildRole[] editableRoles = new GuildRole[] {
        GuildRole.RECRUIT, GuildRole.MEMBER, GuildRole.SENIOR, GuildRole.OFFICER, GuildRole.LEADER
    };
    private int selectedRoleIndex = 1; // MEMBER by default

    private List<PlayerData> editableMembers = List.of();
    private int selectedMemberIndex = 0;

    private CompletableFuture<ClaimMapAsset> mapAsset = null;

    public GuildClaimGui(@NonNullDecl HC_FactionsPlugin plugin,
                         @NonNullDecl PlayerRef playerRef,
                         @NonNullDecl UUID guildId,
                         @NonNullDecl String factionId,
                         @NonNullDecl String dimension,
                         int centerChunkX,
                         int centerChunkZ) {
        super(playerRef, CustomPageLifetime.CanDismiss, GuildClaimData.CODEC);
        this.plugin = plugin;
        this.guildId = guildId;
        this.playerOwnerId = playerRef.getUuid();
        this.factionId = factionId;
        this.dimension = dimension;
        this.centerChunkX = centerChunkX;
        this.centerChunkZ = centerChunkZ;
    }

    public static GuildClaimGui forSoloPlayer(@NonNullDecl HC_FactionsPlugin plugin,
                                              @NonNullDecl PlayerRef playerRef,
                                              @NonNullDecl String factionId,
                                              @NonNullDecl String dimension,
                                              int centerChunkX,
                                              int centerChunkZ) {
        return new GuildClaimGui(plugin, playerRef, null, factionId, dimension, centerChunkX, centerChunkZ);
    }

    private boolean isSoloMode() {
        return guildId == null;
    }

    @Override
    public void handleDataEvent(@NonNullDecl Ref<EntityStore> ref,
                                @NonNullDecl Store<EntityStore> store,
                                @NonNullDecl GuildClaimData data) {
        super.handleDataEvent(ref, store, data);

        if (data.action == null) {
            sendUpdate();
            return;
        }

        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            sendUpdate();
            return;
        }

        String action = data.action;
        boolean handled = false;

        if (action.startsWith("CellLeft:") || action.startsWith("CellRight:")) {
            boolean rightClick = action.startsWith("CellRight:");
            String[] parts = action.split(":");
            if (parts.length == 3) {
                try {
                    int chunkX = Integer.parseInt(parts[1]);
                    int chunkZ = Integer.parseInt(parts[2]);
                    handleChunkAction(player, rightClick, chunkX, chunkZ);
                    handled = true;
                } catch (NumberFormatException ignored) {
                    handled = true;
                }
            } else {
                handled = true;
            }
        } else {
            handled = handleEditorAction(player, action);
        }

        if (handled) {
            rebuild(ref, store);
        } else {
            sendUpdate();
        }
    }

    @Override
    public void build(@NonNullDecl Ref<EntityStore> ref,
                      @NonNullDecl UICommandBuilder cmd,
                      @NonNullDecl UIEventBuilder events,
                      @NonNullDecl Store<EntityStore> store) {
        cmd.append("Pages/FactionGuilds_ChunkVisualizer.ui");

        if (mapAsset == null) {
            ClaimMapAsset.sendToPlayer(playerRef.getPacketHandler(), ClaimMapAsset.empty());
            mapAsset = ClaimMapAsset.generate(
                playerRef,
                centerChunkX - GRID_RADIUS,
                centerChunkZ - GRID_RADIUS,
                centerChunkX + GRID_RADIUS,
                centerChunkZ + GRID_RADIUS
            );

            if (mapAsset != null) {
                mapAsset.thenAccept(asset -> {
                    if (asset != null) {
                        ClaimMapAsset.sendToPlayer(playerRef.getPacketHandler(), asset);
                        sendUpdate();
                    }
                });
            }
        }

        Faction faction = plugin.getFactionManager().getFaction(factionId);

        if (isSoloMode()) {
            cmd.set("#TitleText.Text", "Personal Claim Manager");
            cmd.set("#FactionLabel.Text", "Faction: ");
            if (faction != null) {
                cmd.set("#FactionName.TextSpans", Message.raw(faction.getDisplayName()).color(faction.getColor()));
            } else {
                cmd.set("#FactionName.Text", factionId);
            }

            int claimCount = plugin.getClaimManager().getPlayerClaimCount(playerOwnerId);
            int maxClaims = plugin.getClaimManager().getSoloMaxClaims();
            cmd.set("#ClaimCountLabel.Text", "Claims: ");
            cmd.set("#ClaimCount.Text", claimCount + "/" + maxClaims);
        } else {
            Guild guild = plugin.getGuildManager().getGuild(guildId);
            String guildName = guild != null ? guild.getName() : "Unknown Guild";

            cmd.set("#TitleText.Text", "Guild Claim Manager - " + guildName);
            cmd.set("#FactionLabel.Text", "Guild: ");
            if (faction != null) {
                cmd.set("#FactionName.TextSpans", Message.raw(guildName).color(faction.getColor()));
            } else {
                cmd.set("#FactionName.Text", guildName);
            }

            int claimCount = plugin.getClaimManager().getClaimCount(guildId);
            int maxClaims = plugin.getClaimManager().getMaxClaims(guildId);
            int power = guild != null ? guild.getPower() : 0;
            int powerPerClaim = plugin.getClaimManager().getPowerPerClaim();
            int powerLimit = powerPerClaim > 0 ? power / powerPerClaim : maxClaims;
            cmd.set("#ClaimCountLabel.Text", "Claims: ");
            cmd.set("#ClaimCount.Text", claimCount + "/" + Math.min(maxClaims, powerLimit) + " (Power: " + power + ")");
        }

        configureEditorPanels(cmd);

        List<Claim> nearbyClaims = plugin.getClaimManager().getClaimsInRadius(
            dimension, centerChunkX, centerChunkZ, GRID_RADIUS + 1
        );

        for (int z = 0; z <= GRID_RADIUS * 2; z++) {
            cmd.appendInline("#ChunkCards", "Group { LayoutMode: Left; Anchor: (Bottom: 0); }");

            for (int x = 0; x <= GRID_RADIUS * 2; x++) {
                cmd.append("#ChunkCards[" + z + "]", "Pages/FactionGuilds_ChunkEntry.ui");

                int chunkX = centerChunkX + x - GRID_RADIUS;
                int chunkZ = centerChunkZ + z - GRID_RADIUS;
                String cellSelector = "#ChunkCards[" + z + "][" + x + "]";

                boolean isCenterCell = (x == GRID_RADIUS && z == GRID_RADIUS);
                if (isCenterCell) {
                    cmd.set(cellSelector + ".Text", "+");
                }

                Claim claim = findClaim(nearbyClaims, chunkX, chunkZ);
                if (claim != null) {
                    Color chunkColor = getClaimColor(claim);
                    Color bgColor = new Color(chunkColor.getRed(), chunkColor.getGreen(), chunkColor.getBlue(), 128);

                    cmd.set(cellSelector + ".Background.Color", ColorParseUtil.colorToHexAlpha(bgColor));
                    cmd.set(cellSelector + ".OutlineColor", ColorParseUtil.colorToHexAlpha(chunkColor));
                    cmd.set(cellSelector + ".OutlineSize", 1);

                    ChunkOverlay overlay = getOverlay(claim, chunkX, chunkZ);
                    if (overlay.active) {
                        cmd.set(cellSelector + ".OutlineColor", ColorParseUtil.colorToHexAlpha(overlay.color));
                        cmd.set(cellSelector + ".OutlineSize", 2);
                        if (!isCenterCell) {
                            cmd.set(cellSelector + ".Text", overlay.marker);
                        }
                    }

                    String ownerName = getClaimOwnerName(claim);
                    String tooltip = buildClaimedTooltip(claim, chunkX, chunkZ, ownerName);
                    cmd.set(cellSelector + ".TooltipText", tooltip);
                } else {
                    cmd.set(cellSelector + ".TooltipText", buildUnclaimedTooltip());
                }

                events.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    cellSelector,
                    EventData.of("Action", "CellLeft:" + chunkX + ":" + chunkZ)
                );
                events.addEventBinding(
                    CustomUIEventBindingType.RightClicking,
                    cellSelector,
                    EventData.of("Action", "CellRight:" + chunkX + ":" + chunkZ)
                );
            }
        }

        bindEditorEvents(events);
    }

    private void configureEditorPanels(UICommandBuilder cmd) {
        if (isSoloMode()) {
            cmd.set("#ModeMemberButton.Visible", false);
            cmd.set("#ModeRoleButton.Visible", false);
            cmd.set("#MemberEditor.Visible", false);
            cmd.set("#RoleEditor.Visible", false);
            cmd.set("#EditContextLabel.Text", "Claim Mode");
            cmd.set("#InstructionsText.Text", "Left-click wilderness to claim. Right-click your claim to unclaim.");
            cmd.set("#ModeClaimsButton.Text", "[Claim Mode]");
            return;
        }

        refreshEditableMembers();
        PlayerData selectedMember = getSelectedMember();
        GuildRole selectedRole = getSelectedRole();

        cmd.set("#ModeClaimsButton.Text", editorMode == EditorMode.CLAIMS ? "[Claim Mode]" : "Claim Mode");
        cmd.set("#ModeMemberButton.Text", editorMode == EditorMode.MEMBER ? "[Member Mode]" : "Member Mode");
        cmd.set("#ModeRoleButton.Text", editorMode == EditorMode.ROLE ? "[Role Mode]" : "Role Mode");

        cmd.set("#MemberEditor.Visible", editorMode == EditorMode.MEMBER);
        cmd.set("#RoleEditor.Visible", editorMode == EditorMode.ROLE);

        cmd.set("#MemberTargetLabel.Text", selectedMember != null ? selectedMember.getPlayerName() : "No Members");
        cmd.set("#RoleTargetLabel.Text", selectedRole != null ? selectedRole.getDisplayName() : "None");

        cmd.set("#MemberPermEditButton.Text", memberPermissionMode == PermissionMode.EDIT ? "[Edit]" : "Edit");
        cmd.set("#MemberPermChestButton.Text", memberPermissionMode == PermissionMode.CHEST ? "[Chest]" : "Chest");
        cmd.set("#MemberPermBothButton.Text", memberPermissionMode == PermissionMode.BOTH ? "[Both]" : "Both");

        cmd.set("#RolePermEditButton.Text", rolePermissionMode == PermissionMode.EDIT ? "[Edit]" : "Edit");
        cmd.set("#RolePermChestButton.Text", rolePermissionMode == PermissionMode.CHEST ? "[Chest]" : "Chest");
        cmd.set("#RolePermBothButton.Text", rolePermissionMode == PermissionMode.BOTH ? "[Both]" : "Both");

        switch (editorMode) {
            case CLAIMS -> {
                cmd.set("#EditContextLabel.Text", "Claim Mode");
                cmd.set("#InstructionsText.Text", "Left-click wilderness to claim. Right-click your claim to unclaim.");
            }
            case MEMBER -> {
                String target = selectedMember != null ? selectedMember.getPlayerName() : "No member selected";
                cmd.set("#EditContextLabel.Text", "Editing member: " + target + " (" + memberPermissionMode.name().toLowerCase() + ")");
                cmd.set("#InstructionsText.Text", "Left-click your guild chunks to assign. Right-click your guild chunks to remove this member.");
            }
            case ROLE -> {
                String target = selectedRole != null ? selectedRole.getDisplayName() : "None";
                cmd.set("#EditContextLabel.Text", "Editing role: " + target + " (" + rolePermissionMode.name().toLowerCase() + ")");
                cmd.set("#InstructionsText.Text", "Left-click your guild chunks to set role requirement. Right-click to clear selected requirement.");
            }
        }
    }

    private void bindEditorEvents(UIEventBuilder events) {
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ModeClaimsButton", EventData.of("Action", "ModeClaims"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ModeMemberButton", EventData.of("Action", "ModeMember"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ModeRoleButton", EventData.of("Action", "ModeRole"));

        if (!isSoloMode()) {
            events.addEventBinding(CustomUIEventBindingType.Activating, "#MemberPrevButton", EventData.of("Action", "MemberPrev"));
            events.addEventBinding(CustomUIEventBindingType.Activating, "#MemberNextButton", EventData.of("Action", "MemberNext"));
            events.addEventBinding(CustomUIEventBindingType.Activating, "#MemberPermEditButton", EventData.of("Action", "MemberPermEdit"));
            events.addEventBinding(CustomUIEventBindingType.Activating, "#MemberPermChestButton", EventData.of("Action", "MemberPermChest"));
            events.addEventBinding(CustomUIEventBindingType.Activating, "#MemberPermBothButton", EventData.of("Action", "MemberPermBoth"));

            events.addEventBinding(CustomUIEventBindingType.Activating, "#RolePrevButton", EventData.of("Action", "RolePrev"));
            events.addEventBinding(CustomUIEventBindingType.Activating, "#RoleNextButton", EventData.of("Action", "RoleNext"));
            events.addEventBinding(CustomUIEventBindingType.Activating, "#RolePermEditButton", EventData.of("Action", "RolePermEdit"));
            events.addEventBinding(CustomUIEventBindingType.Activating, "#RolePermChestButton", EventData.of("Action", "RolePermChest"));
            events.addEventBinding(CustomUIEventBindingType.Activating, "#RolePermBothButton", EventData.of("Action", "RolePermBoth"));
        }
    }

    private boolean handleEditorAction(Player player, String action) {
        switch (action) {
            case "ModeClaims" -> {
                if (editorMode == EditorMode.CLAIMS) {
                    player.sendMessage(
                        Message.raw("Claim mode active: left-click wilderness to claim, right-click your claim to unclaim.")
                            .color(Color.GRAY)
                    );
                    return true;
                }
                editorMode = EditorMode.CLAIMS;
                player.sendMessage(Message.raw("Switched to claim mode.").color(Color.YELLOW));
                return true;
            }
            case "ModeMember" -> {
                if (isSoloMode()) {
                    player.sendMessage(Message.raw("Member edit mode is only available for guild claims.").color(Color.RED));
                    return true;
                }
                editorMode = EditorMode.MEMBER;
                return true;
            }
            case "ModeRole" -> {
                if (isSoloMode()) {
                    player.sendMessage(Message.raw("Role edit mode is only available for guild claims.").color(Color.RED));
                    return true;
                }
                editorMode = EditorMode.ROLE;
                return true;
            }
            case "MemberPrev" -> {
                rotateMember(-1);
                return true;
            }
            case "MemberNext" -> {
                rotateMember(1);
                return true;
            }
            case "MemberPermEdit" -> {
                memberPermissionMode = PermissionMode.EDIT;
                return true;
            }
            case "MemberPermChest" -> {
                memberPermissionMode = PermissionMode.CHEST;
                return true;
            }
            case "MemberPermBoth" -> {
                memberPermissionMode = PermissionMode.BOTH;
                return true;
            }
            case "RolePrev" -> {
                rotateRole(-1);
                return true;
            }
            case "RoleNext" -> {
                rotateRole(1);
                return true;
            }
            case "RolePermEdit" -> {
                rolePermissionMode = PermissionMode.EDIT;
                return true;
            }
            case "RolePermChest" -> {
                rolePermissionMode = PermissionMode.CHEST;
                return true;
            }
            case "RolePermBoth" -> {
                rolePermissionMode = PermissionMode.BOTH;
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void handleChunkAction(Player player, boolean rightClick, int chunkX, int chunkZ) {
        ClaimManager claimManager = plugin.getClaimManager();
        Claim claim = claimManager.getClaim(dimension, chunkX, chunkZ);

        if (!rightClick) {
            if (claim == null) {
                if (editorMode == EditorMode.CLAIMS) {
                    handleClaimChunk(player, chunkX, chunkZ);
                }
                return;
            }

            if (!isSoloMode() && isOwnGuildClaim(claim)) {
                if (editorMode == EditorMode.MEMBER) {
                    handleMemberAssign(player, chunkX, chunkZ);
                } else if (editorMode == EditorMode.ROLE) {
                    handleRoleAssign(player, chunkX, chunkZ);
                }
            }
            return;
        }

        if (editorMode == EditorMode.CLAIMS) {
            handleUnclaimChunk(player, claim, chunkX, chunkZ);
            return;
        }

        if (!isSoloMode() && claim != null && isOwnGuildClaim(claim)) {
            if (editorMode == EditorMode.MEMBER) {
                handleMemberUnassign(player, chunkX, chunkZ);
            } else if (editorMode == EditorMode.ROLE) {
                handleRoleClear(player, chunkX, chunkZ);
            }
        }
    }

    private void handleClaimChunk(Player player, int chunkX, int chunkZ) {
        ClaimResult result;
        if (isSoloMode()) {
            result = plugin.getClaimManager().claimChunkForPlayer(playerOwnerId, dimension, chunkX, chunkZ);
            if (result.isSuccess()) {
                int current = plugin.getClaimManager().getPlayerClaimCount(playerOwnerId);
                int max = plugin.getClaimManager().getSoloMaxClaims();
                player.sendMessage(Message.raw(
                    "Personal claim created! [" + chunkX + ", " + chunkZ + "] (" + current + "/" + max + ")"
                ).color(Color.GREEN));
            } else {
                player.sendMessage(Message.raw(result.getMessage()).color(Color.RED));
            }
        } else {
            result = plugin.getClaimManager().claimChunkWithResult(guildId, dimension, chunkX, chunkZ);
            if (result.isSuccess()) {
                player.sendMessage(Message.raw(
                    "Claimed chunk [" + chunkX + ", " + chunkZ + "] for your guild!"
                ).color(Color.GREEN));
            } else {
                player.sendMessage(Message.raw(result.getMessage()).color(Color.RED));
            }
        }
    }

    private void handleUnclaimChunk(Player player, Claim claim, int chunkX, int chunkZ) {
        if (claim == null || claim.isFactionClaim()) {
            return;
        }

        boolean canUnclaim = false;
        boolean success;
        if (isSoloMode() && claim.isSoloPlayerClaim() && playerOwnerId.equals(claim.getPlayerOwnerId())) {
            canUnclaim = true;
            success = plugin.getClaimManager().unclaimChunkForPlayer(playerOwnerId, dimension, chunkX, chunkZ);
        } else if (!isSoloMode() && guildId.equals(claim.getGuildId())) {
            canUnclaim = true;
            success = plugin.getClaimManager().unclaimChunk(guildId, dimension, chunkX, chunkZ);
        } else {
            return;
        }

        if (canUnclaim && success) {
            player.sendMessage(Message.raw("Unclaimed chunk [" + chunkX + ", " + chunkZ + "].").color(Color.YELLOW));
        } else {
            player.sendMessage(Message.raw("Failed to unclaim chunk.").color(Color.RED));
        }
    }

    private void handleMemberAssign(Player player, int chunkX, int chunkZ) {
        PlayerData target = getSelectedMember();
        if (target == null) {
            player.sendMessage(Message.raw("No member selected for assignment.").color(Color.RED));
            return;
        }

        boolean canEdit = memberPermissionMode != PermissionMode.CHEST;
        boolean canChest = memberPermissionMode != PermissionMode.EDIT;

        plugin.getGuildChunkAccessManager().assign(
            guildId,
            target.getPlayerUuid(),
            dimension,
            chunkX,
            chunkZ,
            canEdit,
            canChest,
            playerRef.getUuid()
        );

        player.sendMessage(Message.raw(
            "Assigned " + target.getPlayerName() + " on [" + chunkX + ", " + chunkZ + "] "
                + "(edit=" + canEdit + ", chest=" + canChest + ")"
        ).color(Color.GREEN));
    }

    private void handleMemberUnassign(Player player, int chunkX, int chunkZ) {
        PlayerData target = getSelectedMember();
        if (target == null) {
            return;
        }

        plugin.getGuildChunkAccessManager().unassign(guildId, target.getPlayerUuid(), dimension, chunkX, chunkZ);
        player.sendMessage(Message.raw(
            "Removed " + target.getPlayerName() + " from [" + chunkX + ", " + chunkZ + "]."
        ).color(Color.YELLOW));
    }

    private void handleRoleAssign(Player player, int chunkX, int chunkZ) {
        GuildRole role = getSelectedRole();
        if (role == null) {
            return;
        }

        plugin.getGuildChunkRoleAccessManager().setRoleRequirement(
            guildId,
            dimension,
            chunkX,
            chunkZ,
            toPermissionType(rolePermissionMode),
            role,
            playerRef.getUuid()
        );

        player.sendMessage(Message.raw(
            "Set " + rolePermissionMode.name().toLowerCase() + " role requirement at [" + chunkX + ", " + chunkZ + "] to "
                + role.getDisplayName() + "."
        ).color(Color.GREEN));
    }

    private void handleRoleClear(Player player, int chunkX, int chunkZ) {
        plugin.getGuildChunkRoleAccessManager().clearRoleRequirement(
            guildId,
            dimension,
            chunkX,
            chunkZ,
            toPermissionType(rolePermissionMode),
            playerRef.getUuid()
        );

        player.sendMessage(Message.raw(
            "Cleared " + rolePermissionMode.name().toLowerCase() + " role requirement at [" + chunkX + ", " + chunkZ + "]."
        ).color(Color.YELLOW));
    }

    private GuildChunkRoleAccessManager.PermissionType toPermissionType(PermissionMode mode) {
        return switch (mode) {
            case EDIT -> GuildChunkRoleAccessManager.PermissionType.EDIT;
            case CHEST -> GuildChunkRoleAccessManager.PermissionType.CHEST;
            case BOTH -> GuildChunkRoleAccessManager.PermissionType.BOTH;
        };
    }

    private void rebuild(Ref<EntityStore> ref, Store<EntityStore> store) {
        UICommandBuilder commandBuilder = new UICommandBuilder();
        UIEventBuilder eventBuilder = new UIEventBuilder();
        build(ref, commandBuilder, eventBuilder, store);
        sendUpdate(commandBuilder, eventBuilder, true);
    }

    private Claim findClaim(List<Claim> claims, int chunkX, int chunkZ) {
        for (Claim claim : claims) {
            if (claim.getChunkX() == chunkX && claim.getChunkZ() == chunkZ) {
                return claim;
            }
        }
        return null;
    }

    private boolean isOwnGuildClaim(Claim claim) {
        return claim != null && !isSoloMode()
            && claim.getGuildId() != null
            && claim.getGuildId().equals(guildId);
    }

    private void refreshEditableMembers() {
        if (isSoloMode()) {
            editableMembers = List.of();
            selectedMemberIndex = 0;
            return;
        }

        List<PlayerData> members = plugin.getGuildManager().getGuildMembers(guildId);
        List<PlayerData> filtered = new ArrayList<>();
        for (PlayerData member : members) {
            GuildRole role = member.getGuildRole();
            if (role == null || !role.hasAtLeast(GuildRole.OFFICER)) {
                filtered.add(member);
            }
        }

        editableMembers = filtered;
        if (selectedMemberIndex >= editableMembers.size()) {
            selectedMemberIndex = Math.max(0, editableMembers.size() - 1);
        }
    }

    private void rotateMember(int delta) {
        if (editableMembers.isEmpty()) {
            selectedMemberIndex = 0;
            return;
        }
        selectedMemberIndex = (selectedMemberIndex + delta) % editableMembers.size();
        if (selectedMemberIndex < 0) {
            selectedMemberIndex += editableMembers.size();
        }
    }

    private void rotateRole(int delta) {
        if (editableRoles.length == 0) {
            selectedRoleIndex = 0;
            return;
        }
        selectedRoleIndex = (selectedRoleIndex + delta) % editableRoles.length;
        if (selectedRoleIndex < 0) {
            selectedRoleIndex += editableRoles.length;
        }
    }

    private PlayerData getSelectedMember() {
        if (editableMembers.isEmpty()) {
            return null;
        }
        if (selectedMemberIndex < 0 || selectedMemberIndex >= editableMembers.size()) {
            selectedMemberIndex = 0;
        }
        return editableMembers.get(selectedMemberIndex);
    }

    private GuildRole getSelectedRole() {
        if (editableRoles.length == 0) {
            return null;
        }
        if (selectedRoleIndex < 0 || selectedRoleIndex >= editableRoles.length) {
            selectedRoleIndex = 0;
        }
        return editableRoles[selectedRoleIndex];
    }

    private Color getClaimColor(Claim claim) {
        if (claim.isFactionClaim()) {
            return COLOR_FACTION;
        }

        if (claim.isSoloPlayerClaim()) {
            if (playerOwnerId.equals(claim.getPlayerOwnerId())) {
                return COLOR_OWN_PERSONAL;
            }
            if (factionId.equals(claim.getFactionId())) {
                return COLOR_ALLIED_GUILD;
            }
            return COLOR_ENEMY;
        }

        if (guildId != null && guildId.equals(claim.getGuildId())) {
            return COLOR_OWN_GUILD;
        }

        if (factionId.equals(claim.getFactionId())) {
            return COLOR_ALLIED_GUILD;
        }

        return COLOR_ENEMY;
    }

    private String getClaimOwnerName(Claim claim) {
        if (claim.isFactionClaim()) {
            Faction faction = plugin.getFactionManager().getFaction(claim.getFactionId());
            return faction != null ? faction.getDisplayName() + " (Protected)" : claim.getFactionId();
        }

        if (claim.isSoloPlayerClaim()) {
            PlayerData ownerData = plugin.getPlayerDataRepository().getPlayerData(claim.getPlayerOwnerId());
            String playerName = ownerData != null && ownerData.getPlayerName() != null
                ? ownerData.getPlayerName()
                : "Unknown Player";

            String relationship;
            if (playerOwnerId.equals(claim.getPlayerOwnerId())) {
                relationship = "Your Claim";
            } else if (factionId.equals(claim.getFactionId())) {
                relationship = "Allied Player";
            } else {
                relationship = "Enemy Player";
            }
            return playerName + " (" + relationship + ")";
        }

        Guild claimGuild = plugin.getGuildManager().getGuild(claim.getGuildId());
        if (claimGuild != null) {
            String relationship;
            if (guildId != null && guildId.equals(claim.getGuildId())) {
                relationship = "Your Guild";
            } else if (factionId.equals(claim.getFactionId())) {
                relationship = "Allied";
            } else {
                relationship = "Enemy";
            }
            return claimGuild.getName() + " (" + relationship + ")";
        }
        return "Unknown Guild";
    }

    private String buildClaimedTooltip(Claim claim, int chunkX, int chunkZ, String ownerName) {
        StringBuilder sb = new StringBuilder();
        sb.append("Owner: ").append(ownerName);

        if (claim.isFactionClaim()) {
            sb.append("\n\nProtected faction territory");
            return sb.toString();
        }

        if (editorMode == EditorMode.CLAIMS) {
            if (claim.isSoloPlayerClaim() && playerOwnerId.equals(claim.getPlayerOwnerId())) {
                sb.append("\n\nRight Click to Unclaim");
            } else if (!claim.isSoloPlayerClaim() && guildId != null && guildId.equals(claim.getGuildId())) {
                sb.append("\n\nRight Click to Unclaim");
            }
        }

        if (!isSoloMode() && claim.getGuildId() != null && claim.getGuildId().equals(guildId)) {
            GuildChunkRoleAccess roleAccess = plugin.getGuildChunkRoleAccessManager().getAccess(
                guildId, dimension, chunkX, chunkZ
            );
            if (roleAccess != null) {
                sb.append("\nEdit Role: ").append(roleDisplay(roleAccess.getMinEditRole()));
                sb.append("\nChest Role: ").append(roleDisplay(roleAccess.getMinChestRole()));
            }

            PlayerData selectedMember = getSelectedMember();
            if (selectedMember != null) {
                GuildChunkAccess access = plugin.getGuildChunkAccessManager().getAccess(
                    guildId, selectedMember.getPlayerUuid(), dimension, chunkX, chunkZ
                );
                if (access != null) {
                    sb.append("\n").append(selectedMember.getPlayerName()).append(": ")
                        .append("edit=").append(access.canEdit())
                        .append(", chest=").append(access.canChest());
                }
            }
        }

        return sb.toString();
    }

    private String buildUnclaimedTooltip() {
        if (editorMode == EditorMode.CLAIMS) {
            return "Wilderness\n\nLeft Click to claim";
        }
        return "Wilderness";
    }

    private String roleDisplay(GuildRole role) {
        return role != null ? role.getDisplayName() : "None";
    }

    private ChunkOverlay getOverlay(Claim claim, int chunkX, int chunkZ) {
        if (isSoloMode() || claim == null || claim.getGuildId() == null || !claim.getGuildId().equals(guildId)) {
            return ChunkOverlay.none();
        }

        if (editorMode == EditorMode.MEMBER) {
            PlayerData selectedMember = getSelectedMember();
            if (selectedMember == null) {
                return ChunkOverlay.none();
            }

            GuildChunkAccess access = plugin.getGuildChunkAccessManager().getAccess(
                guildId, selectedMember.getPlayerUuid(), dimension, chunkX, chunkZ
            );
            if (access == null) {
                return ChunkOverlay.none();
            }

            boolean highlighted = switch (memberPermissionMode) {
                case EDIT -> access.canEdit();
                case CHEST -> access.canChest();
                case BOTH -> access.canEdit() || access.canChest();
            };
            return highlighted ? new ChunkOverlay(true, COLOR_MEMBER_OVERLAY, "M") : ChunkOverlay.none();
        }

        if (editorMode == EditorMode.ROLE) {
            GuildRole selectedRole = getSelectedRole();
            if (selectedRole == null) {
                return ChunkOverlay.none();
            }

            GuildChunkRoleAccess roleAccess = plugin.getGuildChunkRoleAccessManager().getAccess(
                guildId, dimension, chunkX, chunkZ
            );
            if (roleAccess == null) {
                return ChunkOverlay.none();
            }

            boolean highlighted = switch (rolePermissionMode) {
                case EDIT -> plugin.getGuildChunkRoleAccessManager().roleMeetsRequirement(selectedRole, roleAccess.getMinEditRole());
                case CHEST -> plugin.getGuildChunkRoleAccessManager().roleMeetsRequirement(selectedRole, roleAccess.getMinChestRole());
                case BOTH -> plugin.getGuildChunkRoleAccessManager().roleMeetsRequirement(selectedRole, roleAccess.getMinEditRole())
                    || plugin.getGuildChunkRoleAccessManager().roleMeetsRequirement(selectedRole, roleAccess.getMinChestRole());
            };
            return highlighted ? new ChunkOverlay(true, COLOR_ROLE_OVERLAY, "R") : ChunkOverlay.none();
        }

        return ChunkOverlay.none();
    }

    private static class ChunkOverlay {
        private final boolean active;
        private final Color color;
        private final String marker;

        private ChunkOverlay(boolean active, Color color, String marker) {
            this.active = active;
            this.color = color;
            this.marker = marker;
        }

        private static ChunkOverlay none() {
            return new ChunkOverlay(false, Color.WHITE, "");
        }
    }

    /**
     * Data class for UI events.
     */
    public static class GuildClaimData {
        public static final BuilderCodec<GuildClaimData> CODEC = BuilderCodec.<GuildClaimData>builder(
                GuildClaimData.class, GuildClaimData::new)
            .addField(new KeyedCodec<>("Action", Codec.STRING),
                (d, s) -> d.action = s, d -> d.action)
            .build();

        private String action;
    }
}
