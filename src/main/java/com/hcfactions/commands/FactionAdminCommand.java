package com.hcfactions.commands;

import com.hcfactions.HC_FactionsPlugin;
import com.hcfactions.config.FactionGuildsConfig;
import com.hcfactions.gui.AdminGuildClaimGui;
import com.hcfactions.gui.FactionClaimGui;


import com.hcfactions.managers.ClaimManager;
import com.hcfactions.models.Claim;
import com.hcfactions.map.ClaimMapManager;
import com.hcfactions.models.Faction;
import com.hcfactions.models.Guild;
import com.hcfactions.models.PlayerData;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Admin commands for managing factions and guilds.
 */
public class FactionAdminCommand extends AbstractAsyncCommand {

    private final HC_FactionsPlugin plugin;

    public FactionAdminCommand(HC_FactionsPlugin plugin) {
        super("factionadmin", "Admin commands for factions and guilds");
        this.addAliases("fadmin", "fa");
        this.setAllowsExtraArguments(true);
        this.requirePermission("*");
        this.plugin = plugin;
    }

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    @NonNullDecl
    @Override
    protected CompletableFuture<Void> executeAsync(@NonNullDecl CommandContext ctx) {
        CommandSender sender = ctx.sender();

        String inputString = ctx.getInputString();
        String[] parts = inputString.split("\\s+");
        String[] args = parts.length > 1 ? Arrays.copyOfRange(parts, 1, parts.length) : new String[]{};

        if (args.length == 0) {
            showHelp(sender);
            return CompletableFuture.completedFuture(null);
        }

        String subCommand = args[0].toLowerCase();

        // All subcommands require admin permissions
        if (sender instanceof Player player && !player.hasPermission("*")) {
            sender.sendMessage(Message.raw("You must be an operator to use this command").color(Color.RED));
            return CompletableFuture.completedFuture(null);
        }

        switch (subCommand) {
            case "setfaction" -> handleSetFaction(sender, args);
            case "resetfaction" -> handleResetFaction(sender, args);
            case "resetguild" -> handleResetGuild(sender, args);
            case "deleteguild" -> handleDeleteGuild(sender, args);
            case "info" -> handleInfo(sender, args);
            case "listguilds" -> handleListGuilds(sender);
            case "maprefresh" -> handleMapRefresh(sender);
            case "claim" -> handleClaim(sender, args);
            case "claimfor" -> handleClaimFor(sender, args);
            case "unclaimguild" -> handleUnclaimGuild(sender, args);
            case "transferclaim" -> handleTransferClaim(sender, args);
            case "bypass", "bypasstoggle", "togglebypass", "testmode" -> handleBypassToggle(sender);
            case "addeditor" -> handleAddEditor(sender, args);
            case "removeeditor" -> handleRemoveEditor(sender, args);
            case "listeditors" -> handleListEditors(sender);
            case "config" -> handleConfig(sender, args);
            case "faction" -> handleFaction(sender, args);
            case "tp" -> handleTeleport(sender, args);
            case "blacklist", "bl" -> handleBlacklist(sender, args);
            case "help" -> showHelp(sender);
            default -> {
                sender.sendMessage(Message.raw("Unknown subcommand: " + subCommand).color(Color.RED));
                showHelp(sender);
            }
        }

        return CompletableFuture.completedFuture(null);
    }

    private void showHelp(CommandSender sender) {
        sender.sendMessage(Message.raw("=== FactionAdmin Commands ===").color(Color.ORANGE));
        sender.sendMessage(Message.raw("/fa setfaction <player> <faction> - Set player faction").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa resetfaction <player> - Remove player from faction").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa resetguild <player> - Remove player from guild (fix orphaned data)").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa deleteguild <guild> - Force delete a guild").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa info <player> - View player info").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa listguilds - List all guilds").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa maprefresh - Refresh world map for all claims").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa claim <faction> - Open claim GUI for a FACTION (horde/alliance)").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa claimfor <guildName> - Open claim GUI for a GUILD").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa unclaimguild <guild> [all] - Unclaim guild chunks").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa transferclaim <from> <to> - Transfer claim ownership").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa bypass - Toggle admin bypass (for testing as user)").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa addeditor <player> - Grant player edit access to faction claims").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa removeeditor <player> - Revoke player edit access to faction claims").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa listeditors - List all faction claim editors").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa config list - List all config values").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa config get <key> - Get a config value").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa config set <key> <value> - Set a config value").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa config reload - Reload config from database").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa faction list - List all factions").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa faction create <id> <name> <color> - Create faction").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa faction setspawn <id> - Set faction spawn to current pos").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa faction delete <id> - Delete a faction").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa faction reload - Reload factions from database").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa tp <faction> - Teleport to faction spawn").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa bl blocked [list|add|remove] - Manage protected blocks").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa bl allowed [list|add|remove] - Manage always-allowed blocks").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa bl reload/reset - Reload or reset protection lists").color(Color.YELLOW));
    }

    private void handleBypassToggle(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Message.raw("This command can only be used by a player.").color(Color.RED));
            return;
        }

        boolean bypassEnabled = HC_FactionsPlugin.toggleBypass(player.getUuid());

        if (bypassEnabled) {
            sender.sendMessage(Message.raw("Admin bypass ENABLED - You can now modify protected territory.").color(Color.GREEN));
        } else {
            sender.sendMessage(Message.raw("Admin bypass DISABLED - You will now be affected by protection systems like a regular player.").color(Color.ORANGE));
            sender.sendMessage(Message.raw("Use '/fa bypasstoggle' again to re-enable bypass.").color(Color.GRAY));
        }
    }

    private void handleAddEditor(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Message.raw("Usage: /fa addeditor <player>").color(Color.RED));
            return;
        }

        String playerName = args[1];
        PlayerRef playerRef = findOnlinePlayer(playerName);
        if (playerRef == null) {
            sender.sendMessage(Message.raw("Player '" + playerName + "' not found or not online.").color(Color.RED));
            return;
        }

        if (HC_FactionsPlugin.addFactionEditor(playerRef.getUuid())) {
            sender.sendMessage(Message.raw("Granted " + playerRef.getUsername() + " editor access to faction claims.").color(Color.GREEN));
            playerRef.sendMessage(Message.raw("You have been granted edit access to faction-claimed land.").color(Color.CYAN));
        } else {
            sender.sendMessage(Message.raw(playerRef.getUsername() + " is already a faction editor.").color(Color.YELLOW));
        }
    }

    private void handleRemoveEditor(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Message.raw("Usage: /fa removeeditor <player>").color(Color.RED));
            return;
        }

        String playerName = args[1];
        PlayerRef playerRef = findOnlinePlayer(playerName);
        if (playerRef == null) {
            sender.sendMessage(Message.raw("Player '" + playerName + "' not found or not online.").color(Color.RED));
            return;
        }

        if (HC_FactionsPlugin.removeFactionEditor(playerRef.getUuid())) {
            sender.sendMessage(Message.raw("Revoked " + playerRef.getUsername() + "'s editor access to faction claims.").color(Color.GREEN));
            playerRef.sendMessage(Message.raw("Your edit access to faction-claimed land has been revoked.").color(Color.ORANGE));
        } else {
            sender.sendMessage(Message.raw(playerRef.getUsername() + " is not a faction editor.").color(Color.YELLOW));
        }
    }

    private void handleListEditors(CommandSender sender) {
        Set<UUID> editors = HC_FactionsPlugin.getFactionEditors();
        if (editors.isEmpty()) {
            sender.sendMessage(Message.raw("No faction editors currently assigned.").color(Color.YELLOW));
            return;
        }

        sender.sendMessage(Message.raw("=== Faction Editors (" + editors.size() + ") ===").color(Color.ORANGE));
        for (UUID editorUuid : editors) {
            PlayerRef online = findOnlinePlayerByUuid(editorUuid);
            String name = online != null ? online.getUsername() : editorUuid.toString();
            String status = online != null ? " (online)" : " (offline)";
            sender.sendMessage(Message.raw("  - " + name + status).color(Color.YELLOW));
        }
    }

    private PlayerRef findOnlinePlayerByUuid(UUID uuid) {
        return Universe.get().getPlayer(uuid);
    }

    // Hardcoded faction spawn coordinates
    private static final double VALOR_SPAWN_X = 580;
    private static final double VALOR_SPAWN_Y = 132;
    private static final double VALOR_SPAWN_Z = 126;

    private static final double LEGION_SPAWN_X = -1315;
    private static final double LEGION_SPAWN_Y = 117;
    private static final double LEGION_SPAWN_Z = 402;

    /**
     * Teleport player to their faction's hardcoded spawn point.
     */
    private void teleportToFactionSpawn(PlayerRef playerRef, World fromWorld, String factionId) {
        World targetWorld = Universe.get().getDefaultWorld();

        if (targetWorld == null) {
            playerRef.sendMessage(Message.raw("Could not find target world.").color(Color.RED));
            return;
        }

        // Get hardcoded spawn for this faction
        double spawnX, spawnY, spawnZ;
        String factionLower = factionId.toLowerCase();

        if (factionLower.equals("alliance") || factionLower.equals("valor")) {
            spawnX = VALOR_SPAWN_X;
            spawnY = VALOR_SPAWN_Y;
            spawnZ = VALOR_SPAWN_Z;
        } else if (factionLower.equals("horde") || factionLower.equals("legion")) {
            spawnX = LEGION_SPAWN_X;
            spawnY = LEGION_SPAWN_Y;
            spawnZ = LEGION_SPAWN_Z;
        } else {
            // Unknown faction, use world spawn
            playerRef.sendMessage(Message.raw("Unknown faction, teleporting to world spawn.").color(Color.YELLOW));
            spawnX = 0;
            spawnY = 100;
            spawnZ = 0;
        }

        plugin.getLogger().at(Level.INFO).log("[Faction] Teleporting " + playerRef.getUsername() +
            " to " + factionId + " spawn at " + spawnX + ", " + spawnY + ", " + spawnZ);

        doTeleport(playerRef, fromWorld, targetWorld, spawnX, spawnY, spawnZ);
        playerRef.sendMessage(Message.raw("Welcome to your faction territory!").color(Color.GREEN));
    }

    /**
     * Execute the cross-world teleport.
     */
    private void doTeleport(PlayerRef playerRef, World fromWorld, World targetWorld, double x, double y, double z) {
        fromWorld.execute(() -> {
            try {
                Ref<EntityStore> playerEntityRef = playerRef.getReference();
                if (playerEntityRef == null || !playerEntityRef.isValid()) {
                    plugin.getLogger().at(Level.WARNING).log("[RTP] Could not find player to teleport: " + playerRef.getUsername());
                    return;
                }

                Store<EntityStore> store = playerEntityRef.getStore();

                // Clear interactions
                InteractionManager interactionManager = store.getComponent(playerEntityRef,
                    InteractionModule.get().getInteractionManagerComponent());
                if (interactionManager != null) {
                    interactionManager.clear();
                }

                // Cross-world teleport
                Vector3d targetPos = new Vector3d(x, y, z);
                Vector3f rotation = new Vector3f(0, 0, 0);
                store.addComponent(playerEntityRef, Teleport.getComponentType(),
                    Teleport.createForPlayer(targetWorld, targetPos, rotation));

                plugin.getLogger().at(Level.INFO).log("[RTP] Teleported " + playerRef.getUsername() + " to " +
                    targetWorld.getName() + " at " + x + ", " + y + ", " + z);

            } catch (Exception e) {
                plugin.getLogger().at(Level.SEVERE).log("[RTP] Error during teleport: " + e.getMessage());
            }
        });
    }

    private void handleConfig(CommandSender sender, String[] args) {
        if (args.length < 2) {
            showConfigHelp(sender);
            return;
        }

        String action = args[1].toLowerCase();

        switch (action) {
            case "list" -> handleConfigList(sender);
            case "get" -> handleConfigGet(sender, args);
            case "set" -> handleConfigSet(sender, args);
            case "reload" -> handleConfigReload(sender);
            default -> showConfigHelp(sender);
        }
    }

    private void showConfigHelp(CommandSender sender) {
        sender.sendMessage(Message.raw("=== Config Commands ===").color(Color.ORANGE));
        sender.sendMessage(Message.raw("/fa config list - List all config values").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa config get <key> - Get a specific config value").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa config set <key> <value> - Set a config value").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa config reload - Reload config from database").color(Color.YELLOW));
        sender.sendMessage(Message.raw("").color(Color.WHITE));
        sender.sendMessage(Message.raw("Available config keys:").color(Color.GRAY));
        sender.sendMessage(Message.raw("  guild.maxNameLength, guild.minNameLength").color(Color.WHITE));
        sender.sendMessage(Message.raw("  guild.baseClaimsPerGuild, guild.claimsPerAdditionalMember").color(Color.WHITE));
        sender.sendMessage(Message.raw("  guild.maxMembers, guild.defaultPower, guild.powerPerClaim").color(Color.WHITE));
        sender.sendMessage(Message.raw("  guild.homeCooldownSeconds").color(Color.WHITE));
        sender.sendMessage(Message.raw("  protection.enemyCanDestroy, protection.enemyCanBuild").color(Color.WHITE));
        sender.sendMessage(Message.raw("  protection.sameFactionGuildAccess").color(Color.WHITE));
        sender.sendMessage(Message.raw("  pvp.allowSameFactionPvp, pvp.protectNoFaction").color(Color.WHITE));
    }

    private void handleConfigList(CommandSender sender) {
        FactionGuildsConfig config = plugin.getConfig();

        sender.sendMessage(Message.raw("=== FactionGuilds Configuration ===").color(Color.ORANGE));

        // Guild settings
        sender.sendMessage(Message.raw("--- Guild Settings ---").color(Color.CYAN));
        sender.sendMessage(Message.raw("  guild.maxNameLength = " + config.getGuildMaxNameLength()).color(Color.WHITE));
        sender.sendMessage(Message.raw("  guild.minNameLength = " + config.getGuildMinNameLength()).color(Color.WHITE));
        sender.sendMessage(Message.raw("  guild.baseClaimsPerGuild = " + config.getGuildBaseClaimsPerGuild() + " (base claims for 1 member)").color(Color.WHITE));
        sender.sendMessage(Message.raw("  guild.claimsPerAdditionalMember = " + config.getGuildClaimsPerAdditionalMember() + " (per extra member)").color(Color.WHITE));
        sender.sendMessage(Message.raw("  guild.maxMembers = " + config.getGuildMaxMembers()).color(Color.WHITE));
        sender.sendMessage(Message.raw("  guild.defaultPower = " + config.getGuildDefaultPower()).color(Color.WHITE));
        sender.sendMessage(Message.raw("  guild.powerPerClaim = " + config.getGuildPowerPerClaim()).color(Color.WHITE));
        sender.sendMessage(Message.raw("  guild.homeCooldownSeconds = " + config.getGuildHomeCooldownSeconds()).color(Color.WHITE));

        // Protection settings
        sender.sendMessage(Message.raw("--- Protection Settings ---").color(Color.CYAN));
        sender.sendMessage(Message.raw("  protection.enemyCanDestroy = " + config.isProtectionEnemyCanDestroy()).color(Color.WHITE));
        sender.sendMessage(Message.raw("  protection.enemyCanBuild = " + config.isProtectionEnemyCanBuild()).color(Color.WHITE));
        sender.sendMessage(Message.raw("  protection.sameFactionGuildAccess = " + config.isProtectionSameFactionGuildAccess()).color(Color.WHITE));

        // PvP settings
        sender.sendMessage(Message.raw("--- PvP Settings ---").color(Color.CYAN));
        sender.sendMessage(Message.raw("  pvp.allowSameFactionPvp = " + config.isPvpAllowSameFactionPvp()).color(Color.WHITE));
        sender.sendMessage(Message.raw("  pvp.protectNoFaction = " + config.isPvpProtectNoFaction()).color(Color.WHITE));
    }

    private void handleConfigGet(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Message.raw("Usage: /fa config get <key>").color(Color.RED));
            return;
        }

        String key = args[2];
        String normalizedKey = normalizeConfigKey(key);
        if (normalizedKey == null) {
            sender.sendMessage(Message.raw("Unknown config key: " + key).color(Color.RED));
            sender.sendMessage(Message.raw("Use '/fa config list' to see all available keys.").color(Color.GRAY));
            return;
        }

        FactionGuildsConfig config = plugin.getConfig();
        String value = getConfigValue(config, key);

        sender.sendMessage(Message.raw(normalizedKey + " = " + value).color(Color.GREEN));
    }

    private void handleConfigSet(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(Message.raw("Usage: /fa config set <key> <value>").color(Color.RED));
            return;
        }

        String key = args[2];
        String value = args[3];

        // Normalize the key to canonical form
        String normalizedKey = normalizeConfigKey(key);
        if (normalizedKey == null) {
            sender.sendMessage(Message.raw("Unknown config key: " + key).color(Color.RED));
            sender.sendMessage(Message.raw("Use '/fa config list' to see all available keys.").color(Color.GRAY));
            return;
        }

        // Validate the value type
        if (!validateConfigValue(key, value)) {
            sender.sendMessage(Message.raw("Invalid value for " + normalizedKey + ": " + value).color(Color.RED));
            if (normalizedKey.startsWith("guild.")) {
                sender.sendMessage(Message.raw("Expected: integer (number)").color(Color.GRAY));
            } else {
                sender.sendMessage(Message.raw("Expected: true or false").color(Color.GRAY));
            }
            return;
        }

        // Update in database and in-memory (use normalized key)
        FactionGuildsConfig config = plugin.getConfig();
        boolean success = plugin.getConfigRepository().updateConfigValue(config, normalizedKey, value);

        if (success) {
            sender.sendMessage(Message.raw("Config updated: " + normalizedKey + " = " + value).color(Color.GREEN));
            sender.sendMessage(Message.raw("Change is effective immediately.").color(Color.GRAY));
        } else {
            sender.sendMessage(Message.raw("Failed to update config. Check server logs.").color(Color.RED));
        }
    }

    private void handleConfigReload(CommandSender sender) {
        sender.sendMessage(Message.raw("Reloading config from database...").color(Color.YELLOW));

        try {
            // Load fresh config from database
            FactionGuildsConfig newConfig = plugin.getConfigRepository().loadConfig();

            // Update the plugin's config reference
            // Note: We need to copy values since we can't replace the reference
            FactionGuildsConfig currentConfig = plugin.getConfig();
            currentConfig.setGuildMaxNameLength(newConfig.getGuildMaxNameLength());
            currentConfig.setGuildMinNameLength(newConfig.getGuildMinNameLength());
            currentConfig.setGuildBaseClaimsPerGuild(newConfig.getGuildBaseClaimsPerGuild());
            currentConfig.setGuildClaimsPerAdditionalMember(newConfig.getGuildClaimsPerAdditionalMember());
            currentConfig.setGuildMaxMembers(newConfig.getGuildMaxMembers());
            currentConfig.setGuildDefaultPower(newConfig.getGuildDefaultPower());
            currentConfig.setGuildPowerPerClaim(newConfig.getGuildPowerPerClaim());
            currentConfig.setGuildHomeCooldownSeconds(newConfig.getGuildHomeCooldownSeconds());
            currentConfig.setProtectionEnemyCanDestroy(newConfig.isProtectionEnemyCanDestroy());
            currentConfig.setProtectionEnemyCanBuild(newConfig.isProtectionEnemyCanBuild());
            currentConfig.setProtectionSameFactionGuildAccess(newConfig.isProtectionSameFactionGuildAccess());
            currentConfig.setPvpAllowSameFactionPvp(newConfig.isPvpAllowSameFactionPvp());
            currentConfig.setPvpProtectNoFaction(newConfig.isPvpProtectNoFaction());

            sender.sendMessage(Message.raw("Config reloaded successfully!").color(Color.GREEN));
        } catch (Exception e) {
            sender.sendMessage(Message.raw("Failed to reload config: " + e.getMessage()).color(Color.RED));
        }
    }

    /**
     * Normalizes a config key to its canonical camelCase form.
     * Accepts case-insensitive input like "guild.maxclaims" and returns "guild.maxClaims".
     */
    private String normalizeConfigKey(String key) {
        String lowerKey = key.toLowerCase();
        return switch (lowerKey) {
            case "guild.maxnamelength" -> "guild.maxNameLength";
            case "guild.minnamelength" -> "guild.minNameLength";
            case "guild.baseclaimsperguild" -> "guild.baseClaimsPerGuild";
            case "guild.claimsperadditionalmember" -> "guild.claimsPerAdditionalMember";
            case "guild.maxmembers" -> "guild.maxMembers";
            case "guild.defaultpower" -> "guild.defaultPower";
            case "guild.powerperclaim" -> "guild.powerPerClaim";
            case "guild.homecooldownseconds" -> "guild.homeCooldownSeconds";
            case "protection.enemycandestroy" -> "protection.enemyCanDestroy";
            case "protection.enemycanbuild" -> "protection.enemyCanBuild";
            case "protection.samefactionguildaccess" -> "protection.sameFactionGuildAccess";
            case "pvp.allowsamefactionpvp" -> "pvp.allowSameFactionPvp";
            case "pvp.protectnofaction" -> "pvp.protectNoFaction";
            default -> null;
        };
    }

    /**
     * Gets a config value by key.
     */
    private String getConfigValue(FactionGuildsConfig config, String key) {
        String normalizedKey = normalizeConfigKey(key);
        if (normalizedKey == null) return null;

        return switch (normalizedKey) {
            case "guild.maxNameLength" -> String.valueOf(config.getGuildMaxNameLength());
            case "guild.minNameLength" -> String.valueOf(config.getGuildMinNameLength());
            case "guild.baseClaimsPerGuild" -> String.valueOf(config.getGuildBaseClaimsPerGuild());
            case "guild.claimsPerAdditionalMember" -> String.valueOf(config.getGuildClaimsPerAdditionalMember());
            case "guild.maxMembers" -> String.valueOf(config.getGuildMaxMembers());
            case "guild.defaultPower" -> String.valueOf(config.getGuildDefaultPower());
            case "guild.powerPerClaim" -> String.valueOf(config.getGuildPowerPerClaim());
            case "guild.homeCooldownSeconds" -> String.valueOf(config.getGuildHomeCooldownSeconds());
            case "protection.enemyCanDestroy" -> String.valueOf(config.isProtectionEnemyCanDestroy());
            case "protection.enemyCanBuild" -> String.valueOf(config.isProtectionEnemyCanBuild());
            case "protection.sameFactionGuildAccess" -> String.valueOf(config.isProtectionSameFactionGuildAccess());
            case "pvp.allowSameFactionPvp" -> String.valueOf(config.isPvpAllowSameFactionPvp());
            case "pvp.protectNoFaction" -> String.valueOf(config.isPvpProtectNoFaction());
            default -> null;
        };
    }

    /**
     * Validates a config value for a given key.
     */
    private boolean validateConfigValue(String key, String value) {
        String normalizedKey = normalizeConfigKey(key);
        if (normalizedKey == null) return false;

        // Guild settings are integers
        if (normalizedKey.startsWith("guild.")) {
            try {
                Integer.parseInt(value);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        // Protection and PvP settings are booleans
        return value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false");
    }

    // ═══════════════════════════════════════════════════════
    // FACTION COMMANDS
    // ═══════════════════════════════════════════════════════

    private void handleFaction(CommandSender sender, String[] args) {
        if (args.length < 2) {
            showFactionHelp(sender);
            return;
        }

        String action = args[1].toLowerCase();

        switch (action) {
            case "list" -> handleFactionList(sender);
            case "info" -> handleFactionInfo(sender, args);
            case "create" -> handleFactionCreate(sender, args);
            case "setspawn" -> handleFactionSetSpawn(sender, args);
            case "delete" -> handleFactionDelete(sender, args);
            case "reload" -> handleFactionReload(sender);
            default -> showFactionHelp(sender);
        }
    }

    private void showFactionHelp(CommandSender sender) {
        sender.sendMessage(Message.raw("=== Faction Commands ===").color(Color.ORANGE));
        sender.sendMessage(Message.raw("/fa faction list - List all factions").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa faction info <id> - Show faction details").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa faction create <id> <name> <color> - Create a new faction").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa faction setspawn <id> - Set spawn to current position").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa faction delete <id> - Delete a faction").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa faction reload - Reload factions from database").color(Color.YELLOW));
        sender.sendMessage(Message.raw("").color(Color.WHITE));
        sender.sendMessage(Message.raw("Color format: #RRGGBB (e.g., #FF0000 for red)").color(Color.GRAY));
    }

    private void handleFactionList(CommandSender sender) {
        var factions = plugin.getFactionManager().getFactions();

        sender.sendMessage(Message.raw("=== Factions (" + factions.size() + ") ===").color(Color.ORANGE));

        if (factions.isEmpty()) {
            sender.sendMessage(Message.raw("No factions defined.").color(Color.GRAY));
            return;
        }

        for (Faction faction : factions) {
            sender.sendMessage(Message.raw("- " + faction.getId() + ": " + faction.getDisplayName() + " [" + faction.getColorHex() + "]").color(faction.getColor()));
        }
        
        sender.sendMessage(Message.raw("Use '/fa faction info <id>' for details.").color(Color.GRAY));
    }

    private void handleFactionInfo(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Message.raw("Usage: /fa faction info <factionId>").color(Color.RED));
            return;
        }

        String factionId = args[2].toLowerCase();
        Faction faction = plugin.getFactionManager().getFaction(factionId);

        if (faction == null) {
            sender.sendMessage(Message.raw("Faction '" + factionId + "' not found.").color(Color.RED));
            sender.sendMessage(Message.raw("Available factions: " + String.join(", ", plugin.getFactionManager().getFactionIds())).color(Color.GRAY));
            return;
        }

        // Get member/guild counts from database
        int memberCount = 0;
        int guildCount = 0;
        try {
            var guilds = plugin.getGuildRepository().getGuildsByFaction(factionId);
            guildCount = guilds.size();
            for (var guild : guilds) {
                memberCount += plugin.getGuildManager().getMemberCount(guild.getId());
            }
        } catch (Exception e) {
            // Ignore if we can't get counts
        }

        sender.sendMessage(Message.raw("=== Faction: " + faction.getDisplayName() + " ===").color(faction.getColor()));
        sender.sendMessage(Message.raw("  ID: " + faction.getId()).color(Color.WHITE));
        sender.sendMessage(Message.raw("  Display Name: " + faction.getDisplayName()).color(Color.WHITE));
        sender.sendMessage(Message.raw("  Color: " + faction.getColorHex()).color(faction.getColor()));
        sender.sendMessage(Message.raw("  Short Name: " + faction.getShortName()).color(Color.WHITE));
        sender.sendMessage(Message.raw("  Spawn World: " + faction.getSpawnWorld()).color(Color.WHITE));
        sender.sendMessage(Message.raw("  Spawn Position: " + String.format("%.2f, %.2f, %.2f", faction.getSpawnX(), faction.getSpawnY(), faction.getSpawnZ())).color(Color.WHITE));
        sender.sendMessage(Message.raw("  Guilds: " + guildCount).color(Color.WHITE));
        sender.sendMessage(Message.raw("  Total Members: " + memberCount).color(Color.WHITE));
    }

    private void handleFactionCreate(CommandSender sender, String[] args) {
        if (args.length < 5) {
            sender.sendMessage(Message.raw("Usage: /fa faction create <id> <displayName> <color>").color(Color.RED));
            sender.sendMessage(Message.raw("Example: /fa faction create rebels The Rebels #00FF00").color(Color.GRAY));
            return;
        }

        String factionId = args[2].toLowerCase();
        String displayName = args[3];
        String color = args[4];

        // Validate color format
        if (!color.matches("^#[0-9A-Fa-f]{6}$")) {
            sender.sendMessage(Message.raw("Invalid color format. Use #RRGGBB (e.g., #FF0000)").color(Color.RED));
            return;
        }

        // Check if faction already exists
        if (plugin.getFactionManager().isValidFaction(factionId)) {
            sender.sendMessage(Message.raw("Faction '" + factionId + "' already exists.").color(Color.RED));
            return;
        }

        // Create with default spawn (can be updated with setspawn)
        Faction faction = new Faction(factionId, displayName, color, "default", 0.0, 64.0, 0.0);

        if (plugin.getFactionManager().createFaction(faction)) {
            sender.sendMessage(Message.raw("Created faction: " + displayName + " (" + factionId + ")").color(Color.GREEN));
            sender.sendMessage(Message.raw("Use '/fa faction setspawn " + factionId + "' to set the spawn point.").color(Color.GRAY));
        } else {
            sender.sendMessage(Message.raw("Failed to create faction.").color(Color.RED));
        }
    }

    private void handleFactionSetSpawn(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Message.raw("Usage: /fa faction setspawn <factionId>").color(Color.RED));
            return;
        }

        String factionId = args[2].toLowerCase();

        Faction faction = plugin.getFactionManager().getFaction(factionId);
        if (faction == null) {
            sender.sendMessage(Message.raw("Faction '" + factionId + "' not found.").color(Color.RED));
            return;
        }

        // This command requires a player in-game
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Message.raw("This command can only be used by a player in-game.").color(Color.RED));
            return;
        }

        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) {
            sender.sendMessage(Message.raw("Could not get player reference.").color(Color.RED));
            return;
        }

        // Execute on world thread to safely access store components
        player.getWorld().execute(() -> {
            Store<EntityStore> store = ref.getStore();
            TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
            if (transform == null) {
                sender.sendMessage(Message.raw("Could not get player position.").color(Color.RED));
                return;
            }

            String worldName = player.getWorld().getName();
            double x = transform.getPosition().getX();
            double y = transform.getPosition().getY();
            double z = transform.getPosition().getZ();

            // Create updated faction
            Faction updatedFaction = new Faction(
                faction.getId(),
                faction.getDisplayName(),
                faction.getColorHex(),
                worldName, x, y, z
            );

            if (plugin.getFactionManager().updateFaction(updatedFaction)) {
                sender.sendMessage(Message.raw("Updated " + faction.getDisplayName() + " spawn to: " + worldName + " @ " + 
                    String.format("%.1f, %.1f, %.1f", x, y, z)).color(Color.GREEN));
            } else {
                sender.sendMessage(Message.raw("Failed to update faction spawn.").color(Color.RED));
            }
        });
    }

    private void handleFactionDelete(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Message.raw("Usage: /fa faction delete <factionId>").color(Color.RED));
            return;
        }

        String factionId = args[2].toLowerCase();

        Faction faction = plugin.getFactionManager().getFaction(factionId);
        if (faction == null) {
            sender.sendMessage(Message.raw("Faction '" + factionId + "' not found.").color(Color.RED));
            return;
        }

        // Warn about consequences
        sender.sendMessage(Message.raw("WARNING: Deleting a faction does not automatically handle players/guilds in that faction!").color(Color.RED));

        if (plugin.getFactionManager().deleteFaction(factionId)) {
            sender.sendMessage(Message.raw("Deleted faction: " + faction.getDisplayName()).color(Color.GREEN));
        } else {
            sender.sendMessage(Message.raw("Failed to delete faction.").color(Color.RED));
        }
    }

    private void handleFactionReload(CommandSender sender) {
        plugin.getFactionManager().reloadFactions();
        sender.sendMessage(Message.raw("Reloaded " + plugin.getFactionManager().getFactions().size() + " factions from database.").color(Color.GREEN));
    }

    private void handleTeleport(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Message.raw("Usage: /fa tp <factionId>").color(Color.RED));
            sender.sendMessage(Message.raw("Available factions: " + String.join(", ", plugin.getFactionManager().getFactionIds())).color(Color.GRAY));
            return;
        }

        String factionId = args[1].toLowerCase();

        Faction faction = plugin.getFactionManager().getFaction(factionId);
        if (faction == null) {
            sender.sendMessage(Message.raw("Faction '" + factionId + "' not found.").color(Color.RED));
            sender.sendMessage(Message.raw("Available factions: " + String.join(", ", plugin.getFactionManager().getFactionIds())).color(Color.GRAY));
            return;
        }

        // This command requires a player in-game
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Message.raw("This command can only be used by a player in-game.").color(Color.RED));
            return;
        }

        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) {
            sender.sendMessage(Message.raw("Could not get player reference.").color(Color.RED));
            return;
        }

        // Get target world
        String targetWorldName = faction.getSpawnWorld();
        var targetWorld = Universe.get().getWorld(targetWorldName);
        
        if (targetWorld == null) {
            sender.sendMessage(Message.raw("Target world '" + targetWorldName + "' not found.").color(Color.RED));
            return;
        }

        // Teleport on world thread using Teleport component
        player.getWorld().execute(() -> {
            Store<EntityStore> store = ref.getStore();
            
            com.hypixel.hytale.math.vector.Vector3d spawnPos = new com.hypixel.hytale.math.vector.Vector3d(
                faction.getSpawnX(), 
                faction.getSpawnY(), 
                faction.getSpawnZ()
            );
            com.hypixel.hytale.math.vector.Vector3f rotation = new com.hypixel.hytale.math.vector.Vector3f(0, 0, 0);
            
            com.hypixel.hytale.server.core.modules.entity.teleport.Teleport teleport = 
                new com.hypixel.hytale.server.core.modules.entity.teleport.Teleport(targetWorld, spawnPos, rotation);
            store.addComponent(ref, com.hypixel.hytale.server.core.modules.entity.teleport.Teleport.getComponentType(), teleport);
            
            sender.sendMessage(Message.raw("Teleported to " + faction.getDisplayName() + " spawn!").color(Color.GREEN));
        });
    }

    private void handleMapRefresh(CommandSender sender) {
        ClaimMapManager.getInstance().refreshAllExistingClaims();
        sender.sendMessage(Message.raw("Queued all claims for map refresh. Open your map to see updated claims.").color(Color.GREEN));
    }

    private void handleClaim(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Message.raw("Usage: /fa claim <faction>").color(Color.RED));
            sender.sendMessage(Message.raw("Valid factions: " + String.join(", ", plugin.getFactionManager().getFactionIds())).color(Color.GRAY));
            return;
        }

        String factionId = args[1].toLowerCase();

        if (!plugin.getFactionManager().isValidFaction(factionId)) {
            sender.sendMessage(Message.raw("Invalid faction: " + factionId).color(Color.RED));
            sender.sendMessage(Message.raw("Valid factions: " + String.join(", ", plugin.getFactionManager().getFactionIds())).color(Color.GRAY));
            return;
        }

        // This command requires a player in-game
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Message.raw("This command can only be used by a player in-game.").color(Color.RED));
            return;
        }

        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) {
            sender.sendMessage(Message.raw("Could not get player reference.").color(Color.RED));
            return;
        }

        // Execute on world thread to safely access store components
        player.getWorld().execute(() -> {
            Store<EntityStore> store = ref.getStore();
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null) {
                sender.sendMessage(Message.raw("Could not get player reference.").color(Color.RED));
                return;
            }

            // Get player's current position and convert to chunk coordinates
            TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
            if (transform == null) {
                sender.sendMessage(Message.raw("Could not get player position.").color(Color.RED));
                return;
            }

            String worldName = player.getWorld().getName();
            int chunkX = ClaimManager.toChunkCoord(transform.getPosition().getX());
            int chunkZ = ClaimManager.toChunkCoord(transform.getPosition().getZ());

            // Open the faction claim GUI
            FactionClaimGui gui = new FactionClaimGui(plugin, playerRef, factionId, worldName, chunkX, chunkZ);
            player.getPageManager().openCustomPage(ref, store, gui);

            Faction faction = plugin.getFactionManager().getFaction(factionId);
            sender.sendMessage(Message.raw("Opened faction claim GUI for " + faction.getDisplayName()).color(Color.GREEN));
        });
    }

    private void handleSetFaction(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Message.raw("Usage: /fa setfaction <player> <faction>").color(Color.RED));
            return;
        }

        String playerName = args[1];
        String factionId = args[2].toLowerCase();

        if (!plugin.getFactionManager().isValidFaction(factionId)) {
            sender.sendMessage(Message.raw("Invalid faction: " + factionId).color(Color.RED));
            sender.sendMessage(Message.raw("Valid factions: " + String.join(", ", plugin.getFactionManager().getFactionIds())).color(Color.GRAY));
            return;
        }

        PlayerRef playerRef = findOnlinePlayer(playerName);
        if (playerRef == null) {
            sender.sendMessage(Message.raw("Player '" + playerName + "' not found or not online.").color(Color.RED));
            return;
        }

        PlayerData playerData = plugin.getPlayerDataRepository().getOrCreatePlayerData(playerRef.getUuid());

        if (playerData.isInGuild()) {
            Guild guild = plugin.getGuildManager().getGuild(playerData.getGuildId());
            if (guild != null && !guild.getFactionId().equals(factionId)) {
                plugin.getGuildManager().leaveGuild(playerRef.getUuid());
                sender.sendMessage(Message.raw("Removed player from their guild (different faction).").color(Color.YELLOW));
            }
        }

        playerData.setPlayerName(playerRef.getUsername());
        playerData.setFactionId(factionId);
        plugin.getPlayerDataRepository().savePlayerData(playerData);

        Faction faction = plugin.getFactionManager().getFaction(factionId);
        sender.sendMessage(Message.raw("Set " + playerName + "'s faction to " + faction.getDisplayName()).color(Color.GREEN));
        playerRef.sendMessage(Message.raw("An admin has set your faction to " + faction.getDisplayName()).color(Color.CYAN));
    }

    private void handleResetFaction(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Message.raw("Usage: /fa resetfaction <player>").color(Color.RED));
            return;
        }

        String playerName = args[1];
        PlayerRef playerRef = findOnlinePlayer(playerName);
        if (playerRef == null) {
            sender.sendMessage(Message.raw("Player '" + playerName + "' not found or not online.").color(Color.RED));
            return;
        }

        PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
        if (playerData == null) {
            sender.sendMessage(Message.raw("Player has no faction data.").color(Color.YELLOW));
            return;
        }

        if (playerData.isInGuild()) {
            Guild guild = plugin.getGuildManager().getGuild(playerData.getGuildId());
            if (guild != null && guild.getLeaderId().equals(playerRef.getUuid())) {
                plugin.getGuildManager().disbandGuild(guild.getId());
                sender.sendMessage(Message.raw("Disbanded player's guild (was leader).").color(Color.YELLOW));
            } else {
                plugin.getGuildManager().leaveGuild(playerRef.getUuid());
            }
        }

        playerData.setFactionId(null);
        playerData.setHasChosenFaction(false);
        plugin.getPlayerDataRepository().savePlayerData(playerData);

        sender.sendMessage(Message.raw("Reset " + playerName + "'s faction.").color(Color.GREEN));
        playerRef.sendMessage(Message.raw("An admin has reset your faction. Please select a new one.").color(Color.CYAN));
    }

    private void handleResetGuild(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Message.raw("Usage: /fa resetguild <player>").color(Color.RED));
            return;
        }

        String playerName = args[1];
        PlayerRef playerRef = findOnlinePlayer(playerName);
        if (playerRef == null) {
            sender.sendMessage(Message.raw("Player '" + playerName + "' not found or not online.").color(Color.RED));
            return;
        }

        PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
        if (playerData == null) {
            sender.sendMessage(Message.raw("Player has no data.").color(Color.YELLOW));
            return;
        }

        if (!playerData.isInGuild()) {
            sender.sendMessage(Message.raw("Player is not in a guild.").color(Color.YELLOW));
            return;
        }

        // Check if the guild exists
        UUID guildId = playerData.getGuildId();
        Guild guild = plugin.getGuildManager().getGuild(guildId);

        if (guild == null) {
            // Guild doesn't exist - this is orphaned data, just clear it
            sender.sendMessage(Message.raw("Player has orphaned guild reference (guild doesn't exist). Clearing...").color(Color.YELLOW));
        } else {
            sender.sendMessage(Message.raw("Removing player from guild: " + guild.getName()).color(Color.YELLOW));
        }

        // Clear guild data
        playerData.leaveGuild();
        plugin.getPlayerDataRepository().savePlayerData(playerData);
        plugin.getGuildManager().invalidateCache(playerRef.getUuid());

        sender.sendMessage(Message.raw("Reset " + playerName + "'s guild association.").color(Color.GREEN));
        playerRef.sendMessage(Message.raw("An admin has removed you from your guild.").color(Color.CYAN));
    }

    private void handleDeleteGuild(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Message.raw("Usage: /fa deleteguild <guild>").color(Color.RED));
            return;
        }

        String guildName = args[1];
        Guild guild = plugin.getGuildManager().getGuildByName(guildName);
        if (guild == null) {
            sender.sendMessage(Message.raw("Guild '" + guildName + "' not found.").color(Color.RED));
            return;
        }

        String name = guild.getName();
        if (plugin.getGuildManager().disbandGuild(guild.getId())) {
            sender.sendMessage(Message.raw("Deleted guild: " + name).color(Color.GREEN));
        } else {
            sender.sendMessage(Message.raw("Failed to delete guild.").color(Color.RED));
        }
    }

    private void handleInfo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Message.raw("Usage: /fa info <player>").color(Color.RED));
            return;
        }

        String playerName = args[1];
        PlayerRef playerRef = findOnlinePlayer(playerName);
        if (playerRef == null) {
            sender.sendMessage(Message.raw("Player '" + playerName + "' not found or not online.").color(Color.RED));
            return;
        }

        PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
        if (playerData == null) {
            sender.sendMessage(Message.raw("Player has no data.").color(Color.YELLOW));
            return;
        }

        sender.sendMessage(Message.raw("=== " + playerName + " ===").color(Color.ORANGE));
        sender.sendMessage(Message.raw("UUID: " + playerRef.getUuid().toString()).color(Color.WHITE));

        if (playerData.hasChosenFaction()) {
            Faction faction = plugin.getFactionManager().getFaction(playerData.getFactionId());
            sender.sendMessage(Message.raw("Faction: " + (faction != null ? faction.getDisplayName() : playerData.getFactionId())).color(Color.WHITE));
        } else {
            sender.sendMessage(Message.raw("Faction: None").color(Color.YELLOW));
        }

        if (playerData.isInGuild()) {
            Guild guild = plugin.getGuildManager().getGuild(playerData.getGuildId());
            if (guild != null) {
                sender.sendMessage(Message.raw("Guild: " + guild.getName()).color(Color.WHITE));
                sender.sendMessage(Message.raw("Role: " + (playerData.getGuildRole() != null ? playerData.getGuildRole().getDisplayName() : "Unknown")).color(Color.WHITE));
            } else {
                sender.sendMessage(Message.raw("Guild: ORPHANED DATA (guild deleted)").color(Color.RED));
                sender.sendMessage(Message.raw("  Guild ID: " + playerData.getGuildId()).color(Color.GRAY));
                sender.sendMessage(Message.raw("  Use '/fa resetguild " + playerName + "' to fix").color(Color.YELLOW));
            }
        } else {
            sender.sendMessage(Message.raw("Guild: None").color(Color.YELLOW));
        }
    }

    private void handleListGuilds(CommandSender sender) {
        List<Guild> guilds = plugin.getGuildRepository().getAllGuilds();

        sender.sendMessage(Message.raw("=== All Guilds (" + guilds.size() + ") ===").color(Color.ORANGE));

        if (guilds.isEmpty()) {
            sender.sendMessage(Message.raw("No guilds exist.").color(Color.GRAY));
            return;
        }

        for (Guild guild : guilds) {
            Faction faction = plugin.getFactionManager().getFaction(guild.getFactionId());
            int memberCount = plugin.getGuildManager().getMemberCount(guild.getId());

            sender.sendMessage(Message.raw("- " + guild.getName() + " [" + (faction != null ? faction.getDisplayName() : guild.getFactionId()) + "] (" + memberCount + " members)").color(Color.WHITE));
        }
    }

    private void handleClaimFor(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Message.raw("Usage: /fa claimfor <guildName>").color(Color.RED));
            sender.sendMessage(Message.raw("Note: For faction claims use '/fa claim <faction>' instead.").color(Color.GRAY));
            return;
        }

        String guildName = args[1];
        Guild guild = plugin.getGuildManager().getGuildByName(guildName);
        if (guild == null) {
            // Check if they accidentally used a faction ID instead of a guild name
            if (plugin.getFactionManager().isValidFaction(guildName.toLowerCase())) {
                sender.sendMessage(Message.raw("'" + guildName + "' is a faction, not a guild.").color(Color.RED));
                sender.sendMessage(Message.raw("Use '/fa claim " + guildName.toLowerCase() + "' for faction claims.").color(Color.YELLOW));
                return;
            }
            sender.sendMessage(Message.raw("Guild '" + guildName + "' not found.").color(Color.RED));
            sender.sendMessage(Message.raw("Use '/fa listguilds' to see all guilds.").color(Color.GRAY));
            return;
        }

        // This command requires a player in-game
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Message.raw("This command can only be used by a player in-game.").color(Color.RED));
            return;
        }

        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) {
            sender.sendMessage(Message.raw("Could not get player reference.").color(Color.RED));
            return;
        }

        // Execute on world thread to safely access store components
        player.getWorld().execute(() -> {
            Store<EntityStore> store = ref.getStore();
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null) {
                sender.sendMessage(Message.raw("Could not get player reference.").color(Color.RED));
                return;
            }

            // Get player's current position and convert to chunk coordinates
            TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
            if (transform == null) {
                sender.sendMessage(Message.raw("Could not get player position.").color(Color.RED));
                return;
            }

            String worldName = player.getWorld().getName();
            int chunkX = ClaimManager.toChunkCoord(transform.getPosition().getX());
            int chunkZ = ClaimManager.toChunkCoord(transform.getPosition().getZ());

            // Open the admin guild claim GUI
            AdminGuildClaimGui gui = new AdminGuildClaimGui(plugin, playerRef, guild.getId(), guild.getFactionId(), worldName, chunkX, chunkZ);
            player.getPageManager().openCustomPage(ref, store, gui);

            sender.sendMessage(Message.raw("Opened admin claim GUI for guild: " + guild.getName()).color(Color.GREEN));
        });
    }

    private void handleUnclaimGuild(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Message.raw("Usage: /fa unclaimguild <guild> [all]").color(Color.RED));
            return;
        }

        String guildName = args[1];
        Guild guild = plugin.getGuildManager().getGuildByName(guildName);
        if (guild == null) {
            sender.sendMessage(Message.raw("Guild '" + guildName + "' not found.").color(Color.RED));
            return;
        }

        boolean unclaimAll = args.length >= 3 && args[2].equalsIgnoreCase("all");

        if (unclaimAll) {
            // Unclaim all guild chunks
            int claimCount = plugin.getClaimManager().getClaimCount(guild.getId());
            plugin.getClaimManager().unclaimAllForGuild(guild.getId());
            sender.sendMessage(Message.raw("Unclaimed all " + claimCount + " chunks for guild: " + guild.getName()).color(Color.GREEN));
        } else {
            // Unclaim current chunk only
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Message.raw("Use '/fa unclaimguild <guild> all' from console, or stand in chunk to unclaim.").color(Color.RED));
                return;
            }

            Ref<EntityStore> ref = player.getReference();
            if (ref == null || !ref.isValid()) {
                sender.sendMessage(Message.raw("Could not get player reference.").color(Color.RED));
                return;
            }

            // Execute on world thread to safely access store components
            player.getWorld().execute(() -> {
                Store<EntityStore> store = ref.getStore();
                TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
                if (transform == null) {
                    sender.sendMessage(Message.raw("Could not get player position.").color(Color.RED));
                    return;
                }

                String worldName = player.getWorld().getName();
                int chunkX = ClaimManager.toChunkCoord(transform.getPosition().getX());
                int chunkZ = ClaimManager.toChunkCoord(transform.getPosition().getZ());

                Claim claim = plugin.getClaimManager().getClaim(worldName, chunkX, chunkZ);
                if (claim == null) {
                    sender.sendMessage(Message.raw("This chunk is not claimed.").color(Color.RED));
                    return;
                }

                if (claim.isFactionClaim()) {
                    sender.sendMessage(Message.raw("This is a faction claim, not a guild claim. Use /fa claim to manage faction claims.").color(Color.RED));
                    return;
                }

                if (!guild.getId().equals(claim.getGuildId())) {
                    Guild claimOwner = plugin.getGuildManager().getGuild(claim.getGuildId());
                    sender.sendMessage(Message.raw("This chunk is claimed by " + (claimOwner != null ? claimOwner.getName() : "another guild") + ", not " + guild.getName()).color(Color.RED));
                    return;
                }

                if (plugin.getClaimManager().adminUnclaimGuildChunk(worldName, chunkX, chunkZ)) {
                    sender.sendMessage(Message.raw("Unclaimed chunk [" + chunkX + ", " + chunkZ + "] from guild: " + guild.getName()).color(Color.GREEN));
                } else {
                    sender.sendMessage(Message.raw("Failed to unclaim chunk.").color(Color.RED));
                }
            });
        }
    }

    private void handleTransferClaim(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Message.raw("Usage: /fa transferclaim <from-guild> <to-guild>").color(Color.RED));
            return;
        }

        String fromGuildName = args[1];
        String toGuildName = args[2];

        Guild fromGuild = plugin.getGuildManager().getGuildByName(fromGuildName);
        if (fromGuild == null) {
            sender.sendMessage(Message.raw("Source guild '" + fromGuildName + "' not found.").color(Color.RED));
            return;
        }

        Guild toGuild = plugin.getGuildManager().getGuildByName(toGuildName);
        if (toGuild == null) {
            sender.sendMessage(Message.raw("Target guild '" + toGuildName + "' not found.").color(Color.RED));
            return;
        }

        // This command requires a player in-game to determine which chunk
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Message.raw("This command can only be used by a player in-game.").color(Color.RED));
            return;
        }

        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) {
            sender.sendMessage(Message.raw("Could not get player reference.").color(Color.RED));
            return;
        }

        // Execute on world thread to safely access store components
        player.getWorld().execute(() -> {
            Store<EntityStore> store = ref.getStore();
            TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
            if (transform == null) {
                sender.sendMessage(Message.raw("Could not get player position.").color(Color.RED));
                return;
            }

            String worldName = player.getWorld().getName();
            int chunkX = ClaimManager.toChunkCoord(transform.getPosition().getX());
            int chunkZ = ClaimManager.toChunkCoord(transform.getPosition().getZ());

            Claim claim = plugin.getClaimManager().getClaim(worldName, chunkX, chunkZ);
            if (claim == null) {
                sender.sendMessage(Message.raw("This chunk is not claimed.").color(Color.RED));
                return;
            }

            if (claim.isFactionClaim()) {
                sender.sendMessage(Message.raw("Cannot transfer faction claims. Only guild claims can be transferred.").color(Color.RED));
                return;
            }

            if (!fromGuild.getId().equals(claim.getGuildId())) {
                Guild actualOwner = plugin.getGuildManager().getGuild(claim.getGuildId());
                sender.sendMessage(Message.raw("This chunk is claimed by " + (actualOwner != null ? actualOwner.getName() : "another guild") + ", not " + fromGuild.getName()).color(Color.RED));
                return;
            }

            if (plugin.getClaimManager().transferGuildClaim(worldName, chunkX, chunkZ, toGuild.getId())) {
                sender.sendMessage(Message.raw("Transferred chunk [" + chunkX + ", " + chunkZ + "] from " + fromGuild.getName() + " to " + toGuild.getName()).color(Color.GREEN));
            } else {
                sender.sendMessage(Message.raw("Failed to transfer claim.").color(Color.RED));
            }
        });
    }

    private PlayerRef findOnlinePlayer(String name) {
        for (PlayerRef ref : Universe.get().getPlayers()) {
            if (ref != null && ref.getUsername().equalsIgnoreCase(name)) {
                return ref;
            }
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════
    // INTERACTION PROTECTION COMMANDS (blocked/allowed lists)
    // ═══════════════════════════════════════════════════════

    private void handleBlacklist(CommandSender sender, String[] args) {
        if (args.length < 2) {
            showBlacklistHelp(sender);
            return;
        }

        String action = args[1].toLowerCase();

        switch (action) {
            case "blocked", "block" -> handleBlockedList(sender, args);
            case "allowed", "allow", "whitelist" -> handleAllowedList(sender, args);
            case "reload" -> handleProtectionReload(sender);
            case "reset" -> handleProtectionReset(sender);
            default -> showBlacklistHelp(sender);
        }
    }

    private void showBlacklistHelp(CommandSender sender) {
        sender.sendMessage(Message.raw("=== Interaction Protection Commands ===").color(Color.ORANGE));
        sender.sendMessage(Message.raw("Manages which blocks are protected/allowed in claims.").color(Color.GRAY));
        sender.sendMessage(Message.raw("").color(Color.GRAY));
        sender.sendMessage(Message.raw("BLOCKED list (protected in claims):").color(Color.RED));
        sender.sendMessage(Message.raw("/fa bl blocked list - List blocked patterns").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa bl blocked add <pattern> - Add (e.g., 'grass', 'flower')").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa bl blocked remove <pattern> - Remove pattern").color(Color.YELLOW));
        sender.sendMessage(Message.raw("").color(Color.GRAY));
        sender.sendMessage(Message.raw("ALLOWED list (always interactable):").color(Color.GREEN));
        sender.sendMessage(Message.raw("/fa bl allowed list - List allowed patterns").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa bl allowed add <pattern> - Add (e.g., 'portal', 'quest')").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa bl allowed remove <pattern> - Remove pattern").color(Color.YELLOW));
        sender.sendMessage(Message.raw("").color(Color.GRAY));
        sender.sendMessage(Message.raw("/fa bl reload - Reload both lists from files").color(Color.YELLOW));
        sender.sendMessage(Message.raw("/fa bl reset - Reset both lists to defaults").color(Color.YELLOW));
    }

    private void handleBlockedList(CommandSender sender, String[] args) {
        if (args.length < 3) {
            // Show blocked list
            var manager = plugin.getPickupBlacklistManager();
            var patterns = manager.getBlockedPatterns();
            sender.sendMessage(Message.raw("=== BLOCKED Patterns (" + patterns.size() + ") ===").color(Color.RED));
            sender.sendMessage(Message.raw("These blocks are PROTECTED in claims.").color(Color.GRAY));
            displayPatterns(sender, patterns, Color.YELLOW);
            return;
        }

        String subAction = args[2].toLowerCase();
        var manager = plugin.getPickupBlacklistManager();

        switch (subAction) {
            case "list" -> {
                var patterns = manager.getBlockedPatterns();
                sender.sendMessage(Message.raw("=== BLOCKED Patterns (" + patterns.size() + ") ===").color(Color.RED));
                displayPatterns(sender, patterns, Color.YELLOW);
            }
            case "add" -> {
                if (args.length < 4) {
                    sender.sendMessage(Message.raw("Usage: /fa bl blocked add <pattern>").color(Color.RED));
                    return;
                }
                String pattern = args[3].toLowerCase().trim();
                if (manager.addBlockedPattern(pattern)) {
                    sender.sendMessage(Message.raw("Added '" + pattern + "' to BLOCKED list.").color(Color.GREEN));
                } else {
                    sender.sendMessage(Message.raw("Pattern '" + pattern + "' already exists.").color(Color.YELLOW));
                }
            }
            case "remove", "rm" -> {
                if (args.length < 4) {
                    sender.sendMessage(Message.raw("Usage: /fa bl blocked remove <pattern>").color(Color.RED));
                    return;
                }
                String pattern = args[3].toLowerCase().trim();
                if (manager.removeBlockedPattern(pattern)) {
                    sender.sendMessage(Message.raw("Removed '" + pattern + "' from BLOCKED list.").color(Color.GREEN));
                } else {
                    sender.sendMessage(Message.raw("Pattern '" + pattern + "' not found.").color(Color.RED));
                }
            }
            default -> sender.sendMessage(Message.raw("Usage: /fa bl blocked [list|add|remove] <pattern>").color(Color.RED));
        }
    }

    private void handleAllowedList(CommandSender sender, String[] args) {
        if (args.length < 3) {
            // Show allowed list
            var manager = plugin.getPickupBlacklistManager();
            var patterns = manager.getAllowedPatterns();
            sender.sendMessage(Message.raw("=== ALLOWED Patterns (" + patterns.size() + ") ===").color(Color.GREEN));
            sender.sendMessage(Message.raw("These blocks are ALWAYS interactable (bypass protection).").color(Color.GRAY));
            displayPatterns(sender, patterns, Color.YELLOW);
            return;
        }

        String subAction = args[2].toLowerCase();
        var manager = plugin.getPickupBlacklistManager();

        switch (subAction) {
            case "list" -> {
                var patterns = manager.getAllowedPatterns();
                sender.sendMessage(Message.raw("=== ALLOWED Patterns (" + patterns.size() + ") ===").color(Color.GREEN));
                displayPatterns(sender, patterns, Color.YELLOW);
            }
            case "add" -> {
                if (args.length < 4) {
                    sender.sendMessage(Message.raw("Usage: /fa bl allowed add <pattern>").color(Color.RED));
                    return;
                }
                String pattern = args[3].toLowerCase().trim();
                if (manager.addAllowedPattern(pattern)) {
                    sender.sendMessage(Message.raw("Added '" + pattern + "' to ALLOWED list.").color(Color.GREEN));
                } else {
                    sender.sendMessage(Message.raw("Pattern '" + pattern + "' already exists.").color(Color.YELLOW));
                }
            }
            case "remove", "rm" -> {
                if (args.length < 4) {
                    sender.sendMessage(Message.raw("Usage: /fa bl allowed remove <pattern>").color(Color.RED));
                    return;
                }
                String pattern = args[3].toLowerCase().trim();
                if (manager.removeAllowedPattern(pattern)) {
                    sender.sendMessage(Message.raw("Removed '" + pattern + "' from ALLOWED list.").color(Color.GREEN));
                } else {
                    sender.sendMessage(Message.raw("Pattern '" + pattern + "' not found.").color(Color.RED));
                }
            }
            default -> sender.sendMessage(Message.raw("Usage: /fa bl allowed [list|add|remove] <pattern>").color(Color.RED));
        }
    }

    private void displayPatterns(CommandSender sender, java.util.Set<String> patterns, Color color) {
        if (patterns.isEmpty()) {
            sender.sendMessage(Message.raw("(none)").color(Color.GRAY));
            return;
        }
        StringBuilder row = new StringBuilder();
        int count = 0;
        for (String pattern : patterns) {
            if (count > 0) row.append(", ");
            row.append(pattern);
            count++;
            if (count >= 6) {
                sender.sendMessage(Message.raw(row.toString()).color(color));
                row = new StringBuilder();
                count = 0;
            }
        }
        if (count > 0) {
            sender.sendMessage(Message.raw(row.toString()).color(color));
        }
    }

    private void handleProtectionReload(CommandSender sender) {
        var manager = plugin.getPickupBlacklistManager();
        manager.reload();
        sender.sendMessage(Message.raw("Reloaded interaction protection lists.").color(Color.GREEN));
        sender.sendMessage(Message.raw("Blocked: " + manager.getBlockedCount() + " | Allowed: " + manager.getAllowedCount()).color(Color.GRAY));
    }

    private void handleProtectionReset(CommandSender sender) {
        var manager = plugin.getPickupBlacklistManager();
        manager.resetToDefaults();
        sender.sendMessage(Message.raw("Reset interaction protection lists to defaults.").color(Color.GREEN));
        sender.sendMessage(Message.raw("Blocked: " + manager.getBlockedCount() + " | Allowed: " + manager.getAllowedCount()).color(Color.GRAY));
    }
}
