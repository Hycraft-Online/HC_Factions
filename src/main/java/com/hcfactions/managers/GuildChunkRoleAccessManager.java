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

    public enum PermissionType {
        EDIT,
        CHEST,
        BOTH
    }

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

    public void setRoleRequirement(UUID guildId, String world, int chunkX, int chunkZ,
                                   PermissionType permissionType, @Nullable GuildRole role,
                                   @Nullable UUID updatedBy) {
        GuildChunkRoleAccess current = getAccess(guildId, world, chunkX, chunkZ);
        GuildRole minEditRole = current != null ? current.getMinEditRole() : null;
        GuildRole minChestRole = current != null ? current.getMinChestRole() : null;

        switch (permissionType) {
            case EDIT -> minEditRole = role;
            case CHEST -> minChestRole = role;
            case BOTH -> {
                minEditRole = role;
                minChestRole = role;
            }
        }

        upsertOrDelete(guildId, world, chunkX, chunkZ, minEditRole, minChestRole, updatedBy);
    }

    public void setRoleRequirements(UUID guildId, String world, int chunkX, int chunkZ,
                                    @Nullable GuildRole minEditRole, @Nullable GuildRole minChestRole,
                                    @Nullable UUID updatedBy) {
        upsertOrDelete(guildId, world, chunkX, chunkZ, minEditRole, minChestRole, updatedBy);
    }

    public void clearRoleRequirement(UUID guildId, String world, int chunkX, int chunkZ,
                                     PermissionType permissionType, @Nullable UUID updatedBy) {
        setRoleRequirement(guildId, world, chunkX, chunkZ, permissionType, null, updatedBy);
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

    private void upsertOrDelete(UUID guildId, String world, int chunkX, int chunkZ,
                                @Nullable GuildRole minEditRole, @Nullable GuildRole minChestRole,
                                @Nullable UUID updatedBy) {
        String key = GuildChunkRoleAccess.createKey(guildId, world, chunkX, chunkZ);

        if (minEditRole == null && minChestRole == null) {
            repository.delete(guildId, world, chunkX, chunkZ);
            cache.remove(key);
            return;
        }

        GuildChunkRoleAccess updated = new GuildChunkRoleAccess(
            guildId,
            world,
            chunkX,
            chunkZ,
            minEditRole,
            minChestRole,
            updatedBy,
            System.currentTimeMillis()
        );

        repository.upsert(updated);
        cache.put(key, updated);
    }
}
