package com.hcfactions.commands;

import com.hcfactions.HC_FactionsPlugin;
import com.hcfactions.gui.FactionMenuGui;
import com.hcfactions.gui.GuildBrowserGui;
import com.hcfactions.gui.GuildClaimGui;
import com.hcfactions.gui.GuildInfoGui;
import com.hcfactions.gui.GuildManagementGui;
import com.hcfactions.managers.ClaimManager;
import com.hcfactions.models.Claim;
import com.hcfactions.models.Faction;
import com.hcfactions.models.Guild;
import com.hcfactions.models.GuildChunkAccess;
import com.hcfactions.models.GuildRole;
import com.hcfactions.models.PlayerData;

import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.math.vector.Vector3f;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import java.awt.Color;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main guild command with subcommands.
 */
public class GuildCommand extends AbstractAsyncCommand {

    private final HC_FactionsPlugin plugin;
    
    // Cooldown tracking for /guild home command (player UUID -> last use timestamp)
    private static final Map<UUID, Long> homeCooldowns = new ConcurrentHashMap<>();

    public GuildCommand(HC_FactionsPlugin plugin) {
        super("guild", "Guild management commands");
        this.addAliases("g");
        this.setAllowsExtraArguments(true);
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
        
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Message.raw("This command can only be used by players.").color(Color.RED));
            return CompletableFuture.completedFuture(null);
        }

        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) {
            sender.sendMessage(Message.raw("Error: Invalid player reference.").color(Color.RED));
            return CompletableFuture.completedFuture(null);
        }

        Store<EntityStore> store = ref.getStore();
        World world = store.getExternalData().getWorld();

        // Parse args from input string before entering world thread
        String inputString = ctx.getInputString();
        String[] parts = inputString.split("\\s+");
        String[] args = parts.length > 1 ? Arrays.copyOfRange(parts, 1, parts.length) : new String[]{};

        // Run on the world thread to safely access store components
        return CompletableFuture.runAsync(() -> {
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null) {
                sender.sendMessage(Message.raw("Error: Could not get player reference.").color(Color.RED));
                return;
            }

            // No args - open faction menu GUI
            if (args.length == 0) {
                handleMenuGui(player, ref, store, playerRef);
                return;
            }

            String subCommand = args[0].toLowerCase();

            switch (subCommand) {
                case "menu" -> handleMenuGui(player, ref, store, playerRef);
                case "manage" -> handleManageGui(player, ref, store, playerRef);
                case "browse" -> handleBrowseGui(player, ref, store, playerRef);
                case "create" -> handleCreate(playerRef, args);
                case "disband" -> handleDisband(playerRef);
                case "invite" -> handleInvite(playerRef, args);
                case "join" -> handleJoin(playerRef, args);
                case "leave" -> handleLeave(playerRef);
                case "kick" -> handleKick(playerRef, args);
                case "promote" -> handlePromote(playerRef, args);
                case "demote" -> handleDemote(playerRef, args);
                case "claimgui" -> handleClaimGui(player, ref, store, playerRef);
                case "claim", "claimchunk", "unclaim", "claims", "map" -> {
                    playerRef.sendMessage(Message.raw("Use /claim for land management.").color(Color.YELLOW));
                }
                // TODO: Re-enable when home system is ready
                // case "sethome" -> handleSetHome(playerRef);
                // case "home" -> handleHome(playerRef);
                case "info" -> handleInfo(playerRef, args);
                case "list" -> handleList(playerRef);
                case "members" -> handleMembers(playerRef);
                case "territory" -> handleTerritory(playerRef, args);
                case "tag" -> handleTag(playerRef, args);
                case "help" -> showHelp(playerRef);
                default -> {
                    playerRef.sendMessage(Message.raw("Unknown subcommand: " + subCommand).color(Color.RED));
                    showHelp(playerRef);
                }
            }
        }, world);
    }

    private void showHelp(PlayerRef playerRef) {
        playerRef.sendMessage(Message.raw("=== Guild Commands ===").color(Color.ORANGE));
        playerRef.sendMessage(Message.raw("/guild create <name> - Create a guild").color(Color.YELLOW));
        playerRef.sendMessage(Message.raw("/guild info [name] - View guild info").color(Color.YELLOW));
        playerRef.sendMessage(Message.raw("/guild list - List faction guilds").color(Color.YELLOW));
        playerRef.sendMessage(Message.raw("/guild members - List guild members").color(Color.YELLOW));
        playerRef.sendMessage(Message.raw("/guild invite <player> - Invite a player").color(Color.YELLOW));
        playerRef.sendMessage(Message.raw("/guild join <guild> - Join a guild").color(Color.YELLOW));
        playerRef.sendMessage(Message.raw("/guild leave - Leave your guild").color(Color.YELLOW));
        playerRef.sendMessage(Message.raw("/guild promote/demote <player> - Manage roles").color(Color.YELLOW));
        playerRef.sendMessage(Message.raw("/guild kick <player> - Kick a member").color(Color.YELLOW));
        playerRef.sendMessage(Message.raw("/guild tag <1-4 letters> - Set guild tag for nameplates").color(Color.YELLOW));
        playerRef.sendMessage(Message.raw("/guild territory ... - Assign member access to guild chunks").color(Color.YELLOW));
        playerRef.sendMessage(Message.raw("/guild disband - Disband your guild").color(Color.YELLOW));
        playerRef.sendMessage(Message.raw("").color(Color.GRAY));
        playerRef.sendMessage(Message.raw("For land claims, use /claim").color(Color.GRAY));
    }

    private void handleCreate(PlayerRef playerRef, String[] args) {
        if (args.length < 2) {
            playerRef.sendMessage(Message.raw("Usage: /guild create <name>").color(Color.RED));
            return;
        }

        PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
        if (playerData == null || !playerData.hasChosenFaction()) {
            playerRef.sendMessage(Message.raw("You must choose a faction before creating a guild!").color(Color.RED));
            return;
        }

        if (playerData.isInGuild()) {
            playerRef.sendMessage(Message.raw("You are already in a guild! Leave first with /guild leave").color(Color.RED));
            return;
        }

        String guildName = args[1];
        Guild guild = plugin.getGuildManager().createGuild(guildName, playerRef.getUuid(), playerData.getFactionId());

        if (guild != null) {
            Faction faction = plugin.getFactionManager().getFaction(playerData.getFactionId());
            String factionName = faction != null ? faction.getDisplayName() : playerData.getFactionId();
            
            playerRef.sendMessage(Message.raw("Guild '" + guildName + "' created successfully!").color(Color.GREEN));
            playerRef.sendMessage(Message.raw("Your guild is part of " + factionName).color(Color.GRAY));
            playerRef.sendMessage(Message.raw("Use /guild invite <player> to invite members").color(Color.YELLOW));
        } else {
            playerRef.sendMessage(Message.raw("Failed to create guild. Name may be taken or invalid.").color(Color.RED));
        }
    }

    private void handleDisband(PlayerRef playerRef) {
        PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
        if (playerData == null || !playerData.isInGuild()) {
            playerRef.sendMessage(Message.raw("You are not in a guild!").color(Color.RED));
            return;
        }

        Guild guild = plugin.getGuildManager().getGuild(playerData.getGuildId());
        if (guild == null) {
            playerRef.sendMessage(Message.raw("Guild not found!").color(Color.RED));
            return;
        }

        if (!guild.getLeaderId().equals(playerRef.getUuid())) {
            playerRef.sendMessage(Message.raw("Only the guild leader can disband the guild!").color(Color.RED));
            return;
        }

        String guildName = guild.getName();
        if (plugin.getGuildManager().disbandGuild(guild.getId())) {
            playerRef.sendMessage(Message.raw("Guild '" + guildName + "' has been disbanded.").color(Color.YELLOW));
        } else {
            playerRef.sendMessage(Message.raw("Failed to disband guild.").color(Color.RED));
        }
    }

    private void handleInvite(PlayerRef playerRef, String[] args) {
        if (args.length < 2) {
            playerRef.sendMessage(Message.raw("Usage: /guild invite <player>").color(Color.RED));
            return;
        }

        PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
        if (playerData == null || !playerData.isInGuild()) {
            playerRef.sendMessage(Message.raw("You are not in a guild!").color(Color.RED));
            return;
        }

        if (!playerData.getGuildRole().hasAtLeast(GuildRole.OFFICER)) {
            playerRef.sendMessage(Message.raw("You need to be an Officer or higher to invite players!").color(Color.RED));
            return;
        }

        String targetName = args[1];
        PlayerRef targetPlayer = findOnlinePlayer(targetName);
        if (targetPlayer == null) {
            playerRef.sendMessage(Message.raw("Player '" + targetName + "' not found or not online.").color(Color.RED));
            return;
        }

        PlayerData targetData = plugin.getPlayerDataRepository().getPlayerData(targetPlayer.getUuid());
        if (targetData == null || !targetData.hasChosenFaction()) {
            playerRef.sendMessage(Message.raw("That player hasn't chosen a faction yet!").color(Color.RED));
            return;
        }

        if (!targetData.getFactionId().equals(playerData.getFactionId())) {
            playerRef.sendMessage(Message.raw("You can only invite players from your faction!").color(Color.RED));
            return;
        }

        if (targetData.isInGuild()) {
            playerRef.sendMessage(Message.raw("That player is already in a guild!").color(Color.RED));
            return;
        }

        Guild guild = plugin.getGuildManager().getGuild(playerData.getGuildId());
        if (plugin.getGuildManager().invitePlayer(playerData.getGuildId(), targetPlayer.getUuid())) {
            playerRef.sendMessage(Message.raw("Invited " + targetName + " to your guild!").color(Color.GREEN));
            targetPlayer.sendMessage(Message.raw("You have been invited to join " + guild.getName() + "!").color(Color.GREEN));
            targetPlayer.sendMessage(Message.raw("Use /guild join " + guild.getName() + " to accept").color(Color.YELLOW));
        } else {
            playerRef.sendMessage(Message.raw("Failed to send invitation.").color(Color.RED));
        }
    }

    private void handleJoin(PlayerRef playerRef, String[] args) {
        if (args.length < 2) {
            playerRef.sendMessage(Message.raw("Usage: /guild join <guild>").color(Color.RED));
            return;
        }

        PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
        if (playerData != null && playerData.isInGuild()) {
            playerRef.sendMessage(Message.raw("You are already in a guild! Leave first with /guild leave").color(Color.RED));
            return;
        }

        String guildName = args[1];
        Guild guild = plugin.getGuildManager().getGuildByName(guildName);
        if (guild == null) {
            playerRef.sendMessage(Message.raw("Guild '" + guildName + "' not found.").color(Color.RED));
            return;
        }

        if (plugin.getGuildManager().joinGuild(guild.getId(), playerRef.getUuid())) {
            playerRef.sendMessage(Message.raw("You have joined " + guild.getName() + "!").color(Color.GREEN));
        } else {
            playerRef.sendMessage(Message.raw("Failed to join guild. Do you have an invitation?").color(Color.RED));
        }
    }

    private void handleLeave(PlayerRef playerRef) {
        PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
        if (playerData == null || !playerData.isInGuild()) {
            playerRef.sendMessage(Message.raw("You are not in a guild!").color(Color.RED));
            return;
        }

        Guild guild = plugin.getGuildManager().getGuild(playerData.getGuildId());
        if (guild != null && guild.getLeaderId().equals(playerRef.getUuid())) {
            playerRef.sendMessage(Message.raw("As leader, you must transfer leadership or disband the guild!").color(Color.RED));
            playerRef.sendMessage(Message.raw("Use /guild promote <player> to transfer leadership").color(Color.YELLOW));
            playerRef.sendMessage(Message.raw("Use /guild disband to disband the guild").color(Color.YELLOW));
            return;
        }

        if (plugin.getGuildManager().leaveGuild(playerRef.getUuid())) {
            playerRef.sendMessage(Message.raw("You have left the guild.").color(Color.YELLOW));
        } else {
            playerRef.sendMessage(Message.raw("Failed to leave guild.").color(Color.RED));
        }
    }

    private void handleKick(PlayerRef playerRef, String[] args) {
        if (args.length < 2) {
            playerRef.sendMessage(Message.raw("Usage: /guild kick <player>").color(Color.RED));
            return;
        }

        PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
        if (playerData == null || !playerData.isInGuild()) {
            playerRef.sendMessage(Message.raw("You are not in a guild!").color(Color.RED));
            return;
        }

        String targetName = args[1];
        UUID targetUuid = findPlayerUuid(targetName);
        if (targetUuid == null) {
            playerRef.sendMessage(Message.raw("Player '" + targetName + "' not found.").color(Color.RED));
            return;
        }

        if (plugin.getGuildManager().kickPlayer(playerData.getGuildId(), targetUuid, playerRef.getUuid())) {
            playerRef.sendMessage(Message.raw(targetName + " has been kicked from the guild.").color(Color.YELLOW));
        } else {
            playerRef.sendMessage(Message.raw("Failed to kick player. Check your permissions.").color(Color.RED));
        }
    }

    private void handlePromote(PlayerRef playerRef, String[] args) {
        if (args.length < 2) {
            playerRef.sendMessage(Message.raw("Usage: /guild promote <player>").color(Color.RED));
            return;
        }

        PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
        if (playerData == null || !playerData.isInGuild()) {
            playerRef.sendMessage(Message.raw("You are not in a guild!").color(Color.RED));
            return;
        }

        String targetName = args[1];
        UUID targetUuid = findPlayerUuid(targetName);
        if (targetUuid == null) {
            playerRef.sendMessage(Message.raw("Player '" + targetName + "' not found.").color(Color.RED));
            return;
        }

        if (plugin.getGuildManager().promotePlayer(playerData.getGuildId(), targetUuid, playerRef.getUuid())) {
            playerRef.sendMessage(Message.raw(targetName + " has been promoted!").color(Color.GREEN));
        } else {
            playerRef.sendMessage(Message.raw("Failed to promote player. Check your permissions.").color(Color.RED));
        }
    }

    private void handleDemote(PlayerRef playerRef, String[] args) {
        if (args.length < 2) {
            playerRef.sendMessage(Message.raw("Usage: /guild demote <player>").color(Color.RED));
            return;
        }

        PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
        if (playerData == null || !playerData.isInGuild()) {
            playerRef.sendMessage(Message.raw("You are not in a guild!").color(Color.RED));
            return;
        }

        String targetName = args[1];
        UUID targetUuid = findPlayerUuid(targetName);
        if (targetUuid == null) {
            playerRef.sendMessage(Message.raw("Player '" + targetName + "' not found.").color(Color.RED));
            return;
        }

        if (plugin.getGuildManager().demotePlayer(playerData.getGuildId(), targetUuid, playerRef.getUuid())) {
            playerRef.sendMessage(Message.raw(targetName + " has been demoted.").color(Color.YELLOW));
        } else {
            playerRef.sendMessage(Message.raw("Failed to demote player. Check your permissions.").color(Color.RED));
        }
    }

    private void handleClaim(PlayerRef playerRef) {
        PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
        if (playerData == null || !playerData.hasChosenFaction()) {
            playerRef.sendMessage(Message.raw("You must choose a faction first!").color(Color.RED));
            return;
        }

        Vector3d pos = playerRef.getTransform().getPosition();
        String worldName = getWorldName(playerRef);
        if (worldName == null) {
            playerRef.sendMessage(Message.raw("Could not determine your location.").color(Color.RED));
            return;
        }

        int chunkX = ClaimManager.toChunkCoord(pos.getX());
        int chunkZ = ClaimManager.toChunkCoord(pos.getZ());

        // If player is in a guild, use guild claiming
        if (playerData.isInGuild()) {
            if (!playerData.getGuildRole().hasAtLeast(GuildRole.OFFICER)) {
                playerRef.sendMessage(Message.raw("You need to be an Officer or higher to claim land!").color(Color.RED));
                return;
            }

            ClaimManager.ClaimResult result = plugin.getClaimManager().claimChunkWithResult(
                playerData.getGuildId(), worldName, chunkX, chunkZ);
            if (result.isSuccess()) {
                playerRef.sendMessage(Message.raw("Chunk claimed for guild! (" + chunkX + ", " + chunkZ + ")").color(Color.GREEN));
            } else {
                playerRef.sendMessage(Message.raw(result.getMessage()).color(Color.RED));
            }
        } else {
            // Solo player claiming
            ClaimManager.ClaimResult result = plugin.getClaimManager().claimChunkForPlayer(
                playerRef.getUuid(), worldName, chunkX, chunkZ);
            if (result.isSuccess()) {
                int current = plugin.getClaimManager().getPlayerClaimCount(playerRef.getUuid());
                int max = plugin.getClaimManager().getSoloMaxClaims();
                playerRef.sendMessage(Message.raw("Personal claim created! (" + chunkX + ", " + chunkZ + ")").color(Color.GREEN));
                playerRef.sendMessage(Message.raw("Claims: " + current + "/" + max).color(Color.GRAY));
            } else {
                playerRef.sendMessage(Message.raw(result.getMessage()).color(Color.RED));
            }
        }
    }

    private void handleUnclaim(PlayerRef playerRef) {
        PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
        if (playerData == null || !playerData.hasChosenFaction()) {
            playerRef.sendMessage(Message.raw("You must choose a faction first!").color(Color.RED));
            return;
        }

        Vector3d pos = playerRef.getTransform().getPosition();
        String worldName = getWorldName(playerRef);
        if (worldName == null) {
            playerRef.sendMessage(Message.raw("Could not determine your location.").color(Color.RED));
            return;
        }

        int chunkX = ClaimManager.toChunkCoord(pos.getX());
        int chunkZ = ClaimManager.toChunkCoord(pos.getZ());

        // Check if this is a solo player claim first
        Claim claim = plugin.getClaimManager().getClaim(worldName, chunkX, chunkZ);
        if (claim != null && claim.isSoloPlayerClaim()) {
            // Solo player trying to unclaim their own land
            if (plugin.getClaimManager().unclaimChunkForPlayer(playerRef.getUuid(), worldName, chunkX, chunkZ)) {
                playerRef.sendMessage(Message.raw("Personal claim removed! (" + chunkX + ", " + chunkZ + ")").color(Color.YELLOW));
            } else {
                playerRef.sendMessage(Message.raw("Failed to unclaim. Is this your personal claim?").color(Color.RED));
            }
            return;
        }

        // Guild claiming logic
        if (!playerData.isInGuild()) {
            playerRef.sendMessage(Message.raw("This is not your claim!").color(Color.RED));
            return;
        }

        if (!playerData.getGuildRole().hasAtLeast(GuildRole.OFFICER)) {
            playerRef.sendMessage(Message.raw("You need to be an Officer or higher to unclaim land!").color(Color.RED));
            return;
        }

        if (plugin.getClaimManager().unclaimChunk(playerData.getGuildId(), worldName, chunkX, chunkZ)) {
            playerRef.sendMessage(Message.raw("Guild chunk unclaimed! (" + chunkX + ", " + chunkZ + ")").color(Color.YELLOW));
        } else {
            playerRef.sendMessage(Message.raw("Failed to unclaim chunk. Is this your guild's land?").color(Color.RED));
        }
    }

    private void handleListClaims(PlayerRef playerRef) {
        PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
        if (playerData == null || !playerData.hasChosenFaction()) {
            playerRef.sendMessage(Message.raw("You must choose a faction first!").color(Color.RED));
            return;
        }

        // Show personal claims if not in a guild
        if (!playerData.isInGuild()) {
            List<Claim> claims = plugin.getClaimManager().getPlayerClaims(playerRef.getUuid());
            int max = plugin.getClaimManager().getSoloMaxClaims();

            playerRef.sendMessage(Message.raw("=== Your Personal Claims (" + claims.size() + "/" + max + ") ===").color(Color.ORANGE));

            if (claims.isEmpty()) {
                playerRef.sendMessage(Message.raw("You have no personal claims.").color(Color.GRAY));
                playerRef.sendMessage(Message.raw("Use /guild claimchunk to claim land.").color(Color.YELLOW));
            } else {
                for (Claim claim : claims) {
                    playerRef.sendMessage(Message.raw("- " + claim.getWorld() + " (" + claim.getChunkX() + ", " + claim.getChunkZ() + ")").color(Color.WHITE));
                }
            }

            playerRef.sendMessage(Message.raw("Note: Personal claims are released when you join a guild.").color(Color.GRAY));
        } else {
            // Show guild claims
            Guild guild = plugin.getGuildManager().getGuild(playerData.getGuildId());
            List<Claim> claims = plugin.getClaimManager().getGuildClaims(playerData.getGuildId());
            int max = plugin.getClaimManager().getMaxClaims(playerData.getGuildId());

            String guildName = guild != null ? guild.getName() : "Your Guild";
            playerRef.sendMessage(Message.raw("=== " + guildName + " Claims (" + claims.size() + "/" + max + ") ===").color(Color.ORANGE));

            if (claims.isEmpty()) {
                playerRef.sendMessage(Message.raw("Your guild has no claims.").color(Color.GRAY));
            } else {
                for (Claim claim : claims) {
                    playerRef.sendMessage(Message.raw("- " + claim.getWorld() + " (" + claim.getChunkX() + ", " + claim.getChunkZ() + ")").color(Color.WHITE));
                }
            }
        }
    }

    private void handleMenuGui(Player player, Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef playerRef) {
        player.getPageManager().openCustomPage(ref, store, new FactionMenuGui(plugin, playerRef));
    }

    private void handleManageGui(Player player, Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef playerRef) {
        PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
        if (playerData == null || !playerData.isInGuild()) {
            playerRef.sendMessage(Message.raw("You are not in a guild!").color(Color.RED));
            return;
        }
        if (!playerData.getGuildRole().hasAtLeast(GuildRole.OFFICER)) {
            playerRef.sendMessage(Message.raw("You need to be an Officer or higher to manage the guild!").color(Color.RED));
            return;
        }
        FactionMenuGui parentPage = new FactionMenuGui(plugin, playerRef);
        player.getPageManager().openCustomPage(ref, store, new GuildManagementGui(plugin, playerRef, parentPage));
    }

    private void handleBrowseGui(Player player, Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef playerRef) {
        FactionMenuGui parentPage = new FactionMenuGui(plugin, playerRef);
        player.getPageManager().openCustomPage(ref, store, new GuildBrowserGui(plugin, playerRef, parentPage));
    }

    private void handleClaimGui(Player player, Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef playerRef) {
        PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
        if (playerData == null || !playerData.isInGuild()) {
            playerRef.sendMessage(Message.raw("You are not in a guild!").color(Color.RED));
            return;
        }

        if (!playerData.getGuildRole().hasAtLeast(GuildRole.OFFICER)) {
            playerRef.sendMessage(Message.raw("You need to be an Officer or higher to manage claims!").color(Color.RED));
            return;
        }

        // Get player's current position and convert to chunk coordinates
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) {
            playerRef.sendMessage(Message.raw("Could not get player position.").color(Color.RED));
            return;
        }

        String worldName = getWorldName(playerRef);
        if (worldName == null) {
            playerRef.sendMessage(Message.raw("Could not determine your location.").color(Color.RED));
            return;
        }

        int chunkX = ClaimManager.toChunkCoord(transform.getPosition().getX());
        int chunkZ = ClaimManager.toChunkCoord(transform.getPosition().getZ());

        // Open the guild claim GUI
        GuildClaimGui gui = new GuildClaimGui(
            plugin,
            playerRef,
            playerData.getGuildId(),
            playerData.getFactionId(),
            worldName,
            chunkX,
            chunkZ
        );
        player.getPageManager().openCustomPage(ref, store, gui);

        Guild guild = plugin.getGuildManager().getGuild(playerData.getGuildId());
        playerRef.sendMessage(Message.raw("Opened claim manager for " + (guild != null ? guild.getName() : "your guild")).color(Color.GREEN));
    }

    private void handleSetHome(PlayerRef playerRef) {
        PlayerData playerData = plugin.getPlayerDataRepository().getOrCreatePlayerData(playerRef.getUuid());
        
        Vector3d pos = playerRef.getTransform().getPosition();
        String worldName = getWorldName(playerRef);
        if (worldName == null) {
            playerRef.sendMessage(Message.raw("Could not determine your location.").color(Color.RED));
            return;
        }

        playerData.setHome(worldName, pos.getX(), pos.getY(), pos.getZ());
        plugin.getPlayerDataRepository().savePlayerData(playerData);
        
        playerRef.sendMessage(Message.raw("Home set! Use /guild home to teleport here.").color(Color.GREEN));
    }

    private void handleHome(PlayerRef playerRef) {
        UUID playerUuid = playerRef.getUuid();
        
        // Check if player has a home set
        PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerUuid);
        
        if (playerData == null || !playerData.hasHome()) {
            playerRef.sendMessage(Message.raw("You don't have a home set!").color(Color.RED));
            playerRef.sendMessage(Message.raw("Use /guild sethome to set your home location.").color(Color.YELLOW));
            return;
        }

        // Check cooldown
        long now = System.currentTimeMillis();
        int cooldownSeconds = plugin.getConfig().getGuildHomeCooldownSeconds();
        long cooldownMs = cooldownSeconds * 1000L;
        
        Long lastUse = homeCooldowns.get(playerUuid);
        if (lastUse != null) {
            long elapsed = now - lastUse;
            if (elapsed < cooldownMs) {
                long remainingMs = cooldownMs - elapsed;
                String remainingFormatted = formatCooldownRemaining(remainingMs);
                playerRef.sendMessage(Message.raw("You must wait " + remainingFormatted + " before teleporting home again.").color(Color.RED));
                return;
            }
        }

        // Get the target world
        World targetWorld = null;
        for (var world : Universe.get().getWorlds().values()) {
            if (world.getName().equals(playerData.getHomeWorld())) {
                targetWorld = world;
                break;
            }
        }

        if (targetWorld == null) {
            playerRef.sendMessage(Message.raw("Could not find the world your home is in!").color(Color.RED));
            return;
        }

        // Get player's entity reference for teleportation
        Ref<EntityStore> playerEntityRef = playerRef.getReference();
        if (playerEntityRef == null || !playerEntityRef.isValid()) {
            playerRef.sendMessage(Message.raw("Could not teleport - please try again.").color(Color.RED));
            return;
        }

        Store<EntityStore> store = playerEntityRef.getStore();
        
        // Teleport to home location
        Vector3d homePosition = new Vector3d(playerData.getHomeX(), playerData.getHomeY(), playerData.getHomeZ());
        Vector3f homeRotation = new Vector3f(0, 0, 0); // Default rotation (facing north)

        // Add teleport component to move player
        store.addComponent(playerEntityRef, Teleport.getComponentType(),
            new Teleport(targetWorld, homePosition, homeRotation));

        // Record cooldown
        homeCooldowns.put(playerUuid, now);

        playerRef.sendMessage(Message.raw("Teleporting home...").color(Color.GREEN));
    }

    /**
     * Formats remaining cooldown time into a human-readable string.
     * Examples: "29 minutes 45 seconds", "5 minutes", "30 seconds"
     */
    private String formatCooldownRemaining(long milliseconds) {
        long totalSeconds = milliseconds / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;

        if (minutes > 0 && seconds > 0) {
            return minutes + " minute" + (minutes != 1 ? "s" : "") + " " + 
                   seconds + " second" + (seconds != 1 ? "s" : "");
        } else if (minutes > 0) {
            return minutes + " minute" + (minutes != 1 ? "s" : "");
        } else {
            return seconds + " second" + (seconds != 1 ? "s" : "");
        }
    }

    private void handleInfo(PlayerRef playerRef, String[] args) {
        Guild guild;
        
        if (args.length >= 2) {
            guild = plugin.getGuildManager().getGuildByName(args[1]);
        } else {
            PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
            if (playerData == null || !playerData.isInGuild()) {
                playerRef.sendMessage(Message.raw("Usage: /guild info <guild> or be in a guild").color(Color.RED));
                return;
            }
            guild = plugin.getGuildManager().getGuild(playerData.getGuildId());
        }

        if (guild == null) {
            playerRef.sendMessage(Message.raw("Guild not found.").color(Color.RED));
            return;
        }

        Faction faction = plugin.getFactionManager().getFaction(guild.getFactionId());
        int memberCount = plugin.getGuildManager().getMemberCount(guild.getId());
        int claimCount = plugin.getClaimManager().getClaimCount(guild.getId());

        playerRef.sendMessage(Message.raw("=== " + guild.getName() + " ===").color(Color.ORANGE));
        playerRef.sendMessage(Message.raw("Faction: " + (faction != null ? faction.getDisplayName() : guild.getFactionId())).color(Color.WHITE));
        playerRef.sendMessage(Message.raw("Members: " + memberCount).color(Color.WHITE));
        playerRef.sendMessage(Message.raw("Power: " + guild.getPower() + "/" + guild.getMaxPower()).color(Color.WHITE));
        playerRef.sendMessage(Message.raw("Claims: " + claimCount).color(Color.WHITE));
        playerRef.sendMessage(Message.raw("Bank: " + String.format("%.2f", guild.getBankBalance())).color(Color.YELLOW));
    }

    private void handleList(PlayerRef playerRef) {
        PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
        if (playerData == null || !playerData.hasChosenFaction()) {
            playerRef.sendMessage(Message.raw("You must choose a faction first!").color(Color.RED));
            return;
        }

        Faction faction = plugin.getFactionManager().getFaction(playerData.getFactionId());
        List<Guild> guilds = plugin.getGuildManager().getGuildsByFaction(playerData.getFactionId());

        playerRef.sendMessage(Message.raw("=== " + (faction != null ? faction.getDisplayName() : playerData.getFactionId()) + " Guilds ===").color(Color.ORANGE));
        
        if (guilds.isEmpty()) {
            playerRef.sendMessage(Message.raw("No guilds in your faction yet.").color(Color.GRAY));
            playerRef.sendMessage(Message.raw("Be the first! Use /guild create <name>").color(Color.YELLOW));
        } else {
            for (Guild guild : guilds) {
                int memberCount = plugin.getGuildManager().getMemberCount(guild.getId());
                playerRef.sendMessage(Message.raw("- " + guild.getName() + " (" + memberCount + " members)").color(Color.WHITE));
            }
        }
    }

    private void handleMembers(PlayerRef playerRef) {
        PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
        if (playerData == null || !playerData.isInGuild()) {
            playerRef.sendMessage(Message.raw("You are not in a guild!").color(Color.RED));
            return;
        }

        Guild guild = plugin.getGuildManager().getGuild(playerData.getGuildId());
        List<PlayerData> members = plugin.getGuildManager().getGuildMembers(playerData.getGuildId());

        playerRef.sendMessage(Message.raw("=== " + guild.getName() + " Members ===").color(Color.ORANGE));
        
        for (PlayerData member : members) {
            String name = member.getPlayerName() != null ? member.getPlayerName() : member.getPlayerUuid().toString().substring(0, 8);
            String role = member.getGuildRole() != null ? member.getGuildRole().getDisplayName() : "Unknown";
            
            Color roleColor = switch (member.getGuildRole()) {
                case LEADER -> Color.YELLOW;
                case OFFICER -> Color.CYAN;
                case SENIOR -> Color.ORANGE;
                case MEMBER -> Color.WHITE;
                case RECRUIT -> Color.GRAY;
                default -> Color.WHITE;
            };

            playerRef.sendMessage(Message.raw("[" + role + "] " + name).color(roleColor));
        }
    }

    private void handleTerritory(PlayerRef playerRef, String[] args) {
        PlayerData actorData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
        if (actorData == null || !actorData.isInGuild()) {
            playerRef.sendMessage(Message.raw("You are not in a guild!").color(Color.RED));
            return;
        }

        if (actorData.getGuildRole() == null || !actorData.getGuildRole().hasAtLeast(GuildRole.OFFICER)) {
            playerRef.sendMessage(Message.raw("You need to be an Officer or higher to manage territory assignments.").color(Color.RED));
            return;
        }

        if (args.length < 2) {
            showTerritoryHelp(playerRef);
            return;
        }

        String sub = args[1].toLowerCase();
        switch (sub) {
            case "help" -> showTerritoryHelp(playerRef);
            case "assign" -> handleTerritoryAssign(playerRef, actorData, args);
            case "unassign" -> handleTerritoryUnassign(playerRef, actorData, args);
            case "clear" -> handleTerritoryClear(playerRef, actorData, args);
            case "list" -> handleTerritoryList(playerRef, actorData, args);
            default -> showTerritoryHelp(playerRef);
        }
    }

    private void handleTerritoryAssign(PlayerRef playerRef, PlayerData actorData, String[] args) {
        if (args.length < 3) {
            playerRef.sendMessage(Message.raw("Usage: /guild territory assign <player> [chunkX chunkZ] [edit|chest|both]").color(Color.RED));
            return;
        }

        UUID targetUuid = resolvePlayerUuidByName(args[2]);
        if (targetUuid == null) {
            playerRef.sendMessage(Message.raw("Player '" + args[2] + "' not found.").color(Color.RED));
            return;
        }

        PlayerData targetData = plugin.getPlayerDataRepository().getPlayerData(targetUuid);
        if (targetData == null || targetData.getGuildId() == null || !targetData.getGuildId().equals(actorData.getGuildId())) {
            playerRef.sendMessage(Message.raw("That player is not in your guild.").color(Color.RED));
            return;
        }

        GuildRole targetRole = targetData.getGuildRole();
        if (targetRole != null && targetRole.hasAtLeast(GuildRole.OFFICER)) {
            playerRef.sendMessage(Message.raw("Officers and leaders already have full guild-land access.").color(Color.YELLOW));
            return;
        }

        String worldName = getWorldName(playerRef);
        if (worldName == null) {
            playerRef.sendMessage(Message.raw("Could not determine your current world.").color(Color.RED));
            return;
        }

        int chunkX = ClaimManager.toChunkCoord(playerRef.getTransform().getPosition().getX());
        int chunkZ = ClaimManager.toChunkCoord(playerRef.getTransform().getPosition().getZ());
        int modeIndex = 3;

        if (args.length >= 5 && isInteger(args[3]) && isInteger(args[4])) {
            chunkX = Integer.parseInt(args[3]);
            chunkZ = Integer.parseInt(args[4]);
            modeIndex = 5;
        } else if (args.length >= 4 && isInteger(args[3])) {
            playerRef.sendMessage(Message.raw("Provide both chunk coordinates: <chunkX chunkZ>.").color(Color.RED));
            return;
        }

        String mode = args.length > modeIndex ? args[modeIndex].toLowerCase() : "both";
        boolean canEdit;
        boolean canChest;
        switch (mode) {
            case "edit" -> {
                canEdit = true;
                canChest = false;
            }
            case "chest" -> {
                canEdit = false;
                canChest = true;
            }
            case "both", "all" -> {
                canEdit = true;
                canChest = true;
            }
            default -> {
                playerRef.sendMessage(Message.raw("Invalid mode. Use: edit, chest, or both").color(Color.RED));
                return;
            }
        }

        Claim claim = plugin.getClaimManager().getClaim(worldName, chunkX, chunkZ);
        if (claim == null || claim.isFactionClaim() || claim.isSoloPlayerClaim()
            || claim.getGuildId() == null || !claim.getGuildId().equals(actorData.getGuildId())) {
            playerRef.sendMessage(Message.raw("That chunk is not claimed by your guild.").color(Color.RED));
            return;
        }

        plugin.getGuildChunkAccessManager().assign(
            actorData.getGuildId(),
            targetUuid,
            worldName,
            chunkX,
            chunkZ,
            canEdit,
            canChest,
            playerRef.getUuid()
        );

        String targetName = targetData.getPlayerName() != null ? targetData.getPlayerName() : args[2];
        playerRef.sendMessage(Message.raw(
            "Assigned " + targetName + " at [" + chunkX + ", " + chunkZ + "] in " + worldName
                + " (edit=" + canEdit + ", chest=" + canChest + ")"
        ).color(Color.GREEN));
    }

    private void handleTerritoryUnassign(PlayerRef playerRef, PlayerData actorData, String[] args) {
        if (args.length < 3) {
            playerRef.sendMessage(Message.raw("Usage: /guild territory unassign <player> [chunkX chunkZ]").color(Color.RED));
            return;
        }

        UUID targetUuid = resolvePlayerUuidByName(args[2]);
        if (targetUuid == null) {
            playerRef.sendMessage(Message.raw("Player '" + args[2] + "' not found.").color(Color.RED));
            return;
        }

        PlayerData targetData = plugin.getPlayerDataRepository().getPlayerData(targetUuid);
        if (targetData == null || targetData.getGuildId() == null || !targetData.getGuildId().equals(actorData.getGuildId())) {
            playerRef.sendMessage(Message.raw("That player is not in your guild.").color(Color.RED));
            return;
        }

        String worldName = getWorldName(playerRef);
        if (worldName == null) {
            playerRef.sendMessage(Message.raw("Could not determine your current world.").color(Color.RED));
            return;
        }

        int chunkX = ClaimManager.toChunkCoord(playerRef.getTransform().getPosition().getX());
        int chunkZ = ClaimManager.toChunkCoord(playerRef.getTransform().getPosition().getZ());

        if (args.length >= 5 && isInteger(args[3]) && isInteger(args[4])) {
            chunkX = Integer.parseInt(args[3]);
            chunkZ = Integer.parseInt(args[4]);
        } else if (args.length >= 4 && isInteger(args[3])) {
            playerRef.sendMessage(Message.raw("Provide both chunk coordinates: <chunkX chunkZ>.").color(Color.RED));
            return;
        }

        plugin.getGuildChunkAccessManager().unassign(actorData.getGuildId(), targetUuid, worldName, chunkX, chunkZ);

        String targetName = targetData.getPlayerName() != null ? targetData.getPlayerName() : args[2];
        playerRef.sendMessage(Message.raw(
            "Removed " + targetName + "'s assignment for [" + chunkX + ", " + chunkZ + "] in " + worldName + "."
        ).color(Color.YELLOW));
    }

    private void handleTerritoryClear(PlayerRef playerRef, PlayerData actorData, String[] args) {
        if (args.length < 3) {
            playerRef.sendMessage(Message.raw("Usage: /guild territory clear <player>").color(Color.RED));
            return;
        }

        UUID targetUuid = resolvePlayerUuidByName(args[2]);
        if (targetUuid == null) {
            playerRef.sendMessage(Message.raw("Player '" + args[2] + "' not found.").color(Color.RED));
            return;
        }

        PlayerData targetData = plugin.getPlayerDataRepository().getPlayerData(targetUuid);
        if (targetData == null || targetData.getGuildId() == null || !targetData.getGuildId().equals(actorData.getGuildId())) {
            playerRef.sendMessage(Message.raw("That player is not in your guild.").color(Color.RED));
            return;
        }

        plugin.getGuildChunkAccessManager().removeAssignmentsForMember(actorData.getGuildId(), targetUuid);
        String targetName = targetData.getPlayerName() != null ? targetData.getPlayerName() : args[2];
        playerRef.sendMessage(Message.raw("Cleared all territory assignments for " + targetName + ".").color(Color.YELLOW));
    }

    private void handleTerritoryList(PlayerRef playerRef, PlayerData actorData, String[] args) {
        if (args.length < 3) {
            playerRef.sendMessage(Message.raw("Usage: /guild territory list <player>").color(Color.RED));
            return;
        }

        UUID targetUuid = resolvePlayerUuidByName(args[2]);
        if (targetUuid == null) {
            playerRef.sendMessage(Message.raw("Player '" + args[2] + "' not found.").color(Color.RED));
            return;
        }

        PlayerData targetData = plugin.getPlayerDataRepository().getPlayerData(targetUuid);
        if (targetData == null || targetData.getGuildId() == null || !targetData.getGuildId().equals(actorData.getGuildId())) {
            playerRef.sendMessage(Message.raw("That player is not in your guild.").color(Color.RED));
            return;
        }

        List<GuildChunkAccess> assignments = plugin.getGuildChunkAccessManager().getMemberAssignments(actorData.getGuildId(), targetUuid);
        String targetName = targetData.getPlayerName() != null ? targetData.getPlayerName() : args[2];

        playerRef.sendMessage(Message.raw("=== Territory Assignments: " + targetName + " (" + assignments.size() + ") ===").color(Color.ORANGE));
        if (assignments.isEmpty()) {
            playerRef.sendMessage(Message.raw("No assigned chunks.").color(Color.GRAY));
            return;
        }

        for (GuildChunkAccess assignment : assignments) {
            playerRef.sendMessage(Message.raw(
                "- " + assignment.getWorld() + " [" + assignment.getChunkX() + ", " + assignment.getChunkZ() + "] "
                    + "(edit=" + assignment.canEdit() + ", chest=" + assignment.canChest() + ")"
            ).color(Color.WHITE));
        }
    }

    private void showTerritoryHelp(PlayerRef playerRef) {
        playerRef.sendMessage(Message.raw("=== Guild Territory Commands ===").color(Color.ORANGE));
        playerRef.sendMessage(Message.raw("/guild territory assign <player> [chunkX chunkZ] [edit|chest|both]").color(Color.YELLOW));
        playerRef.sendMessage(Message.raw("/guild territory unassign <player> [chunkX chunkZ]").color(Color.YELLOW));
        playerRef.sendMessage(Message.raw("/guild territory list <player>").color(Color.YELLOW));
        playerRef.sendMessage(Message.raw("/guild territory clear <player>").color(Color.YELLOW));
        playerRef.sendMessage(Message.raw("If chunk coords are omitted, your current chunk is used.").color(Color.GRAY));
    }

    private void handleTag(PlayerRef playerRef, String[] args) {
        PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
        if (playerData == null || !playerData.isInGuild()) {
            playerRef.sendMessage(Message.raw("You are not in a guild!").color(Color.RED));
            return;
        }

        // Check if officer or higher
        if (!playerData.getGuildRole().hasAtLeast(GuildRole.OFFICER)) {
            playerRef.sendMessage(Message.raw("You need to be an Officer or higher to set the guild tag!").color(Color.RED));
            return;
        }

        Guild guild = plugin.getGuildManager().getGuild(playerData.getGuildId());
        if (guild == null) {
            playerRef.sendMessage(Message.raw("Error: Could not find your guild.").color(Color.RED));
            return;
        }

        // No args - show current tag
        if (args.length < 2) {
            String currentTag = guild.getTag();
            if (currentTag != null && !currentTag.isEmpty()) {
                playerRef.sendMessage(Message.raw("Current guild tag: [" + currentTag + "]").color(Color.GREEN));
            } else {
                playerRef.sendMessage(Message.raw("No custom tag set. Using: [" + guild.getDisplayTag() + "]").color(Color.GRAY));
            }
            playerRef.sendMessage(Message.raw("Usage: /guild tag <1-4 letters> or /guild tag clear").color(Color.YELLOW));
            return;
        }

        String newTag = args[1];

        // Handle "clear" to remove custom tag
        if (newTag.equalsIgnoreCase("clear") || newTag.equalsIgnoreCase("reset")) {
            guild.setTag(null);
            plugin.getGuildRepository().updateGuild(guild);
            // Update all guild members' nameplates
            updateGuildMemberNameplates(guild.getId());
            playerRef.sendMessage(Message.raw("Guild tag cleared. Using guild name: [" + guild.getDisplayTag() + "]").color(Color.GREEN));
            return;
        }

        // Validate tag: 1-4 uppercase letters only
        if (newTag.length() < 1 || newTag.length() > 4) {
            playerRef.sendMessage(Message.raw("Tag must be 1-4 characters!").color(Color.RED));
            return;
        }

        if (!newTag.matches("[A-Za-z]+")) {
            playerRef.sendMessage(Message.raw("Tag can only contain letters (A-Z)!").color(Color.RED));
            return;
        }

        // Convert to uppercase
        newTag = newTag.toUpperCase();

        // Save the tag
        guild.setTag(newTag);
        plugin.getGuildRepository().updateGuild(guild);

        // Update all guild members' nameplates
        updateGuildMemberNameplates(guild.getId());

        playerRef.sendMessage(Message.raw("Guild tag set to: [" + newTag + "]").color(Color.GREEN));
        playerRef.sendMessage(Message.raw("All guild member nameplates have been updated.").color(Color.GRAY));
    }

    private void updateGuildMemberNameplates(UUID guildId) {
        List<PlayerData> members = plugin.getGuildManager().getGuildMembers(guildId);
        for (PlayerData member : members) {
            plugin.getNameplateManager().updateNameplateForPlayer(member.getPlayerUuid());
        }
    }

    private void handleMap(PlayerRef playerRef, String[] args) {
        Vector3d pos = playerRef.getTransform().getPosition();
        String worldName = getWorldName(playerRef);
        if (worldName == null) {
            playerRef.sendMessage(Message.raw("Could not determine your location.").color(Color.RED));
            return;
        }

        int centerX = ClaimManager.toChunkCoord(pos.getX());
        int centerZ = ClaimManager.toChunkCoord(pos.getZ());

        int radius = 5;
        if (args.length >= 2) {
            try {
                radius = Math.min(10, Math.max(1, Integer.parseInt(args[1])));
            } catch (NumberFormatException ignored) {}
        }

        PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
        UUID playerGuildId = playerData != null ? playerData.getGuildId() : null;
        String playerFactionId = playerData != null ? playerData.getFactionId() : null;

        playerRef.sendMessage(Message.raw("=== Claim Map ===").color(Color.ORANGE));

        UUID playerUuid = playerRef.getUuid();

        for (int z = centerZ - radius; z <= centerZ + radius; z++) {
            StringBuilder row = new StringBuilder();
            for (int x = centerX - radius; x <= centerX + radius; x++) {
                Claim claim = plugin.getClaimManager().getClaim(worldName, x, z);

                if (x == centerX && z == centerZ) {
                    row.append("+");
                } else if (claim == null) {
                    row.append("-");
                } else if (claim.isFactionClaim()) {
                    // Faction claim - show as protected territory
                    if (playerFactionId != null && claim.getFactionId().equals(playerFactionId)) {
                        row.append("F"); // Our faction's protected area
                    } else {
                        row.append("X"); // Enemy faction territory
                    }
                } else if (claim.isSoloPlayerClaim()) {
                    // Solo player claim
                    if (playerUuid.equals(claim.getPlayerOwnerId())) {
                        row.append("P"); // Your personal claim
                    } else if (playerFactionId != null && claim.getFactionId().equals(playerFactionId)) {
                        row.append("#"); // Same faction player's claim
                    } else {
                        row.append("X"); // Enemy player's claim
                    }
                } else if (claim.getGuildId() != null && claim.getGuildId().equals(playerGuildId)) {
                    row.append("@");
                } else if (playerFactionId != null && claim.getFactionId().equals(playerFactionId)) {
                    row.append("#");
                } else {
                    row.append("X");
                }
            }
            playerRef.sendMessage(Message.raw(row.toString()).color(Color.WHITE));
        }

        playerRef.sendMessage(Message.raw("+ = You, - = Wild, @ = Guild, P = Personal, # = Ally, F = Faction, X = Enemy").color(Color.GRAY));
    }

    private String getWorldName(PlayerRef playerRef) {
        try {
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref != null && ref.isValid()) {
                World world = ref.getStore().getExternalData().getWorld();
                if (world != null) return world.getName();
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    private PlayerRef findOnlinePlayer(String name) {
        for (PlayerRef ref : Universe.get().getPlayers()) {
            if (ref != null && ref.getUsername().equalsIgnoreCase(name)) {
                return ref;
            }
        }
        return null;
    }

    private UUID findPlayerUuid(String name) {
        PlayerRef online = findOnlinePlayer(name);
        if (online != null) {
            return online.getUuid();
        }
        return null;
    }

    private UUID resolvePlayerUuidByName(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return null;
        }

        UUID onlineUuid = findPlayerUuid(playerName);
        if (onlineUuid != null) {
            return onlineUuid;
        }

        PlayerData data = plugin.getPlayerDataRepository().getPlayerDataByName(playerName);
        return data != null ? data.getPlayerUuid() : null;
    }

    private boolean isInteger(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }

        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
