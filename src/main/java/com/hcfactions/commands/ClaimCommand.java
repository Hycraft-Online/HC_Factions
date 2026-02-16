package com.hcfactions.commands;

import com.hcfactions.HC_FactionsPlugin;
import com.hcfactions.gui.GuildClaimGui;
import com.hcfactions.managers.ClaimManager;
import com.hcfactions.models.Claim;
import com.hcfactions.models.Guild;
import com.hcfactions.models.GuildRole;
import com.hcfactions.models.PlayerData;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import java.awt.Color;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Universal claim command for both solo players and guild members.
 * Usage:
 *   /claim - Claim current chunk
 *   /claim unclaim - Unclaim current chunk
 *   /claim list - List your claims
 *   /claim map [radius] - Show claim map
 */
public class ClaimCommand extends AbstractAsyncCommand {

    private final HC_FactionsPlugin plugin;

    public ClaimCommand(HC_FactionsPlugin plugin) {
        super("claim", "Claim and manage land");
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

        // Parse args
        String inputString = ctx.getInputString();
        String[] parts = inputString.split("\\s+");
        String[] args = parts.length > 1 ? Arrays.copyOfRange(parts, 1, parts.length) : new String[]{};

        return CompletableFuture.runAsync(() -> {
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null) {
                sender.sendMessage(Message.raw("Error: Could not get player reference.").color(Color.RED));
                return;
            }

            // No args - open claim GUI
            if (args.length == 0) {
                handleClaimGui(player, ref, store, playerRef);
                return;
            }

            String subCommand = args[0].toLowerCase();

            switch (subCommand) {
                case "unclaim" -> handleUnclaim(playerRef);
                case "list" -> handleListClaims(playerRef);
                case "map" -> handleMap(playerRef, args);
                case "help" -> showHelp(playerRef);
                default -> {
                    playerRef.sendMessage(Message.raw("Unknown subcommand: " + subCommand).color(Color.RED));
                    showHelp(playerRef);
                }
            }
        }, world);
    }

    private void showHelp(PlayerRef playerRef) {
        playerRef.sendMessage(Message.raw("=== Claim Commands ===").color(Color.ORANGE));
        playerRef.sendMessage(Message.raw("/claim - Open claim manager GUI").color(Color.YELLOW));
        playerRef.sendMessage(Message.raw("/claim unclaim - Unclaim current chunk").color(Color.YELLOW));
        playerRef.sendMessage(Message.raw("/claim list - List your claims").color(Color.YELLOW));
        playerRef.sendMessage(Message.raw("/claim map [radius] - Show nearby claims").color(Color.YELLOW));
    }

    private void handleClaimGui(Player player, Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef playerRef) {
        PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
        if (playerData == null || !playerData.hasChosenFaction()) {
            playerRef.sendMessage(Message.raw("You must choose a faction first!").color(Color.RED));
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

        GuildClaimGui gui;
        if (playerData.isInGuild()) {
            // Guild member - check permissions
            if (!playerData.getGuildRole().hasAtLeast(GuildRole.OFFICER)) {
                playerRef.sendMessage(Message.raw("You need to be an Officer or higher to manage guild claims!").color(Color.RED));
                return;
            }

            gui = new GuildClaimGui(
                plugin,
                playerRef,
                playerData.getGuildId(),
                playerData.getFactionId(),
                worldName,
                chunkX,
                chunkZ
            );

            Guild guild = plugin.getGuildManager().getGuild(playerData.getGuildId());
            playerRef.sendMessage(Message.raw("Opened claim manager for " + (guild != null ? guild.getName() : "your guild")).color(Color.GREEN));
        } else {
            // Solo player
            gui = GuildClaimGui.forSoloPlayer(
                plugin,
                playerRef,
                playerData.getFactionId(),
                worldName,
                chunkX,
                chunkZ
            );

            playerRef.sendMessage(Message.raw("Opened personal claim manager").color(Color.GREEN));
        }

        player.getPageManager().openCustomPage(ref, store, gui);
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
                playerRef.sendMessage(Message.raw("Use /claim to claim land.").color(Color.YELLOW));
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
        UUID playerUuid = playerRef.getUuid();

        playerRef.sendMessage(Message.raw("=== Claim Map ===").color(Color.ORANGE));

        for (int z = centerZ - radius; z <= centerZ + radius; z++) {
            StringBuilder row = new StringBuilder();
            for (int x = centerX - radius; x <= centerX + radius; x++) {
                Claim claim = plugin.getClaimManager().getClaim(worldName, x, z);

                if (x == centerX && z == centerZ) {
                    row.append("+");
                } else if (claim == null) {
                    row.append("-");
                } else if (claim.isFactionClaim()) {
                    if (playerFactionId != null && claim.getFactionId().equals(playerFactionId)) {
                        row.append("F");
                    } else {
                        row.append("X");
                    }
                } else if (claim.isSoloPlayerClaim()) {
                    if (playerUuid.equals(claim.getPlayerOwnerId())) {
                        row.append("P");
                    } else if (playerFactionId != null && claim.getFactionId().equals(playerFactionId)) {
                        row.append("#");
                    } else {
                        row.append("X");
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
}
