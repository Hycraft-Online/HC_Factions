package com.hcfactions.managers;

import com.hcfactions.database.repositories.GuildChunkRoleAccessRepository;
import com.hcfactions.models.GuildChunkRoleAccess;
import com.hcfactions.models.GuildRole;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Manages per-chunk role-based access requirements for guild-owned land.
 */
public class GuildChunkRoleAccessManager {

    private static final HytaleLogger LOGGER = HytaleLogger.getLogger().getSubLogger("FactionGuilds-GuildChunkRoleAccess");

    private final GuildChunkRoleAccessRepository repository;
    private final Map<String, GuildChunkRoleAccess> cache = new ConcurrentHashMap<>();
    private volatile boolean cacheWarmed = false;

    public GuildChunkRoleAccessManager(GuildChunkRoleAccessRepository repository) {
        this.repository = repository;
    }

    public void warmCache() {
        cache.clear();
        List<GuildChunkRoleAccess> all = repository.getAll();
        for (GuildChunkRoleAccess access : all) {
            cache.put(access.getKey(), access);
        }
        cacheWarmed = true;
        LOGGER.at(Level.INFO).log("Guild chunk role access cache warmed: %d rows", all.size());
    }

    public void clearCache() {
        cache.clear();
        cacheWarmed = false;
    }

    @Nullable
    public GuildChunkRoleAccess getAccess(UUID guildId, String world, int chunkX, int chunkZ) {
        String key = GuildChunkRoleAccess.createKey(guildId, world, chunkX, chunkZ);
        GuildChunkRoleAccess cached = cache.get(key);
        if (cached != null || cacheWarmed) {
            return cached;
        }

        GuildChunkRoleAccess db = repository.get(guildId, world, chunkX, chunkZ);
        if (db != null) {
            cache.put(key, db);
        }
        return db;
    }

    /**
     * Sets all 9 role requirements for a chunk at once.
     * If all roles are null, deletes the row.
     */
    public void setRoleRequirements(UUID guildId, String world, int chunkX, int chunkZ,
                                    GuildChunkRoleAccess access) {
        upsertOrDelete(guildId, world, chunkX, chunkZ, access);
    }

    /**
     * Backward-compat overload for code that still sets edit/chest roles.
     * Maps edit → break+place, chest → all interaction columns.
     */
    public void setRoleRequirements(UUID guildId, String world, int chunkX, int chunkZ,
                                    @Nullable GuildRole minEditRole, @Nullable GuildRole minChestRole,
                                    @Nullable UUID updatedBy) {
        GuildChunkRoleAccess access = new GuildChunkRoleAccess(
            guildId, world, chunkX, chunkZ,
            minEditRole, minEditRole,       // break, place
            minChestRole, minChestRole,     // interact, doors
            minChestRole, minChestRole,     // chests, benches
            minChestRole, minChestRole,     // processing, seats
            minChestRole,                   // transport
            updatedBy, System.currentTimeMillis()
        );
        upsertOrDelete(guildId, world, chunkX, chunkZ, access);
    }

    public void removeAccessForChunk(UUID guildId, String world, int chunkX, int chunkZ) {
        repository.deleteForChunk(guildId, world, chunkX, chunkZ);
        cache.remove(GuildChunkRoleAccess.createKey(guildId, world, chunkX, chunkZ));
    }

    public void removeAccessForGuild(UUID guildId) {
        repository.deleteForGuild(guildId);
        cache.entrySet().removeIf(entry -> entry.getValue().getGuildId().equals(guildId));
    }

    public boolean roleMeetsRequirement(@Nullable GuildRole playerRole, @Nullable GuildRole minRole) {
        return playerRole != null && minRole != null && playerRole.hasAtLeast(minRole);
    }

    /**
     * Returns the minimum role required for a specific AccessAction on a chunk.
     * For interaction subtypes, falls back to minInteractRole if the specific role is null.
     */
    @Nullable
    public GuildRole getMinRoleForAction(GuildChunkRoleAccess access,
                                          GuildChunkAccessManager.AccessAction action) {
        GuildRole specific = switch (action) {
            case BREAK -> access.getMinBreakRole();
            case PLACE -> access.getMinPlaceRole();
            case INTERACT -> access.getMinInteractRole();
            case INTERACT_DOORS -> access.getMinDoorsRole();
            case INTERACT_CHESTS -> access.getMinChestsRole();
            case INTERACT_BENCHES -> access.getMinBenchesRole();
            case INTERACT_PROCESSING -> access.getMinProcessingRole();
            case INTERACT_SEATS -> access.getMinSeatsRole();
            case INTERACT_TRANSPORT -> access.getMinTransportRole();
            case HARVEST, PICKUP -> access.getMinInteractRole();
        };

        // For interaction subtypes, fall back to minInteractRole if specific is null
        if (specific == null && isInteractionSubtype(action)) {
            return access.getMinInteractRole();
        }

        return specific;
    }

    private boolean isInteractionSubtype(GuildChunkAccessManager.AccessAction action) {
        return action == GuildChunkAccessManager.AccessAction.INTERACT_DOORS
            || action == GuildChunkAccessManager.AccessAction.INTERACT_CHESTS
            || action == GuildChunkAccessManager.AccessAction.INTERACT_BENCHES
            || action == GuildChunkAccessManager.AccessAction.INTERACT_PROCESSING
            || action == GuildChunkAccessManager.AccessAction.INTERACT_SEATS
            || action == GuildChunkAccessManager.AccessAction.INTERACT_TRANSPORT;
    }

    private void upsertOrDelete(UUID guildId, String world, int chunkX, int chunkZ,
                                GuildChunkRoleAccess access) {
        String key = GuildChunkRoleAccess.createKey(guildId, world, chunkX, chunkZ);

        if (!access.hasAnyCustomPermission()) {
            repository.delete(guildId, world, chunkX, chunkZ);
            cache.remove(key);
            return;
        }

        repository.upsert(access);
        cache.put(key, access);
    }
}
