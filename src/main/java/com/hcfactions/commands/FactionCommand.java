package com.hcfactions.commands;

import com.hcfactions.HC_FactionsPlugin;
import com.hcfactions.gui.FactionSelectionGui;
import com.hcfactions.models.Faction;
import com.hcfactions.models.PlayerData;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import java.awt.Color;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

/**
 * Faction command available to all players.
 * /faction choose - Open the faction selection GUI
 * No permission required.
 */
public class FactionCommand extends AbstractAsyncCommand {

    private final HC_FactionsPlugin plugin;

    public FactionCommand(HC_FactionsPlugin plugin) {
        super("faction", "Faction commands");
        this.addAliases("f");
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

        String inputString = ctx.getInputString();
        String[] parts = inputString.split("\\s+");
        String[] args = parts.length > 1 ? Arrays.copyOfRange(parts, 1, parts.length) : new String[]{};

        if (args.length == 0) {
            showHelp(sender);
            return CompletableFuture.completedFuture(null);
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "choose", "select" -> handleChoose(sender);
            default -> showHelp(sender);
        }

        return CompletableFuture.completedFuture(null);
    }

    private void showHelp(CommandSender sender) {
        sender.sendMessage(Message.raw("=== Faction Commands ===").color(Color.ORANGE));
        sender.sendMessage(Message.raw("/faction choose - Choose or view your faction").color(Color.YELLOW));
    }

    private void handleChoose(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Message.raw("This command can only be used by players.").color(Color.RED));
            return;
        }

        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) {
            sender.sendMessage(Message.raw("Error: Invalid player reference.").color(Color.RED));
            return;
        }

        player.getWorld().execute(() -> {
            Store<EntityStore> store = ref.getStore();
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null) {
                sender.sendMessage(Message.raw("Error: Could not get player reference.").color(Color.RED));
                return;
            }

            // Check if player already has a faction
            PlayerData playerData = plugin.getPlayerDataRepository().getPlayerData(playerRef.getUuid());
            if (playerData != null && playerData.hasChosenFaction()) {
                Faction faction = plugin.getFactionManager().getFaction(playerData.getFactionId());

                // If player is in a non-main world (instance, starter area), show GUI for reaffirmation
                World currentWorld = player.getWorld();
                String worldName = currentWorld != null ? currentWorld.getName() : "";
                String factionWorld = faction != null ? faction.getSpawnWorld() : "default";
                boolean isInMainWorld = worldName.equals(factionWorld) || worldName.equals("default");

                if (!isInMainWorld && faction != null) {
                    player.getPageManager().openCustomPage(ref, store, new FactionSelectionGui(plugin, playerRef));
                    return;
                }

                sender.sendMessage(Message.raw("You are already in " +
                    (faction != null ? faction.getDisplayName() : playerData.getFactionId()) +
                    ".").color(Color.YELLOW));
                return;
            }

            // Open the faction selection GUI for new players
            player.getPageManager().openCustomPage(ref, store, new FactionSelectionGui(plugin, playerRef));
        });
    }
}
