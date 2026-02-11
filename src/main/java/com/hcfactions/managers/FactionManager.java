package com.hcfactions.managers;

import com.hcfactions.HC_FactionsPlugin;
import com.hcfactions.database.repositories.FactionRepository;
import com.hcfactions.models.Faction;
import com.hypixel.hytale.logger.HytaleLogger;

import java.util.*;
import java.util.logging.Level;

/**
 * Manages the admin-defined major factions.
 * Factions are loaded from the database at startup.
 */
public class FactionManager {

    private static final HytaleLogger LOGGER = HytaleLogger.getLogger().getSubLogger("FactionGuilds-Factions");

    private final HC_FactionsPlugin plugin;
    private final FactionRepository factionRepository;
    private final Map<String, Faction> factions = new HashMap<>();

    public FactionManager(HC_FactionsPlugin plugin) {
        this.plugin = plugin;
        this.factionRepository = plugin.getFactionRepository();
        loadFactions();
    }

    /**
     * Loads factions from the database.
     */
    private void loadFactions() {
        factions.clear();
        
        List<Faction> dbFactions = factionRepository.getAllFactions();
        
        for (Faction faction : dbFactions) {
            factions.put(faction.getId(), faction);
            LOGGER.at(Level.INFO).log("Loaded faction: " + faction.getDisplayName() + " (" + faction.getId() + ")");
        }

        LOGGER.at(Level.INFO).log("Loaded " + factions.size() + " factions from database");
    }

    /**
     * Reloads factions from the database.
     * Can be called at runtime to pick up changes.
     */
    public void reloadFactions() {
        LOGGER.at(Level.INFO).log("Reloading factions from database...");
        loadFactions();
    }

    /**
     * Creates a new faction.
     * 
     * @param faction The faction to create
     * @return true if created successfully
     */
    public boolean createFaction(Faction faction) {
        if (factions.containsKey(faction.getId())) {
            return false; // Already exists
        }

        try {
            factionRepository.createFaction(faction);
            factions.put(faction.getId(), faction);
            return true;
        } catch (Exception e) {
            LOGGER.at(Level.SEVERE).log("Failed to create faction: " + e.getMessage());
            return false;
        }
    }

    /**
     * Updates an existing faction.
     * 
     * @param faction The faction to update
     * @return true if updated successfully
     */
    public boolean updateFaction(Faction faction) {
        if (!factions.containsKey(faction.getId())) {
            return false; // Doesn't exist
        }

        factionRepository.updateFaction(faction);
        factions.put(faction.getId(), faction);
        return true;
    }

    /**
     * Deletes a faction.
     * WARNING: This does not handle players/guilds in this faction.
     * 
     * @param factionId The faction ID to delete
     * @return true if deleted successfully
     */
    public boolean deleteFaction(String factionId) {
        if (!factions.containsKey(factionId)) {
            return false; // Doesn't exist
        }

        if (factionRepository.deleteFaction(factionId)) {
            factions.remove(factionId);
            return true;
        }
        return false;
    }

    /**
     * Gets a faction by ID.
     */
    public Faction getFaction(String id) {
        return factions.get(id);
    }

    /**
     * Gets all factions.
     */
    public Collection<Faction> getFactions() {
        return Collections.unmodifiableCollection(factions.values());
    }

    /**
     * Gets all faction IDs.
     */
    public Set<String> getFactionIds() {
        return Collections.unmodifiableSet(factions.keySet());
    }

    /**
     * Checks if two factions are enemies.
     */
    public boolean areEnemies(String factionId1, String factionId2) {
        if (factionId1 == null || factionId2 == null) {
            return false;
        }
        // In a two-faction system, different factions are always enemies
        return !factionId1.equals(factionId2);
    }

    /**
     * Checks if a faction ID is valid.
     */
    public boolean isValidFaction(String factionId) {
        return factions.containsKey(factionId);
    }
}
