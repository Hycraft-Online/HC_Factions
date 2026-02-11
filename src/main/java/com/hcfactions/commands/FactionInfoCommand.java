package com.hcfactions.commands;

import com.hcfactions.HC_FactionsPlugin;
import com.hcfactions.models.Faction;
import com.hcfactions.models.Guild;
import com.hcfactions.models.PlayerData;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hcfactions.gui.FactionMenuGui;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import java.awt.Color;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Faction info/menu command for players who already have a faction.
 * Handles: menu, info, help subcommands.
 */
public class FactionInfoCommand extends AbstractAsyncCommand {

    private final HC_FactionsPlugin plugin;

    public FactionInfoCommand(HC_FactionsPlugin plugin) {
        super("factioninfo", "Faction menu and information");
        this.addAliases("finfo");
        this.setAllowsExtraArguments(true);
        this.plugin = plugin;
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

        String inputString = ctx.getInputString();
        String[] parts = inputString.split("\\s+");
        String[] args = parts.length > 1 ? Arrays.copyOfRange(parts, 1, parts.length) : new String[]{};

        return CompletableFuture.runAsync(() -> {
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null) {
                sender.sendMessage(Message.raw("Error: Could not get player reference.").color(Color.RED));
                return;
            }

            PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
            if (playerData == null || playerData.getFactionId() == null) {
                sender.sendMessage(Message.raw("You must choose a faction first!").color(Color.RED));
                sender.sendMessage(Message.raw("Use /fa choose to select a faction.").color(Color.GRAY));
                return;
            }

            if (args.length == 0 || args[0].equalsIgnoreCase("menu")) {
                player.getPageManager().openCustomPage(ref, store, new FactionMenuGui(plugin, playerRef));
                return;
            }

            String subCommand = args[0].toLowerCase();

            switch (subCommand) {
                case "info" -> showFactionInfo(playerRef, playerData);
                case "help" -> showHelp(playerRef);
                default -> {
                    sender.sendMessage(Message.raw("Unknown subcommand: " + subCommand).color(Color.RED));
                    showHelp(playerRef);
                }
            }
        }, store.getExternalData().getWorld()::execute);
    }

    private void showFactionInfo(PlayerRef playerRef, PlayerData playerData) {
        Faction faction = plugin.getFactionManager().getFaction(playerData.getFactionId());
        if (faction == null) {
            playerRef.sendMessage(Message.raw("Could not load faction info.").color(Color.RED));
            return;
        }

        List<UUID> factionMembers = plugin.getPlayerDataRepository().getFactionMembers(faction.getId());
        List<Guild> factionGuilds = plugin.getGuildRepository().getGuildsByFaction(faction.getId());

        int totalClaims = 0;
        for (Guild guild : factionGuilds) {
            totalClaims += plugin.getClaimManager().getClaimCount(guild.getId());
        }
        totalClaims += plugin.getClaimManager().getFactionOnlyClaims(faction.getId()).size();

        playerRef.sendMessage(Message.raw("=== " + faction.getDisplayName() + " ===").color(faction.getColor()));
        playerRef.sendMessage(Message.raw("Members: " + factionMembers.size()).color(Color.WHITE));
        playerRef.sendMessage(Message.raw("Guilds: " + factionGuilds.size()).color(Color.WHITE));
        playerRef.sendMessage(Message.raw("Total Territory: " + totalClaims + " chunks").color(Color.WHITE));

        if (!factionGuilds.isEmpty()) {
            playerRef.sendMessage(Message.raw("").color(Color.WHITE));
            playerRef.sendMessage(Message.raw("Top Guilds:").color(Color.ORANGE));
            factionGuilds.stream()
                .sorted((a, b) -> Integer.compare(b.getPower(), a.getPower()))
                .limit(5)
                .forEach(guild -> {
                    playerRef.sendMessage(Message.raw("  - " + guild.getName() + " (Power: " + guild.getPower() + ")").color(Color.WHITE));
                });
        }
    }

    private void showHelp(PlayerRef playerRef) {
        playerRef.sendMessage(Message.raw("=== Faction Commands ===").color(Color.ORANGE));
        playerRef.sendMessage(Message.raw("/fa - Choose your faction").color(Color.YELLOW));
        playerRef.sendMessage(Message.raw("/factioninfo - Open faction menu").color(Color.YELLOW));
        playerRef.sendMessage(Message.raw("/factioninfo info - View faction overview").color(Color.YELLOW));
        playerRef.sendMessage(Message.raw("/factioninfo help - Show this help").color(Color.YELLOW));
        playerRef.sendMessage(Message.raw("").color(Color.WHITE));
        playerRef.sendMessage(Message.raw("Use /guild for guild-specific commands.").color(Color.GRAY));
    }
}
