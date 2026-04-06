package com.hcfactions;

import com.hcfactions.chat.FactionChatFormatter;
import com.hcfactions.commands.ClaimCommand;
import com.hcfactions.commands.FactionCommand;
import com.hcfactions.commands.FactionInfoCommand;
import com.hcfactions.commands.GuildCommand;
import com.hcfactions.commands.FactionAdminCommand;
import com.hccore.api.HC_CoreAPI;
import com.hcfactions.config.FactionGuildsConfig;
import com.hcfactions.database.DatabaseManager;
import com.hcfactions.database.repositories.ClaimRepository;
import com.hcfactions.database.repositories.FactionRepository;
import com.hcfactions.database.repositories.GuildChunkAccessRepository;
import com.hcfactions.database.repositories.GuildChunkRoleAccessRepository;
import com.hcfactions.database.repositories.GuildRepository;
import com.hcfactions.database.repositories.PlayerDataRepository;
import com.hcfactions.gui.FactionSelectionGui;
import com.hcfactions.hud.HudWrapper;
import com.hcfactions.hud.TerritoryHud;
import com.hcfactions.managers.ClaimDecayManager;
import com.hcfactions.managers.ClaimManager;
import com.hcfactions.managers.PickupBlacklistManager;
import com.hcfactions.managers.FactionManager;
import com.hcfactions.managers.GuildChunkAccessManager;
import com.hcfactions.managers.GuildChunkRoleAccessManager;
import com.hcfactions.managers.GuildLogManager;
import com.hcfactions.managers.GuildManager;
import com.hcfactions.managers.SpawnSuppressionManager;
import com.hcfactions.nameplates.NameplateManager;
import com.hcfactions.map.ClaimMapManager;
import com.hcfactions.map.ClaimWorldMapProvider;
import com.hcfactions.map.FactionCapitalMarkerProvider;
import com.hcfactions.systems.BedBreakRespawnClearSystem;
import com.hcfactions.systems.BedRespawnCleanupSystem;
import com.hcfactions.systems.ClaimProtectionSystem;
import com.hcfactions.systems.ClaimBreakProtectionSystem;
import com.hcfactions.systems.ClaimBreakBlockEventSystem;
import com.hcfactions.systems.ClaimInteractProtectionSystem;
import com.hcfactions.systems.ClaimPickupProtectionSystem;
import com.hcfactions.systems.FactionDamageSystem;
import com.hcfactions.systems.ChunkEnterNotifySystem;
import com.hcfactions.systems.FactionRespawnSystem;
import com.hcfactions.systems.FactionPlayerMarkerTicker;
import com.hcfactions.systems.WorldMapUpdateSystem;

import com.hcfactions.combat.CombatTagComponent;
import com.hcfactions.combat.CombatTagDamageSystem;
import com.hcfactions.combat.CombatTagManager;
import com.hcfactions.combat.CombatTagSystem;

import com.hcfactions.interactions.ClaimChangeBlockInteraction;
import com.hcfactions.interactions.ClaimHarvestCropInteraction;
import com.hcfactions.interactions.ClaimUseBlockInteraction;

import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.universe.world.events.AddWorldEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldmap.provider.IWorldMapProvider;
import com.hypixel.hytale.server.core.asset.common.CommonAssetModule;
import com.hypixel.hytale.server.core.asset.common.ResourceCommonAsset;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import java.awt.Color;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * HC_Factions - A hybrid faction-guild system for Hytale.
 *
 * Combines admin-defined major factions (Valor/Iron Legion) with player-created guilds.
 * - Players must choose a major faction on first join
 * - Players can create/join guilds within their faction
 * - PvP is faction-based (same faction protected)
 * - Land protection is guild-based (only guild members can build)
 */
public class HC_FactionsPlugin extends JavaPlugin {

    public static final String VERSION = "1.0.0";

    private static volatile HC_FactionsPlugin instance;

    // ═══════════════════════════════════════════════════════
    // CLAIM BYPASS API - allows external plugins to bypass claim protection
    // ═══════════════════════════════════════════════════════

    public enum ClaimBypassOperation { PLACE, BREAK, INTERACT }

    @FunctionalInterface
    public interface ClaimBypassCheck {
        boolean shouldBypass(UUID playerUuid, String worldName, int blockX, int blockY, int blockZ, ClaimBypassOperation op);

        /**
         * Extended bypass check that includes the item being placed.
         * Override this in bypass implementations that need to restrict by item type.
         */
        default boolean shouldBypass(UUID playerUuid, String worldName, int blockX, int blockY, int blockZ, ClaimBypassOperation op, String itemId) {
            return shouldBypass(playerUuid, worldName, blockX, blockY, blockZ, op);
        }
    }

    private static final List<ClaimBypassCheck> claimBypassChecks = new CopyOnWriteArrayList<>();

    public static void registerClaimBypass(ClaimBypassCheck check) {
        if (check != null) claimBypassChecks.add(check);
    }

    public static void unregisterClaimBypass(ClaimBypassCheck check) {
        claimBypassChecks.remove(check);
    }

    public static boolean isClaimBypassed(UUID playerUuid, String worldName, int blockX, int blockY, int blockZ, ClaimBypassOperation op) {
        return isClaimBypassed(playerUuid, worldName, blockX, blockY, blockZ, op, null);
    }

    public static boolean isClaimBypassed(UUID playerUuid, String worldName, int blockX, int blockY, int blockZ, ClaimBypassOperation op, String itemId) {
        if (instance != null) {
            instance.getLogger().at(Level.FINE).log("[ClaimBypass] checking " + claimBypassChecks.size() + " bypass(es) for op=" + op
                    + " at (" + blockX + "," + blockY + "," + blockZ + ") world=" + worldName
                    + " item=" + (itemId != null ? itemId : "null"));
        }
        for (ClaimBypassCheck check : claimBypassChecks) {
            try {
                if (check.shouldBypass(playerUuid, worldName, blockX, blockY, blockZ, op, itemId)) {
                    return true;
                }
            } catch (Exception e) {
                if (instance != null) {
                    instance.getLogger().at(Level.WARNING).log("[ClaimBypass] Exception in bypass check: " + e.getMessage());
                }
            }
        }
        return false;
    }

    // Tracks admins who have explicitly enabled their bypass (disabled by default)
    private static final Set<UUID> bypassEnabled = ConcurrentHashMap.newKeySet();

    // Tracks players granted editor access to faction-claimed land
    private static final Set<UUID> factionEditors = ConcurrentHashMap.newKeySet();

    // Database
    private DatabaseManager databaseManager;

    // Configuration (reads live from HC_CoreAPI mod_settings)
    private FactionGuildsConfig config;

    // Repositories
    private PlayerDataRepository playerDataRepository;
    private GuildRepository guildRepository;
    private ClaimRepository claimRepository;
    private GuildChunkAccessRepository guildChunkAccessRepository;
    private GuildChunkRoleAccessRepository guildChunkRoleAccessRepository;
    private FactionRepository factionRepository;

    // Managers
    private FactionManager factionManager;
    private GuildManager guildManager;
    private ClaimManager claimManager;
    private GuildChunkAccessManager guildChunkAccessManager;
    private GuildChunkRoleAccessManager guildChunkRoleAccessManager;
    private PickupBlacklistManager pickupBlacklistManager;
    private SpawnSuppressionManager spawnSuppressionManager;
    private NameplateManager nameplateManager;
    private GuildLogManager guildLogManager;
    private ClaimDecayManager claimDecayManager;
    private CombatTagManager combatTagManager;

    // Systems
    private ChunkEnterNotifySystem chunkEnterNotifySystem;

    // Territory HUD tracking
    private final Map<UUID, TerritoryHud> territoryHuds = new ConcurrentHashMap<>();

    public HC_FactionsPlugin(@NonNullDecl JavaPluginInit init) {
        super(init);
        instance = this;
    }

    public static HC_FactionsPlugin getInstance() {
        return instance;
    }

    /**
     * Checks if bypass is enabled for a player.
     * Bypass is disabled by default - admins must explicitly enable it.
     */
    public static boolean isBypassEnabled(UUID playerId) {
        return bypassEnabled.contains(playerId);
    }

    /**
     * Checks if a world is an arena/instance world that should skip faction systems.
     * This reduces overhead in temporary game worlds.
     */
    public static boolean isArenaWorld(String worldName) {
        if (worldName == null) return false;
        return worldName.startsWith("zombie-arena-")
            || worldName.startsWith("ffa-arena-")
            || worldName.startsWith("instance-")
            || worldName.startsWith("arena_")
            || worldName.equals("FFA_Arena");
    }

    private static final String CAPITAL_MARKER_ICON = "FactionGuilds_Capital.png";

    private void registerCapitalMarkerIcon() {
        try {
            String resourcePath = "/Common/UI/WorldMap/MapMarkers/" + CAPITAL_MARKER_ICON;
            ResourceCommonAsset asset = ResourceCommonAsset.of(getClass(), resourcePath, CAPITAL_MARKER_ICON);
            if (asset == null) {
                this.getLogger().at(Level.WARNING).log("[HC_Factions] " + CAPITAL_MARKER_ICON + " not found in resources");
                return;
            }
            CommonAssetModule.get().addCommonAsset(CAPITAL_MARKER_ICON, asset);
            this.getLogger().at(Level.INFO).log("[HC_Factions] Registered capital marker icon: " + CAPITAL_MARKER_ICON);
        } catch (Exception e) {
            this.getLogger().at(Level.SEVERE).log("[HC_Factions] Failed to register capital marker icon: " + e.getMessage());
        }
    }

    /**
     * Toggles bypass mode for a player.
     * @return true if bypass is now enabled, false if disabled
     */
    public static boolean toggleBypass(UUID playerId) {
        if (bypassEnabled.contains(playerId)) {
            bypassEnabled.remove(playerId);
            return false; // Bypass now disabled
        } else {
            bypassEnabled.add(playerId);
            return true; // Bypass now enabled
        }
    }

    /**
     * Checks if a player has faction editor access (can build/break on faction-claimed land).
     */
    public static boolean isFactionEditor(UUID playerId) {
        return factionEditors.contains(playerId);
    }

    /**
     * Grants a player faction editor access.
     * @return true if newly added, false if already an editor
     */
    public static boolean addFactionEditor(UUID playerId) {
        return factionEditors.add(playerId);
    }

    /**
     * Revokes a player's faction editor access.
     * @return true if removed, false if wasn't an editor
     */
    public static boolean removeFactionEditor(UUID playerId) {
        return factionEditors.remove(playerId);
    }

    /**
     * Returns an unmodifiable view of all faction editor UUIDs.
     */
    public static Set<UUID> getFactionEditors() {
        return Set.copyOf(factionEditors);
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public FactionGuildsConfig getConfig() {
        return config;
    }

    public PlayerDataRepository getPlayerDataRepository() {
        return playerDataRepository;
    }

    public GuildRepository getGuildRepository() {
        return guildRepository;
    }

    public ClaimRepository getClaimRepository() {
        return claimRepository;
    }

    public GuildChunkAccessRepository getGuildChunkAccessRepository() {
        return guildChunkAccessRepository;
    }

    public GuildChunkRoleAccessRepository getGuildChunkRoleAccessRepository() {
        return guildChunkRoleAccessRepository;
    }

    public FactionRepository getFactionRepository() {
        return factionRepository;
    }

    public FactionManager getFactionManager() {
        return factionManager;
    }

    public GuildManager getGuildManager() {
        return guildManager;
    }

    public ClaimManager getClaimManager() {
        return claimManager;
    }

    public GuildChunkAccessManager getGuildChunkAccessManager() {
        return guildChunkAccessManager;
    }

    public GuildChunkRoleAccessManager getGuildChunkRoleAccessManager() {
        return guildChunkRoleAccessManager;
    }

    public PickupBlacklistManager getPickupBlacklistManager() {
        return pickupBlacklistManager;
    }

    public SpawnSuppressionManager getSpawnSuppressionManager() {
        return spawnSuppressionManager;
    }

    public NameplateManager getNameplateManager() {
        return nameplateManager;
    }

    public GuildLogManager getGuildLogManager() {
        return guildLogManager;
    }

    public ClaimDecayManager getClaimDecayManager() {
        return claimDecayManager;
    }

    public CombatTagManager getCombatTagManager() {
        return combatTagManager;
    }

    public ChunkEnterNotifySystem getChunkEnterNotifySystem() {
        return chunkEnterNotifySystem;
    }

    /**
     * Gets the territory HUD for a player, or null if not registered.
     */
    public TerritoryHud getTerritoryHud(UUID playerUuid) {
        return territoryHuds.get(playerUuid);
    }

    /**
     * Removes the territory HUD reference for a player (call on disconnect).
     */
    public void removeTerritoryHud(UUID playerUuid) {
        territoryHuds.remove(playerUuid);
    }

    @Override
    protected void setup() {
        super.setup();
        
        // Register custom interaction overrides to prevent exploits on claimed land
        // UseBlock: blocks harvestable blocks (plants/crops/flowers) — portals, doors, etc. pass through
        this.getCodecRegistry(Interaction.CODEC).register(
            "UseBlock", ClaimUseBlockInteraction.class, ClaimUseBlockInteraction.CODEC);
        // HarvestCrop: blocks sickle crop harvesting on claimed land
        this.getCodecRegistry(Interaction.CODEC).register(
            "HarvestCrop", ClaimHarvestCropInteraction.class, ClaimHarvestCropInteraction.CODEC);
        // ChangeBlock: blocks hoe tilling on claimed land
        this.getCodecRegistry(Interaction.CODEC).register(
            "ChangeBlock", ClaimChangeBlockInteraction.class, ClaimChangeBlockInteraction.CODEC);

        this.getLogger().at(Level.INFO).log("=================================");
        this.getLogger().at(Level.INFO).log("       HC_FACTIONS " + VERSION);
        this.getLogger().at(Level.INFO).log("=================================");

        // ═══════════════════════════════════════════════════════
        // DATABASE INITIALIZATION (via HC_Core shared pool)
        // ═══════════════════════════════════════════════════════
        this.getLogger().at(Level.INFO).log("Initializing database via HC_Core...");
        try {
            // Register config defaults with HC_Core (seeds mod_settings if empty)
            FactionGuildsConfig.registerDefaults();
            this.getLogger().at(Level.INFO).log("Settings defaults registered with HC_Core");

            // DatabaseManager now uses HC_Core's pool (initializes game schema)
            databaseManager = new DatabaseManager();
            this.getLogger().at(Level.INFO).log("Database schema initialized");

            // Initialize repositories
            playerDataRepository = new PlayerDataRepository(databaseManager);
            guildRepository = new GuildRepository(databaseManager);
            claimRepository = new ClaimRepository(databaseManager);
            guildChunkAccessRepository = new GuildChunkAccessRepository(databaseManager);
            guildChunkRoleAccessRepository = new GuildChunkRoleAccessRepository(databaseManager);
            factionRepository = new FactionRepository(databaseManager);
            this.getLogger().at(Level.INFO).log("Repositories initialized");

            // Seed default factions if none exist
            factionRepository.seedDefaultFactions();

            // Config reads live from HC_CoreAPI — no loading needed
            config = new FactionGuildsConfig();
            this.getLogger().at(Level.INFO).log("Configuration ready (via HC_Core mod_settings)");

        } catch (Exception e) {
            this.getLogger().at(Level.SEVERE).log("Failed to initialize database: " + e.getMessage());
            this.getLogger().at(Level.SEVERE).log("Plugin features will be disabled!");
            return;
        }

        // ═══════════════════════════════════════════════════════
        // MANAGER INITIALIZATION
        // ═══════════════════════════════════════════════════════
        guildLogManager = new GuildLogManager(databaseManager);
        this.getLogger().at(Level.INFO).log("Guild log manager initialized");

        factionManager = new FactionManager(this);
        this.getLogger().at(Level.INFO).log("Loaded " + factionManager.getFactions().size() + " factions");

        guildManager = new GuildManager(this);
        this.getLogger().at(Level.INFO).log("Guild manager initialized");

        claimManager = new ClaimManager(this);
        this.getLogger().at(Level.INFO).log("Claim manager initialized");

        guildChunkRoleAccessManager = new GuildChunkRoleAccessManager(guildChunkRoleAccessRepository);
        this.getLogger().at(Level.INFO).log("Guild chunk role access manager initialized");

        guildChunkAccessManager = new GuildChunkAccessManager(guildChunkAccessRepository, guildChunkRoleAccessManager);
        this.getLogger().at(Level.INFO).log("Guild chunk access manager initialized");

        spawnSuppressionManager = new SpawnSuppressionManager(this);
        claimManager.setSpawnSuppressionManager(spawnSuppressionManager);
        this.getLogger().at(Level.INFO).log("Spawn suppression manager initialized (enabled=" + config.isSpawnSuppressionEnabled() + ")");

        pickupBlacklistManager = new PickupBlacklistManager(this);
        this.getLogger().at(Level.INFO).log("Pickup blacklist manager initialized with " + pickupBlacklistManager.getPatternCount() + " patterns");

        nameplateManager = new NameplateManager(this);
        this.getLogger().at(Level.INFO).log("Nameplate manager initialized");

        claimDecayManager = new ClaimDecayManager(this);
        this.getLogger().at(Level.INFO).log("Claim decay manager initialized (enabled=" + config.isClaimDecayEnabled() + ")");

        combatTagManager = new CombatTagManager();
        this.getLogger().at(Level.INFO).log("Combat tag manager initialized (duration=" + config.getCombatTagDurationSeconds() + "s, logoutPenalty=" + config.isCombatLogoutPenaltyEnabled() + ")");

        // ═══════════════════════════════════════════════════════
        // MAP MARKER ICON REGISTRATION
        // ═══════════════════════════════════════════════════════
        registerCapitalMarkerIcon();

        // ═══════════════════════════════════════════════════════
        // WORLD MAP PROVIDER REGISTRATION
        // ═══════════════════════════════════════════════════════
        IWorldMapProvider.CODEC.register(ClaimWorldMapProvider.ID, ClaimWorldMapProvider.class, ClaimWorldMapProvider.CODEC);
        this.getLogger().at(Level.INFO).log("Registered ClaimWorldMapProvider codec");

        // Register event to set world map provider on each world (disable for arena worlds)
        this.getEventRegistry().registerGlobal(AddWorldEvent.class, (event) -> {
            String worldName = event.getWorld().getName();
            this.getLogger().at(Level.INFO).log("[HC_Factions] World added: " + worldName);

            // Disable world map entirely for arena/instance worlds to improve performance
            if (isArenaWorld(worldName)) {
                event.getWorld().getWorldConfig().setWorldMapProvider(null);
                this.getLogger().at(Level.INFO).log("[HC_Factions] Disabled world map for arena world: " + worldName);
                return;
            }

            // Use our claim world map provider for regular worlds
            event.getWorld().getWorldConfig().setWorldMapProvider(new ClaimWorldMapProvider());
            this.getLogger().at(Level.INFO).log("[HC_Factions] Set ClaimWorldMapProvider for world: " + worldName);

            // Remove default playerIcons provider — FactionPlayerMarkerTicker handles player markers
            var mapManager = event.getWorld().getWorldMapManager();
            if (mapManager != null) {
                mapManager.getMarkerProviders().remove("playerIcons");
                mapManager.addMarkerProvider(
                    FactionCapitalMarkerProvider.PROVIDER_ID,
                    new FactionCapitalMarkerProvider()
                );
            }
        });

        // ═══════════════════════════════════════════════════════
        // ECS COMPONENTS (transient -- no persistence)
        // ═══════════════════════════════════════════════════════
        CombatTagComponent.setComponentType(
                this.getEntityStoreRegistry().registerComponent(CombatTagComponent.class, CombatTagComponent::new));
        this.getLogger().at(Level.INFO).log("Registered CombatTagComponent (transient)");

        // ═══════════════════════════════════════════════════════
        // ECS SYSTEMS
        // ═══════════════════════════════════════════════════════
        this.getEntityStoreRegistry().registerSystem(new FactionDamageSystem(this));
        this.getEntityStoreRegistry().registerSystem(new CombatTagDamageSystem(this, combatTagManager));
        this.getEntityStoreRegistry().registerSystem(new CombatTagSystem(combatTagManager));
        this.getEntityStoreRegistry().registerSystem(new ClaimProtectionSystem(this));
        this.getEntityStoreRegistry().registerSystem(new ClaimBreakProtectionSystem(this));
        this.getEntityStoreRegistry().registerSystem(new ClaimBreakBlockEventSystem(this));
        this.getEntityStoreRegistry().registerSystem(new ClaimInteractProtectionSystem(this));
        this.getEntityStoreRegistry().registerSystem(new ClaimPickupProtectionSystem(this));
        chunkEnterNotifySystem = new ChunkEnterNotifySystem(this);
        this.getEntityStoreRegistry().registerSystem(chunkEnterNotifySystem);
        this.getEntityStoreRegistry().registerSystem(new FactionRespawnSystem(this));
        this.getEntityStoreRegistry().registerSystem(new BedRespawnCleanupSystem(this));
        this.getEntityStoreRegistry().registerSystem(new BedBreakRespawnClearSystem(this));
        this.getEntityStoreRegistry().registerSystem(new FactionPlayerMarkerTicker(this));
        this.getChunkStoreRegistry().registerSystem(new WorldMapUpdateSystem());
        this.getLogger().at(Level.INFO).log("Registered ECS systems (damage, combat tag, block/break protection, pickup protection, notifications, respawn, bed cleanup, map updates)");

        // ═══════════════════════════════════════════════════════
        // COMMANDS
        // ═══════════════════════════════════════════════════════
        this.getCommandRegistry().registerCommand(new FactionCommand(this));
        this.getCommandRegistry().registerCommand(new FactionInfoCommand(this));
        this.getCommandRegistry().registerCommand(new GuildCommand(this));
        this.getCommandRegistry().registerCommand(new ClaimCommand(this));
        this.getCommandRegistry().registerCommand(new FactionAdminCommand(this));
        this.getLogger().at(Level.INFO).log("Registered commands: /faction, /factioninfo, /guild, /claim, /factionadmin");

        // ═══════════════════════════════════════════════════════
        // CHAT FORMATTING (faction colors + guild tags)
        // ═══════════════════════════════════════════════════════
        FactionChatFormatter chatFormatter = new FactionChatFormatter(this);
        try {
            Class.forName("com.github.heroslender.herochat.event.ChannelChatEvent");
            chatFormatter.registerHeroChat(this.getEventRegistry());
            this.getLogger().at(Level.INFO).log("HeroChat integration enabled (faction-colored chat)");
        } catch (ClassNotFoundException e) {
            chatFormatter.registerVanilla(this.getEventRegistry());
            this.getLogger().at(Level.INFO).log("HeroChat not found, using vanilla chat formatter (faction-colored chat)");
        }

        // ═══════════════════════════════════════════════════════
        // PLAYER CONNECT EVENT - Faction Selection Flow
        // ═══════════════════════════════════════════════════════
        this.getEventRegistry().register(PlayerConnectEvent.class, (event) -> {
            PlayerRef playerRef = event.getPlayerRef();
            Player player = event.getPlayer();
            World world = event.getWorld();

            this.getLogger().at(Level.INFO).log("[HC_Factions] Player connected: " + playerRef.getUsername());

            // Update last_online timestamp for claim decay tracking
            playerDataRepository.updateLastOnline(playerRef.getUuid());

            // Check if player has chosen a faction
            var playerData = playerDataRepository.getPlayerData(playerRef.getUuid());
            boolean needsFaction = (playerData == null || !playerData.hasChosenFaction());

            // Always set nameplate (plain white name for factionless, faction-colored for others)
            HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
                nameplateManager.updateNameplateForPlayer(playerRef.getUuid());
            }, 2, TimeUnit.SECONDS);

            if (needsFaction) {
                // Faction selection is handled by StarterArea plugin after tutorial completion
                // Uncomment below to re-enable automatic faction selection UI on connect
                this.getLogger().at(Level.INFO).log("[HC_Factions] Player " + playerRef.getUsername() + " needs faction selection (handled by StarterArea)");

                // playerRef.sendMessage(Message.raw("[HC_Factions] Please select your faction!").color(Color.CYAN));
                // this.getLogger().at(Level.INFO).log("[HC_Factions] Scheduling faction selection UI for " + playerRef.getUsername());
                //
                // // Delay longer to allow MOTD/welcome screens to be shown and dismissed first
                // HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
                //     world.execute(() -> {
                //         try {
                //             var ref = player.getReference();
                //             if (ref != null && ref.isValid()) {
                //                 var store = ref.getStore();
                //                 player.getPageManager().openCustomPage(ref, store,
                //                     new FactionSelectionGui(this, playerRef));
                //             }
                //         } catch (Exception e) {
                //             this.getLogger().at(Level.SEVERE).log("[HC_Factions] Error opening faction UI: " + e.getMessage());
                //             playerRef.sendMessage(Message.raw("[HC_Factions] ERROR: " + e.getMessage()).color(Color.RED));
                //         }
                //     });
                // }, 5, TimeUnit.SECONDS);
            } else {
                String factionId = playerData.getFactionId();
                var faction = factionManager.getFaction(factionId);
                String factionName = faction != null ? faction.getDisplayName() : factionId;

                // Check guild membership
                String guildInfo = "";
                if (playerData.getGuildId() != null) {
                    var guild = guildRepository.getGuild(playerData.getGuildId());
                    if (guild != null) {
                        guildInfo = " of " + guild.getName();
                    }
                }

                playerRef.sendMessage(Message.raw("[HC_Factions] Welcome back, " + factionName + guildInfo + "!").color(Color.GREEN));
                playerRef.sendMessage(Message.raw("[HC_Factions] Use /guild for guild commands.").color(Color.GRAY));

                // Check for claim decay notifications (if player is in a guild)
                if (playerData.getGuildId() != null && config.isClaimDecayEnabled()) {
                    try {
                        long lastOnline = playerData.getUpdatedAt();
                        int decayedCount = claimDecayManager.getDecayedClaimsSince(playerData.getGuildId(), lastOnline);
                        if (decayedCount > 0) {
                            playerRef.sendMessage(Message.raw(
                                "[HC_Factions] WARNING: Your guild lost " + decayedCount +
                                " claim(s) due to inactivity!").color(Color.RED));
                            playerRef.sendMessage(Message.raw(
                                "[HC_Factions] Log in regularly to prevent further decay.").color(Color.ORANGE));
                        }
                    } catch (Exception e) {
                        this.getLogger().at(Level.WARNING).log("[HC_Factions] Error checking decay notification: " + e.getMessage());
                    }
                }

                // Nameplate already updated unconditionally above
            }
        });

        // ═══════════════════════════════════════════════════════
        // PLAYER DISCONNECT EVENT
        // ═══════════════════════════════════════════════════════
        this.getEventRegistry().register(PlayerDisconnectEvent.class, (event) -> {
            PlayerRef playerRef = event.getPlayerRef();
            this.getLogger().at(Level.INFO).log("[HC_Factions] Player disconnected: " + playerRef.getUsername());

            // Clear any caches if needed
            guildManager.invalidateCache(playerRef.getUuid());

            // Remove territory HUD reference
            removeTerritoryHud(playerRef.getUuid());

            // Clean up chunk enter tracking (highway speed boost, last chunk)
            if (chunkEnterNotifySystem != null) {
                chunkEnterNotifySystem.removePlayer(playerRef.getUuid());
            }

            // ── Combat log detection ──
            // PlayerDisconnectEvent fires during world teleport transitions (false disconnect).
            // We snapshot the player's UUID now, then check after a grace period.
            // If the player is back online, it was a teleport -- skip penalty.
            if (combatTagManager != null && config.isCombatLogoutPenaltyEnabled()) {
                final UUID disconnectedUuid = playerRef.getUuid();
                final String disconnectedName = playerRef.getUsername();

                // Only proceed if the player was combat-tagged (check in-memory tracker)
                if (combatTagManager.wasRecentlyTagged(disconnectedUuid)) {
                    HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
                        try {
                            // Re-check: if the player reconnected, this was a false disconnect
                            // during a world teleport transition.
                            PlayerRef recheck = Universe.get().getPlayer(disconnectedUuid);
                            if (recheck != null) {
                                // Player is back online -- false disconnect, skip penalty
                                return;
                            }

                            // Player is truly offline and was combat-tagged -- combat log!
                            String logoutMsg = config.getCombatTaggedLogoutMessage();
                            this.getLogger().at(Level.WARNING).log("[CombatTag] " + disconnectedName + " " + logoutMsg);

                            // Broadcast to all online players
                            Message broadcast = Message.raw("[Combat] " + disconnectedName + " " + logoutMsg).color(Color.YELLOW);
                            for (PlayerRef online : Universe.get().getPlayers()) {
                                online.sendMessage(broadcast);
                            }

                            // Clean up the in-memory tracker entry
                            combatTagManager.clearRecentTag(disconnectedUuid);
                        } catch (Exception e) {
                            this.getLogger().at(Level.WARNING).log("[CombatTag] Error processing combat log check: " + e.getMessage());
                        }
                    }, 3, TimeUnit.SECONDS);
                }
            }
        });

        // ═══════════════════════════════════════════════════════
        // TERRITORY HUD (shown when player is ready)
        // ═══════════════════════════════════════════════════════
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, (event) -> {
            Player player = event.getPlayer();
            if (player == null) return;

            PlayerRef playerRef = player.getPlayerRef();
            if (playerRef == null) return;

            // Skip HUD for arena worlds
            World world = player.getWorld();
            if (world != null && isArenaWorld(world.getName())) return;

            TerritoryHud hud = new TerritoryHud(playerRef);
            if (HudWrapper.setCustomHud(player, playerRef, "HCFactionTerritory", hud)) {
                territoryHuds.put(playerRef.getUuid(), hud);

                // Check if player is already in claimed territory and update HUD immediately
                if (world != null && claimManager != null) {
                    try {
                        var position = playerRef.getTransform().getPosition();
                        int chunkX = ClaimManager.toChunkCoord(position.getX());
                        int chunkZ = ClaimManager.toChunkCoord(position.getZ());
                        var claim = claimManager.getClaim(world.getName(), chunkX, chunkZ);
                        if (claim != null) {
                            var faction = factionManager.getFaction(claim.getFactionId());
                            if (faction != null) {
                                String colorHex = faction.getColorHex();
                                if (claim.getGuildId() != null) {
                                    var guild = guildRepository.getGuild(claim.getGuildId());
                                    if (guild != null) {
                                        hud.updateTerritory(guild.getName() + " [" + faction.getDisplayName() + "]", colorHex, true);
                                    }
                                } else if (claim.isFactionClaim()) {
                                    hud.updateTerritory("Protected Territory", colorHex, true);
                                }
                            }
                        }
                    } catch (Exception e) {
                        this.getLogger().at(Level.WARNING).log("[HC_Factions] Error checking initial territory for HUD: " + e.getMessage());
                    }
                }
            }
        });

        // ═══════════════════════════════════════════════════════
        // WORLD MAP INITIALIZATION (delayed until universe is ready)
        // ═══════════════════════════════════════════════════════
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            ClaimMapManager.getInstance().initialize();
        }, 5, TimeUnit.SECONDS);

        // ═══════════════════════════════════════════════════════
        // SPAWN SUPPRESSION INITIALIZATION (delayed until worlds are ready)
        // ═══════════════════════════════════════════════════════
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            // Load all claims into memory so getClaim() never hits the DB during gameplay
            claimManager.warmCache();
            guildChunkAccessManager.warmCache();
            guildChunkRoleAccessManager.warmCache();

            // Compute perimeter reservations from cached claims
            claimManager.computeAllReservations();

            if (config.isSpawnSuppressionEnabled()) {
                this.getLogger().at(Level.INFO).log("Initializing spawn suppressors for existing claims...");
                claimManager.initializeSpawnSuppressors();
            } else {
                this.getLogger().at(Level.INFO).log("Spawn suppression is DISABLED - mobs will spawn in claimed areas");
            }
        }, 10, TimeUnit.SECONDS);

        // ═══════════════════════════════════════════════════════
        // CLAIM DECAY SCHEDULED TASK (daily cycle)
        // ═══════════════════════════════════════════════════════
        if (config.isClaimDecayEnabled()) {
            // Run first cycle 60 seconds after startup, then every 24 hours
            HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(() -> {
                try {
                    if (config.isClaimDecayEnabled()) {
                        claimDecayManager.runDecayCycle();
                    }
                } catch (Exception e) {
                    this.getLogger().at(Level.SEVERE).log("[HC_Factions] Claim decay cycle failed: " + e.getMessage());
                }
            }, 60, TimeUnit.DAYS.toSeconds(1), TimeUnit.SECONDS);
            this.getLogger().at(Level.INFO).log("Claim decay scheduled (every 24 hours, threshold="
                    + config.getClaimDecayDaysInactive() + " days, rate="
                    + config.getClaimDecayClaimsPerDay() + " claims/day)");
        } else {
            this.getLogger().at(Level.INFO).log("Claim decay is DISABLED");
        }

        this.getLogger().at(Level.INFO).log("HC_Factions enabled successfully!");
        this.getLogger().at(Level.INFO).log("=================================");
    }

    @Override
    protected void shutdown() {
        super.shutdown();

        // Shut down guild log manager
        if (guildLogManager != null) {
            guildLogManager.shutdown();
        }

        // Close database connection
        if (databaseManager != null) {
            databaseManager.close();
        }

        instance = null;
        this.getLogger().at(Level.INFO).log("HC_Factions disabled");
    }
}
