package com.hcfactions.managers;

import com.hcfactions.HC_FactionsPlugin;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Manages block interaction protection in claims using two lists:
 * 
 * 1. BLOCKED patterns (blacklist) - Blocks/items that ARE protected in claims
 *    Examples: grass, flower, plant, mushroom, crop
 *    These can only be harvested by claim owners.
 * 
 * 2. ALLOWED patterns (whitelist) - Blocks that are ALWAYS interactable
 *    Examples: portal, quest, teleport, npc, vendor
 *    These bypass protection entirely.
 * 
 * Logic: If block matches whitelist -> ALLOW
 *        If block matches blacklist -> PROTECT (check claim ownership)
 *        Otherwise -> ALLOW (default)
 */
public class PickupBlacklistManager {
    
    private static final String MOD_FOLDER = "mods/.hc_config/HC_Factions";
    private static final String BLOCKED_FILE = "interaction_blocked.json";
    private static final String ALLOWED_FILE = "interaction_allowed.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    private final HC_FactionsPlugin plugin;
    private final Path blockedPath;
    private final Path allowedPath;
    private final Set<String> blockedPatterns = ConcurrentHashMap.newKeySet();
    private final Set<String> allowedPatterns = ConcurrentHashMap.newKeySet();
    
    // Default BLOCKED patterns - things that should be protected in claims
    // Guild claims: only the owning guild can interact
    // Faction claims: nobody can interact (except admins)
    private static final List<String> DEFAULT_BLOCKED = Arrays.asList(
        // Plants/Grass
        "plant_grass", "grass_sharp", "grass_tall", "grass_short",
        // Flowers
        "flower", "tulip", "rose", "daisy", "poppy", "dandelion",
        "lavender", "orchid", "lily", "sunflower", "marigold", "peony",
        "allium", "cornflower", "bluebell",
        // Mushrooms
        "mushroom", "fungus",
        // Foliage
        "fern", "seagrass", "seaweed", "kelp", "vine", "moss", "lichen", "clover",
        // Plants
        "fruit", "bush", "bramble", "cactus", "coral", "petal", "reed",
        "sapling", "hay",
        // Crops
        "crop", "wheat", "carrot", "potato", "berry",
        // Containers
        "chest", "barrel", "crate", "storage",
        // Doors/Gates
        "door", "gate",
        // Crafting stations
        "bench", "anvil", "furnace", "forge", "loom", "workbench", "crafting",
        // Furniture
        "bed", "chair", "stool",
        // Portals and teleporters
        "portal", "teleport", "waypoint", "warp",
        // Switches/Mechanisms
        "lever", "button", "switch",
        // Quest/NPC interactions (protected in guild claims)
        "quest", "request_board", "notice_board", "bulletin",
        // Misc
        "gravestone", "sign"
    );
    
    // Default ALLOWED patterns - things that should ALWAYS be interactable (bypass protection)
    // Only shops/vendors - everything else is protected in guild claims
    private static final List<String> DEFAULT_ALLOWED = Arrays.asList(
        // Only shops/vendors bypass protection
        "vendor", "merchant", "shopkeeper", "trader", "shop", "barter"
    );
    
    public PickupBlacklistManager(HC_FactionsPlugin plugin) {
        this.plugin = plugin;
        this.blockedPath = Path.of(MOD_FOLDER, BLOCKED_FILE);
        this.allowedPath = Path.of(MOD_FOLDER, ALLOWED_FILE);
        loadBlacklist();
        loadWhitelist();
    }
    
    /**
     * Load the blocked patterns (blacklist) from the JSON file.
     */
    public void loadBlacklist() {
        blockedPatterns.clear();
        
        if (Files.exists(blockedPath)) {
            try (Reader reader = Files.newBufferedReader(blockedPath)) {
                Type listType = new TypeToken<List<String>>(){}.getType();
                List<String> patterns = GSON.fromJson(reader, listType);
                if (patterns != null) {
                    for (String pattern : patterns) {
                        blockedPatterns.add(pattern.toLowerCase());
                    }
                }
                plugin.getLogger().at(Level.INFO).log(
                    "[InteractionProtection] Loaded %d blocked patterns from file", blockedPatterns.size());
                ensureBlockedDefaults();
            } catch (Exception e) {
                plugin.getLogger().at(Level.SEVERE).withCause(e).log(
                    "[InteractionProtection] Failed to load blocked file, using defaults");
                loadBlockedDefaults();
            }
        } else {
            loadBlockedDefaults();
            saveBlacklist();
            plugin.getLogger().at(Level.INFO).log(
                "[InteractionProtection] Created default blocked list with %d patterns", blockedPatterns.size());
        }
    }
    
    /**
     * Load the allowed patterns (whitelist) from the JSON file.
     */
    public void loadWhitelist() {
        allowedPatterns.clear();
        
        if (Files.exists(allowedPath)) {
            try (Reader reader = Files.newBufferedReader(allowedPath)) {
                Type listType = new TypeToken<List<String>>(){}.getType();
                List<String> patterns = GSON.fromJson(reader, listType);
                if (patterns != null) {
                    for (String pattern : patterns) {
                        allowedPatterns.add(pattern.toLowerCase());
                    }
                }
                plugin.getLogger().at(Level.INFO).log(
                    "[InteractionProtection] Loaded %d allowed patterns from file", allowedPatterns.size());
            } catch (Exception e) {
                plugin.getLogger().at(Level.SEVERE).withCause(e).log(
                    "[InteractionProtection] Failed to load allowed file, using defaults");
                loadAllowedDefaults();
            }
        } else {
            loadAllowedDefaults();
            saveWhitelist();
            plugin.getLogger().at(Level.INFO).log(
                "[InteractionProtection] Created default allowed list with %d patterns", allowedPatterns.size());
        }
    }
    
    private void loadBlockedDefaults() {
        for (String pattern : DEFAULT_BLOCKED) {
            blockedPatterns.add(pattern.toLowerCase());
        }
    }

    /**
     * Ensure all default blocked patterns are present (backfills new defaults into existing configs).
     */
    private void ensureBlockedDefaults() {
        boolean changed = false;
        for (String pattern : DEFAULT_BLOCKED) {
            if (blockedPatterns.add(pattern.toLowerCase())) {
                changed = true;
            }
        }
        if (changed) {
            saveBlacklist();
            plugin.getLogger().at(Level.INFO).log(
                "[InteractionProtection] Added missing default blocked patterns (now %d total)", blockedPatterns.size());
        }
    }
    
    private void loadAllowedDefaults() {
        for (String pattern : DEFAULT_ALLOWED) {
            allowedPatterns.add(pattern.toLowerCase());
        }
    }
    
    /**
     * Save the blocked patterns to JSON file.
     */
    public void saveBlacklist() {
        savePatterns(blockedPath, blockedPatterns, "blocked");
    }
    
    /**
     * Save the allowed patterns to JSON file.
     */
    public void saveWhitelist() {
        savePatterns(allowedPath, allowedPatterns, "allowed");
    }
    
    private void savePatterns(Path path, Set<String> patterns, String type) {
        try {
            Files.createDirectories(path.getParent());
            List<String> sortedPatterns = new ArrayList<>(patterns);
            Collections.sort(sortedPatterns);
            
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(sortedPatterns, writer);
            }
            plugin.getLogger().at(Level.INFO).log(
                "[InteractionProtection] Saved %d %s patterns to file", patterns.size(), type);
        } catch (Exception e) {
            plugin.getLogger().at(Level.SEVERE).withCause(e).log(
                "[InteractionProtection] Failed to save %s file", type);
        }
    }
    
    // ═══════════════════════════════════════════════════════
    // BLOCKED PATTERNS (Blacklist) - Protected in claims
    // ═══════════════════════════════════════════════════════
    
    /**
     * Add a pattern to the blocked list.
     */
    public boolean addBlockedPattern(String pattern) {
        String lowerPattern = pattern.toLowerCase().trim();
        if (lowerPattern.isEmpty()) return false;
        boolean added = blockedPatterns.add(lowerPattern);
        if (added) saveBlacklist();
        return added;
    }
    
    /**
     * Remove a pattern from the blocked list.
     */
    public boolean removeBlockedPattern(String pattern) {
        String lowerPattern = pattern.toLowerCase().trim();
        boolean removed = blockedPatterns.remove(lowerPattern);
        if (removed) saveBlacklist();
        return removed;
    }
    
    /**
     * Check if a block/item ID matches any blocked pattern.
     */
    public boolean isBlacklisted(String id) {
        if (id == null || id.isEmpty()) return false;
        String lowerId = id.toLowerCase();
        for (String pattern : blockedPatterns) {
            if (lowerId.contains(pattern)) return true;
        }
        return false;
    }
    
    public Set<String> getBlockedPatterns() {
        return Collections.unmodifiableSet(new TreeSet<>(blockedPatterns));
    }
    
    public int getBlockedCount() {
        return blockedPatterns.size();
    }
    
    // Backwards compatibility
    public boolean addPattern(String pattern) {
        return addBlockedPattern(pattern);
    }
    
    public boolean removePattern(String pattern) {
        return removeBlockedPattern(pattern);
    }
    
    public int getPatternCount() {
        return blockedPatterns.size();
    }
    
    // ═══════════════════════════════════════════════════════
    // ALLOWED PATTERNS (Whitelist) - Always interactable
    // ═══════════════════════════════════════════════════════
    
    /**
     * Add a pattern to the allowed list.
     */
    public boolean addAllowedPattern(String pattern) {
        String lowerPattern = pattern.toLowerCase().trim();
        if (lowerPattern.isEmpty()) return false;
        boolean added = allowedPatterns.add(lowerPattern);
        if (added) saveWhitelist();
        return added;
    }
    
    /**
     * Remove a pattern from the allowed list.
     */
    public boolean removeAllowedPattern(String pattern) {
        String lowerPattern = pattern.toLowerCase().trim();
        boolean removed = allowedPatterns.remove(lowerPattern);
        if (removed) saveWhitelist();
        return removed;
    }
    
    /**
     * Check if a block/item ID matches any allowed pattern.
     * These blocks bypass protection entirely.
     */
    public boolean isWhitelisted(String id) {
        if (id == null || id.isEmpty()) return false;
        String lowerId = id.toLowerCase();
        for (String pattern : allowedPatterns) {
            if (lowerId.contains(pattern)) return true;
        }
        return false;
    }
    
    public Set<String> getAllowedPatterns() {
        return Collections.unmodifiableSet(new TreeSet<>(allowedPatterns));
    }
    
    public int getAllowedCount() {
        return allowedPatterns.size();
    }
    
    // ═══════════════════════════════════════════════════════
    // COMBINED LOGIC
    // ═══════════════════════════════════════════════════════
    
    /**
     * Determine if a block interaction should be protected.
     * 
     * @param blockId The block type ID
     * @return true if the block should be protected (check claim ownership),
     *         false if it should be allowed regardless of claims
     */
    public boolean shouldProtectBlock(String blockId) {
        // Whitelist takes priority - always allow these
        if (isWhitelisted(blockId)) {
            return false;
        }
        // If blacklisted, protect it
        if (isBlacklisted(blockId)) {
            return true;
        }
        // Default: don't protect (allow interaction)
        return false;
    }
    
    // ═══════════════════════════════════════════════════════
    // RESET/RELOAD
    // ═══════════════════════════════════════════════════════
    
    public void resetToDefaults() {
        blockedPatterns.clear();
        allowedPatterns.clear();
        loadBlockedDefaults();
        loadAllowedDefaults();
        saveBlacklist();
        saveWhitelist();
    }
    
    public void reload() {
        loadBlacklist();
        loadWhitelist();
    }
}
