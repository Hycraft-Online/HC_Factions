package com.hcfactions.managers;

import com.hcfactions.database.repositories.GuildChunkAccessRepository;
import com.hcfactions.models.Claim;
import com.hcfactions.models.GuildChunkAccess;
import com.hcfactions.models.GuildChunkRoleAccess;
import com.hcfactions.models.GuildRole;
import com.hcfactions.models.PlayerData;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Manages per-member access grants for specific guild claim chunks.
 */
public class GuildChunkAccessManager {

    public enum AccessAction {
        PLACE,
        BREAK,
        INTERACT,
        HARVEST,
        PICKUP
    }

    private static final HytaleLogger LOGGER = HytaleLogger.getLogger().getSubLogger("FactionGuilds-GuildChunkAccess");

    private final GuildChunkAccessRepository repository;
    private final GuildChunkRoleAccessManager roleAccessManager;
    private final Map<String, GuildChunkAccess> cache = new ConcurrentHashMap<>();
    private volatile boolean cacheWarmed = false;

    public GuildChunkAccessManager(GuildChunkAccessRepository repository,
                                   GuildChunkRoleAccessManager roleAccessManager) {
        this.repository = repository;
        this.roleAccessManager = roleAccessManager;
    }

    public void warmCache() {
        cache.clear();
        List<GuildChunkAccess> all = repository.getAll();
        for (GuildChunkAccess access : all) {
            cache.put(access.getKey(), access);
        }
        cacheWarmed = true;
        LOGGER.at(Level.INFO).log("Guild chunk access cache warmed: %d grants", all.size());
    }

    public void clearCache() {
        cache.clear();
        cacheWarmed = false;
    }

    public void assign(UUID guildId, UUID memberUuid, String world, int chunkX, int chunkZ,
                       boolean canEdit, boolean canChest, UUID grantedBy) {
        GuildChunkAccess access = new GuildChunkAccess(
            guildId,
            memberUuid,
            world,
            chunkX,
            chunkZ,
            canEdit,
            canChest,
            grantedBy,
            System.currentTimeMillis()
        );

        repository.upsert(access);
        cache.put(access.getKey(), access);
    }

    public void unassign(UUID guildId, UUID memberUuid, String world, int chunkX, int chunkZ) {
        repository.delete(guildId, memberUuid, world, chunkX, chunkZ);
        cache.remove(GuildChunkAccess.createKey(guildId, memberUuid, world, chunkX, chunkZ));
    }

    public void removeAssignmentsForChunk(UUID guildId, String world, int chunkX, int chunkZ) {
        repository.deleteForChunk(guildId, world, chunkX, chunkZ);
        cache.entrySet().removeIf(entry -> {
            GuildChunkAccess access = entry.getValue();
            return access.getGuildId().equals(guildId)
                && access.getWorld().equals(world)
                && access.getChunkX() == chunkX
                && access.getChunkZ() == chunkZ;
        });
    }

    public void removeAssignmentsForGuild(UUID guildId) {
        repository.deleteForGuild(guildId);
        cache.entrySet().removeIf(entry -> entry.getValue().getGuildId().equals(guildId));
    }

    public void removeAssignmentsForMember(UUID guildId, UUID memberUuid) {
        repository.deleteForMember(guildId, memberUuid);
        cache.entrySet().removeIf(entry -> {
            GuildChunkAccess access = entry.getValue();
            return access.getGuildId().equals(guildId) && access.getMemberUuid().equals(memberUuid);
        });
    }

    @Nullable
    public GuildChunkAccess getAccess(UUID guildId, UUID memberUuid, String world, int chunkX, int chunkZ) {
        String key = GuildChunkAccess.createKey(guildId, memberUuid, world, chunkX, chunkZ);
        GuildChunkAccess cached = cache.get(key);
        if (cached != null || cacheWarmed) {
            return cached;
        }

        GuildChunkAccess db = repository.get(guildId, memberUuid, world, chunkX, chunkZ);
        if (db != null) {
            cache.put(key, db);
        }
        return db;
    }

    public List<GuildChunkAccess> getMemberAssignments(UUID guildId, UUID memberUuid) {
        return repository.getMemberAssignments(guildId, memberUuid);
    }

    public boolean canAccessGuildClaim(@Nullable PlayerData playerData, Claim claim,
                                       AccessAction action, @Nullable String blockId) {
        if (playerData == null || !playerData.isInGuild() || claim.isFactionClaim() || claim.isSoloPlayerClaim()) {
            return false;
        }

        UUID playerGuildId = playerData.getGuildId();
        if (playerGuildId == null || claim.getGuildId() == null || !playerGuildId.equals(claim.getGuildId())) {
            return false;
        }

        GuildRole role = playerData.getGuildRole();
        if (role != null && role.hasAtLeast(GuildRole.OFFICER)) {
            return true;
        }

        UUID playerUuid = playerData.getPlayerUuid();
        GuildChunkAccess access = getAccess(claim.getGuildId(), playerUuid,
            claim.getWorld(), claim.getChunkX(), claim.getChunkZ());

        // Explicit member assignment overrides role defaults.
        if (access != null) {
            if (action == AccessAction.INTERACT) {
                // Container blocks (chests, barrels, etc.) require canChest.
                // Non-container interactions (doors, beds, workbenches) allow either
                // canChest or canEdit -- canChest is the lower permission tier.
                return access.canChest() || access.canEdit();
            }

            return access.canEdit();
        }

        GuildChunkRoleAccess roleAccess = roleAccessManager.getAccess(
            claim.getGuildId(), claim.getWorld(), claim.getChunkX(), claim.getChunkZ()
        );
        if (roleAccess == null) {
            return false;
        }

        if (action == AccessAction.INTERACT) {
            // Same logic for role-based access: any interaction (container or not)
            // is allowed if the player meets either the chest or edit role requirement.
            return roleAccessManager.roleMeetsRequirement(role, roleAccess.getMinChestRole())
                || roleAccessManager.roleMeetsRequirement(role, roleAccess.getMinEditRole());
        }

        return roleAccessManager.roleMeetsRequirement(role, roleAccess.getMinEditRole());
    }

    private boolean isContainerBlock(@Nullable String blockId) {
        if (blockId == null) {
            return false;
        }

        String lower = blockId.toLowerCase();
        return lower.contains("chest")
            || lower.contains("barrel")
            || lower.contains("crate")
            || lower.contains("storage");
    }
}
