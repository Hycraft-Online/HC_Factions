package com.hcfactions.hud;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

/**
 * Persistent HUD displaying the current territory owner at the left-center of screen.
 * <p>
 * Shows guild name + faction when in claimed territory, hides in wilderness.
 * The accent bars on left/right are colored to match the claim owner's faction color.
 * Permission indicators (Build, Door, Chest, Craft, Use) shown for own guild/solo claims.
 */
public class TerritoryHud extends CustomUIHud {

    private static final String COLOR_ALLOWED = "#2d6b30";
    private static final String COLOR_DENIED = "#6b2d2d";

    private static final int HEIGHT_BANNER_ONLY = 28;
    private static final int HEIGHT_WITH_PERMISSIONS = 50;
    private static final int MIN_BANNER_WIDTH = 100;
    private static final int MAX_BANNER_WIDTH = 260;

    // Approximate pixels per character at FontSize 12 bold
    private static final double PX_PER_CHAR = 7.5;
    // Overhead: accent bars (3+3) + label padding (8+8) + buffer (4)
    private static final int WIDTH_OVERHEAD = 26;

    private final PlayerRef playerRef;

    // Cached state to avoid redundant updates
    private boolean currentlyVisible = false;
    private String currentTerritoryName = "";
    private String currentColorHex = "#96a9be";
    private int currentBannerWidth = MIN_BANNER_WIDTH;
    private boolean permissionsVisible = false;
    private boolean[] currentPerms = new boolean[5]; // build, door, chest, craft, use

    public TerritoryHud(@NonNullDecl PlayerRef playerRef) {
        super(playerRef);
        this.playerRef = playerRef;
    }

    @Override
    protected void build(@NonNullDecl UICommandBuilder cmd) {
        cmd.append("Pages/FactionGuilds_TerritoryHud.ui");

        // Hidden by default (wilderness)
        cmd.set("#TerritoryHud.Visible", false);
    }

    /**
     * Update the territory display.
     *
     * @param territoryName the text to show (e.g. "Valor Guard [Valor]")
     * @param colorHex      faction color as hex (e.g. "#D4AF37")
     * @param visible        whether the HUD should be visible
     */
    public void updateTerritory(String territoryName, String colorHex, boolean visible) {
        Ref<EntityStore> entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) {
            return;
        }

        // Skip update if nothing changed
        if (visible == currentlyVisible
                && territoryName.equals(currentTerritoryName)
                && colorHex.equals(currentColorHex)) {
            return;
        }

        currentlyVisible = visible;
        currentTerritoryName = territoryName;
        currentColorHex = colorHex;

        UICommandBuilder cmd = new UICommandBuilder();

        cmd.set("#TerritoryHud.Visible", visible);

        if (visible) {
            cmd.set("#TerritoryName.Text", territoryName);
            cmd.set("#AccentBar.Background", colorHex);
            cmd.set("#AccentBarRight.Background", colorHex);

            // Size banner to fit the text
            currentBannerWidth = estimateWidth(territoryName);
            setBannerAnchor(cmd, currentBannerWidth);
        }

        // Hide permissions when territory changes (will be re-shown by updatePermissions if applicable)
        if (permissionsVisible) {
            permissionsVisible = false;
            currentPerms = new boolean[5];
            cmd.set("#PermissionRow.Visible", false);
        }

        setHudAnchor(cmd, currentBannerWidth, HEIGHT_BANNER_ONLY);

        update(false, cmd);
    }

    /**
     * Update the permission indicators for the current chunk.
     *
     * @param canBuild whether the player can break/place blocks
     * @param canDoor  whether the player can interact with doors
     * @param canChest whether the player can open chests
     * @param canCraft whether the player can use crafting benches
     * @param canUse   whether the player can interact generally
     */
    public void updatePermissions(boolean canBuild, boolean canDoor, boolean canChest,
                                   boolean canCraft, boolean canUse) {
        Ref<EntityStore> entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) {
            return;
        }

        // Skip update if nothing changed
        if (permissionsVisible
                && currentPerms[0] == canBuild && currentPerms[1] == canDoor
                && currentPerms[2] == canChest && currentPerms[3] == canCraft
                && currentPerms[4] == canUse) {
            return;
        }

        permissionsVisible = true;
        currentPerms = new boolean[]{canBuild, canDoor, canChest, canCraft, canUse};

        // Permission row width = 5 indicators evenly dividing the banner width
        int permWidth = currentBannerWidth;
        int cellWidth = permWidth / 5;

        UICommandBuilder cmd = new UICommandBuilder();

        setHudAnchor(cmd, currentBannerWidth, HEIGHT_WITH_PERMISSIONS);
        setPermissionRowAnchor(cmd, permWidth);
        cmd.set("#PermissionRow.Visible", true);

        setPermCell(cmd, "#PermBuild", canBuild, cellWidth);
        setPermCell(cmd, "#PermDoor", canDoor, cellWidth);
        setPermCell(cmd, "#PermChest", canChest, cellWidth);
        setPermCell(cmd, "#PermCraft", canCraft, cellWidth);
        setPermCell(cmd, "#PermUse", canUse, cellWidth);

        update(false, cmd);
    }

    /**
     * Hide the permission indicators.
     */
    public void hidePermissions() {
        if (!permissionsVisible) {
            return;
        }

        Ref<EntityStore> entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) {
            return;
        }

        permissionsVisible = false;
        currentPerms = new boolean[5];

        UICommandBuilder cmd = new UICommandBuilder();
        cmd.set("#PermissionRow.Visible", false);
        setHudAnchor(cmd, currentBannerWidth, HEIGHT_BANNER_ONLY);
        update(false, cmd);
    }

    /**
     * Hide the territory HUD (e.g. when entering wilderness).
     */
    public void hide() {
        if (!currentlyVisible && !permissionsVisible) {
            return;
        }

        Ref<EntityStore> entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) {
            return;
        }

        currentlyVisible = false;
        currentTerritoryName = "";
        permissionsVisible = false;
        currentPerms = new boolean[5];

        UICommandBuilder cmd = new UICommandBuilder();
        cmd.set("#TerritoryHud.Visible", false);
        cmd.set("#PermissionRow.Visible", false);
        setHudAnchor(cmd, currentBannerWidth, HEIGHT_BANNER_ONLY);
        update(false, cmd);
    }

    /**
     * Get the player ref associated with this HUD.
     */
    public PlayerRef getPlayerRef() {
        return playerRef;
    }

    private static int estimateWidth(String text) {
        int width = (int) (text.length() * PX_PER_CHAR) + WIDTH_OVERHEAD;
        return Math.max(MIN_BANNER_WIDTH, Math.min(MAX_BANNER_WIDTH, width));
    }

    private void setHudAnchor(UICommandBuilder cmd, int width, int height) {
        Anchor anchor = new Anchor();
        anchor.setWidth(Value.of(width));
        anchor.setHeight(Value.of(height));
        cmd.setObject("#TerritoryHud.Anchor", anchor);
    }

    private void setBannerAnchor(UICommandBuilder cmd, int width) {
        Anchor anchor = new Anchor();
        anchor.setWidth(Value.of(width));
        anchor.setHeight(Value.of(HEIGHT_BANNER_ONLY));
        anchor.setTop(Value.of(0));
        cmd.setObject("#TerritoryBanner.Anchor", anchor);
    }

    private void setPermissionRowAnchor(UICommandBuilder cmd, int width) {
        Anchor anchor = new Anchor();
        anchor.setWidth(Value.of(width));
        anchor.setHeight(Value.of(18));
        anchor.setTop(Value.of(30));
        cmd.setObject("#PermissionRow.Anchor", anchor);
    }

    private void setPermCell(UICommandBuilder cmd, String selector, boolean allowed, int width) {
        cmd.set(selector + ".Background", allowed ? COLOR_ALLOWED : COLOR_DENIED);
        Anchor anchor = new Anchor();
        anchor.setWidth(Value.of(width));
        anchor.setHeight(Value.of(18));
        cmd.setObject(selector + ".Anchor", anchor);
    }
}
