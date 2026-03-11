package com.hcfactions.managers;

import com.hcfactions.HC_FactionsPlugin;
import com.hcfactions.database.repositories.GuildRepository;
import com.hcfactions.database.repositories.PlayerDataRepository;
import com.hcfactions.events.GuildJoinEvent;
import com.hcfactions.events.GuildLeaveEvent;
import com.hcfactions.models.Guild;
import com.hcfactions.models.GuildInvitation;
import com.hcfactions.models.GuildRole;
import com.hcfactions.models.PlayerData;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import java.awt.Color;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import static com.hcfactions.managers.GuildLogType.*;

/**
 * Manages guild operations including creation, membership, and caching.
 */
public class GuildManager {

    private static final HytaleLogger LOGGER = HytaleLogger.getLogger().getSubLogger("FactionGuilds-Guilds");

    private final HC_FactionsPlugin plugin;
    private final GuildRepository guildRepository;
    private final PlayerDataRepository playerDataRepository;

    // Cache for frequently accessed guilds
    private final Map<UUID, Guild> guildCache = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> playerGuildCache = new ConcurrentHashMap<>(); // player -> guild

    public GuildManager(HC_FactionsPlugin plugin) {
        this.plugin = plugin;
        this.guildRepository = plugin.getGuildRepository();
        this.playerDataRepository = plugin.getPlayerDataRepository();
    }

    // ═══════════════════════════════════════════════════════
    // GUILD CRUD
    // ═══════════════════════════════════════════════════════

    /**
     * Creates a new guild without a custom tag.
     *
     * @param name Guild name
     * @param leaderUuid UUID of the guild leader
     * @param factionId Faction the guild belongs to
     * @return The created guild, or null if creation failed
     */
    public Guild createGuild(String name, UUID leaderUuid, String factionId) {
        return createGuild(name, leaderUuid, factionId, null);
    }

    /**
     * Creates a new guild with an optional custom tag.
     *
     * @param name Guild name
     * @param leaderUuid UUID of the guild leader
     * @param factionId Faction the guild belongs to
     * @param tag Optional guild tag (1-4 letters, uppercase)
     * @return The created guild, or null if creation failed
     */
    public Guild createGuild(String name, UUID leaderUuid, String factionId, String tag) {
        // Validate name
        int minNameLength = plugin.getConfig().getGuildMinNameLength();
        int maxNameLength = plugin.getConfig().getGuildMaxNameLength();
        if (name == null || name.length() < minNameLength || name.length() > maxNameLength) {
            LOGGER.at(Level.WARNING).log("Invalid guild name: " + name);
            return null;
        }

        // Check name uniqueness
        if (guildRepository.isNameTaken(name)) {
            LOGGER.at(Level.WARNING).log("Guild name already taken: " + name);
            return null;
        }

        // Check if player already in a guild
        PlayerData playerData = playerDataRepository.getPlayerData(leaderUuid);
        if (playerData != null && playerData.isInGuild()) {
            LOGGER.at(Level.WARNING).log("Player already in a guild: " + leaderUuid);
            return null;
        }

        // Create guild
        UUID guildId = UUID.randomUUID();
        Guild guild = new Guild(guildId, name, factionId, leaderUuid);
        int defaultPower = plugin.getConfig().getGuildDefaultPower();
        guild.setPower(defaultPower);
        guild.setMaxPower(defaultPower);

        // Set tag if provided
        if (tag != null && !tag.isEmpty()) {
            guild.setTag(tag.toUpperCase());
        }

        try {
            guildRepository.createGuild(guild);

            // Add leader as member
            if (playerData == null) {
                playerData = new PlayerData(leaderUuid);
                playerData.setFactionId(factionId);
                playerData.setHasChosenFaction(true);
            }

            // Clear any personal claims before joining guild (same as joinGuild/acceptJoinRequest)
            plugin.getClaimManager().unclaimAllForPlayer(leaderUuid);

            playerData.joinGuild(guildId, GuildRole.LEADER);
            playerDataRepository.savePlayerData(playerData);

            // Clear any pending invitations
            guildRepository.deletePlayerInvitations(leaderUuid);

            // Cache
            guildCache.put(guildId, guild);
            playerGuildCache.put(leaderUuid, guildId);

            // Fire GuildJoinEvent for the leader
            String leaderName = playerData.getPlayerName();
            if (leaderName == null) leaderName = "unknown";
            HytaleServer.get().getEventBus().dispatchFor(GuildJoinEvent.class).dispatch(new GuildJoinEvent(
                leaderUuid,
                leaderName,
                guildId,
                name,
                factionId
            ));

            // Update leader's nameplate to show guild tag
            plugin.getNameplateManager().updateNameplateForPlayer(leaderUuid);

            // Log guild creation
            logEvent(guildId, GUILD_CREATE, leaderUuid, "Created guild '" + name + "'");

            LOGGER.at(Level.INFO).log("Created guild: " + name + " by " + leaderUuid);
            return guild;

        } catch (Exception e) {
            LOGGER.at(Level.SEVERE).log("Failed to create guild: " + e.getMessage());
            return null;
        }
    }

    /**
     * Disbands a guild.
     */
    public boolean disbandGuild(UUID guildId) {
        Guild guild = getGuild(guildId);
        if (guild == null) {
            return false;
        }

        try {
            // Remove all claims
            plugin.getClaimManager().unclaimAllForGuild(guildId);
            plugin.getGuildChunkAccessManager().removeAssignmentsForGuild(guildId);
            plugin.getGuildChunkRoleAccessManager().removeAccessForGuild(guildId);

            // Fire GuildLeaveEvent for all members before removing them
            List<PlayerData> members = playerDataRepository.getGuildMembers(guildId);
            String guildName = guild.getName();
            for (PlayerData member : members) {
                String memberName = member.getPlayerName();
                if (memberName == null) memberName = "unknown";
                HytaleServer.get().getEventBus().dispatchFor(GuildLeaveEvent.class).dispatch(new GuildLeaveEvent(
                    member.getPlayerUuid(),
                    memberName,
                    guildId,
                    guildName,
                    GuildLeaveEvent.Reason.DISBANDED
                ));
                playerGuildCache.remove(member.getPlayerUuid());
                // Update nameplate to remove guild tag
                plugin.getNameplateManager().updateNameplateForPlayer(member.getPlayerUuid());
            }
            playerDataRepository.removeAllGuildMembers(guildId);

            // Delete invitations and join requests
            guildRepository.deleteAllInvitations(guildId);
            guildRepository.deleteAllJoinRequests(guildId);

            // Delete guild
            guildRepository.deleteGuild(guildId);
            guildCache.remove(guildId);

            LOGGER.at(Level.INFO).log("Disbanded guild: " + guild.getName());
            return true;

        } catch (Exception e) {
            LOGGER.at(Level.SEVERE).log("Failed to disband guild: " + e.getMessage());
            return false;
        }
    }

    /**
     * Gets a guild by ID.
     */
    public Guild getGuild(UUID guildId) {
        if (guildId == null) return null;

        Guild cached = guildCache.get(guildId);
        if (cached != null) {
            return cached;
        }

        Guild guild = guildRepository.getGuild(guildId);
        if (guild != null) {
            guildCache.put(guildId, guild);
        }
        return guild;
    }

    /**
     * Gets a guild by name.
     */
    public Guild getGuildByName(String name) {
        return guildRepository.getGuildByName(name);
    }

    /**
     * Gets the guild a player is in.
     */
    public Guild getPlayerGuild(UUID playerUuid) {
        UUID cachedGuildId = playerGuildCache.get(playerUuid);
        if (cachedGuildId != null) {
            return getGuild(cachedGuildId);
        }

        PlayerData playerData = playerDataRepository.getPlayerData(playerUuid);
        if (playerData != null && playerData.getGuildId() != null) {
            playerGuildCache.put(playerUuid, playerData.getGuildId());
            return getGuild(playerData.getGuildId());
        }
        return null;
    }

    /**
     * Gets all guilds in a faction.
     */
    public List<Guild> getGuildsByFaction(String factionId) {
        return guildRepository.getGuildsByFaction(factionId);
    }

    // ═══════════════════════════════════════════════════════
    // MEMBER MANAGEMENT
    // ═══════════════════════════════════════════════════════

    /**
     * Invites a player to a guild.
     */
    public boolean invitePlayer(UUID guildId, UUID playerUuid) {
        PlayerData playerData = playerDataRepository.getPlayerData(playerUuid);
        if (playerData == null || playerData.isInGuild()) {
            return false;
        }

        Guild guild = getGuild(guildId);
        if (guild == null) {
            return false;
        }

        // Check if player is in the same faction
        if (!guild.getFactionId().equals(playerData.getFactionId())) {
            return false;
        }

        guildRepository.createInvitation(guildId, playerUuid);
        return true;
    }

    /**
     * Checks if a player has an invitation to a guild.
     */
    public boolean hasInvitation(UUID guildId, UUID playerUuid) {
        return guildRepository.hasInvitation(guildId, playerUuid);
    }

    /**
     * Gets the count of pending invitations for a player.
     */
    public int getPendingInvitationCount(UUID playerUuid) {
        return guildRepository.getPlayerInvitationCount(playerUuid);
    }

    /**
     * Gets all pending invitations for a player.
     */
    public List<GuildInvitation> getPendingInvitations(UUID playerUuid) {
        List<GuildInvitation> invitations = new java.util.ArrayList<>();
        
        List<Object[]> rawInvites = guildRepository.getPlayerInvitationsDetailed(playerUuid);
        for (Object[] raw : rawInvites) {
            UUID guildId = (UUID) raw[0];
            java.sql.Timestamp invitedAt = (java.sql.Timestamp) raw[1];
            // We don't have inviter info in the current schema, so use null
            invitations.add(new GuildInvitation(
                guildId, 
                playerUuid, 
                null,  // inviterUuid - not stored currently
                null,  // inviterName - not stored currently
                invitedAt != null ? invitedAt.toInstant() : java.time.Instant.now()
            ));
        }
        
        return invitations;
    }

    /**
     * Removes an invitation.
     */
    public void removeInvitation(UUID guildId, UUID playerUuid) {
        guildRepository.deleteInvitation(guildId, playerUuid);
    }

    // ═══════════════════════════════════════════════════════
    // JOIN REQUEST MANAGEMENT
    // ═══════════════════════════════════════════════════════

    /**
     * Creates a join request from a player to a guild.
     */
    public boolean createJoinRequest(UUID guildId, UUID playerUuid) {
        PlayerData playerData = playerDataRepository.getPlayerData(playerUuid);
        if (playerData == null || playerData.isInGuild()) {
            return false;
        }

        Guild guild = getGuild(guildId);
        if (guild == null) {
            return false;
        }

        // Check if player is in the same faction
        if (!guild.getFactionId().equals(playerData.getFactionId())) {
            return false;
        }

        // Check if already has a pending request
        if (guildRepository.hasJoinRequest(guildId, playerUuid)) {
            return false;
        }

        // Check if already has an invitation (no need to request)
        if (guildRepository.hasInvitation(guildId, playerUuid)) {
            return false;
        }

        guildRepository.createJoinRequest(guildId, playerUuid);

        // Notify online officers and leaders
        String requesterName = playerData.getPlayerName();
        if (requesterName == null) requesterName = "A player";
        notifyGuildOfficers(guildId, requesterName + " has requested to join your guild!", Color.YELLOW);

        return true;
    }

    /**
     * Notifies online officers and leaders of a guild with a message.
     */
    private void notifyGuildOfficers(UUID guildId, String messageText, Color color) {
        List<PlayerData> members = playerDataRepository.getGuildMembers(guildId);
        Set<UUID> officerUuids = new HashSet<>();

        // Collect UUIDs of officers and leaders
        for (PlayerData member : members) {
            GuildRole role = member.getGuildRole();
            if (role != null && role.hasAtLeast(GuildRole.OFFICER)) {
                officerUuids.add(member.getPlayerUuid());
            }
        }

        // Find and notify online officers via Universe direct lookup (no .join())
        for (PlayerRef playerRef : Universe.get().getPlayers()) {
            if (playerRef != null && officerUuids.contains(playerRef.getUuid())) {
                playerRef.sendMessage(Message.raw("[Guild] " + messageText).color(color));
            }
        }
    }

    /**
     * Checks if a player has a pending join request to a guild.
     */
    public boolean hasJoinRequest(UUID guildId, UUID playerUuid) {
        return guildRepository.hasJoinRequest(guildId, playerUuid);
    }

    /**
     * Gets all pending join requests for a guild.
     */
    public List<UUID> getGuildJoinRequests(UUID guildId) {
        return guildRepository.getGuildJoinRequests(guildId);
    }

    /**
     * Gets the count of pending join requests for a guild.
     */
    public int getGuildJoinRequestCount(UUID guildId) {
        return guildRepository.getGuildJoinRequestCount(guildId);
    }

    /**
     * Gets all guild IDs that a player has sent join requests to.
     */
    public List<UUID> getPlayerJoinRequests(UUID playerUuid) {
        return guildRepository.getPlayerJoinRequests(playerUuid);
    }

    /**
     * Declines a join request.
     */
    public void declineJoinRequest(UUID guildId, UUID playerUuid) {
        guildRepository.deleteJoinRequest(guildId, playerUuid);
    }

    /**
     * Accepts a join request and adds the player to the guild.
     * Returns true if successful.
     */
    public boolean acceptJoinRequest(UUID guildId, UUID playerUuid) {
        // Verify the request exists
        if (!guildRepository.hasJoinRequest(guildId, playerUuid)) {
            return false;
        }

        Guild guild = getGuild(guildId);
        if (guild == null) {
            return false;
        }

        // Check member limit
        int memberCount = playerDataRepository.getGuildMemberCount(guildId);
        int maxMembers = plugin.getConfig().getGuildMaxMembers();
        if (memberCount >= maxMembers) {
            return false;
        }

        PlayerData playerData = playerDataRepository.getOrCreatePlayerData(playerUuid);
        if (playerData.isInGuild()) {
            // Player joined another guild in the meantime
            guildRepository.deleteJoinRequest(guildId, playerUuid);
            return false;
        }

        // Unclaim any personal claims before joining the guild
        plugin.getClaimManager().unclaimAllForPlayer(playerUuid);

        // Join guild
        playerData.joinGuild(guildId, GuildRole.RECRUIT);
        playerDataRepository.savePlayerData(playerData);

        // Remove the join request and all other requests from this player
        guildRepository.deleteJoinRequest(guildId, playerUuid);
        guildRepository.deletePlayerJoinRequests(playerUuid);

        // Also clear any pending invitations
        guildRepository.deletePlayerInvitations(playerUuid);

        // Update max power
        int defaultPower = plugin.getConfig().getGuildDefaultPower();
        guild.setMaxPower(guild.getMaxPower() + defaultPower);
        guild.addPower(defaultPower);
        guildRepository.updateGuild(guild);

        // Cache
        playerGuildCache.put(playerUuid, guildId);

        // Fire GuildJoinEvent
        String playerName = playerData.getPlayerName();
        if (playerName == null) playerName = "unknown";
        HytaleServer.get().getEventBus().dispatchFor(GuildJoinEvent.class).dispatch(new GuildJoinEvent(
            playerUuid,
            playerName,
            guildId,
            guild.getName(),
            guild.getFactionId()
        ));

        // Update nameplate to show guild tag
        plugin.getNameplateManager().updateNameplateForPlayer(playerUuid);

        // Log member join via request
        String joinName = playerData.getPlayerName() != null ? playerData.getPlayerName() : playerUuid.toString();
        logEvent(guildId, MEMBER_JOIN, playerUuid, joinName + " joined via join request");

        LOGGER.at(Level.INFO).log("Player " + playerUuid + " joined guild " + guild.getName() + " via join request");
        return true;
    }

    /**
     * Player accepts an invitation and joins the guild.
     */
    public boolean joinGuild(UUID guildId, UUID playerUuid) {
        // Check invitation
        if (!guildRepository.hasInvitation(guildId, playerUuid)) {
            return false;
        }

        Guild guild = getGuild(guildId);
        if (guild == null) {
            return false;
        }

        // Check member limit
        int memberCount = playerDataRepository.getGuildMemberCount(guildId);
        int maxMembers = plugin.getConfig().getGuildMaxMembers();
        if (memberCount >= maxMembers) {
            return false;
        }

        PlayerData playerData = playerDataRepository.getOrCreatePlayerData(playerUuid);
        if (playerData.isInGuild()) {
            return false;
        }

        // Unclaim any personal claims before joining the guild
        plugin.getClaimManager().unclaimAllForPlayer(playerUuid);

        // Join guild
        playerData.joinGuild(guildId, GuildRole.RECRUIT);
        playerDataRepository.savePlayerData(playerData);

        // Remove invitation
        guildRepository.deleteInvitation(guildId, playerUuid);
        guildRepository.deletePlayerInvitations(playerUuid); // Clear other invitations

        // Update max power
        int defaultPower = plugin.getConfig().getGuildDefaultPower();
        guild.setMaxPower(guild.getMaxPower() + defaultPower);
        guild.addPower(defaultPower);
        guildRepository.updateGuild(guild);

        // Cache
        playerGuildCache.put(playerUuid, guildId);

        // Fire GuildJoinEvent
        String playerName = playerData.getPlayerName();
        if (playerName == null) playerName = "unknown";
        HytaleServer.get().getEventBus().dispatchFor(GuildJoinEvent.class).dispatch(new GuildJoinEvent(
            playerUuid,
            playerName,
            guildId,
            guild.getName(),
            guild.getFactionId()
        ));

        // Update nameplate to show guild tag
        plugin.getNameplateManager().updateNameplateForPlayer(playerUuid);

        // Log member join via invitation
        String invJoinName = playerData.getPlayerName() != null ? playerData.getPlayerName() : playerUuid.toString();
        logEvent(guildId, MEMBER_JOIN, playerUuid, invJoinName + " joined via invitation");

        LOGGER.at(Level.INFO).log("Player " + playerUuid + " joined guild " + guild.getName());
        return true;
    }

    /**
     * Player leaves their guild.
     */
    public boolean leaveGuild(UUID playerUuid) {
        PlayerData playerData = playerDataRepository.getPlayerData(playerUuid);
        if (playerData == null || !playerData.isInGuild()) {
            return false;
        }

        UUID guildId = playerData.getGuildId();
        Guild guild = getGuild(guildId);
        if (guild == null) {
            return false;
        }

        // Leader cannot leave, must disband or transfer
        if (guild.getLeaderId().equals(playerUuid)) {
            return false;
        }

        // Leave guild
        playerData.leaveGuild();
        playerDataRepository.savePlayerData(playerData);
        plugin.getGuildChunkAccessManager().removeAssignmentsForMember(guildId, playerUuid);

        // Update guild power
        int defaultPower = plugin.getConfig().getGuildDefaultPower();
        guild.setMaxPower(Math.max(defaultPower, guild.getMaxPower() - defaultPower));
        guild.removePower(defaultPower);
        guildRepository.updateGuild(guild);

        // Cache
        playerGuildCache.remove(playerUuid);

        // Fire GuildLeaveEvent
        String playerName = playerData.getPlayerName();
        if (playerName == null) playerName = "unknown";
        HytaleServer.get().getEventBus().dispatchFor(GuildLeaveEvent.class).dispatch(new GuildLeaveEvent(
            playerUuid,
            playerName,
            guildId,
            guild.getName(),
            GuildLeaveEvent.Reason.LEFT
        ));

        // Update nameplate to show faction tag instead of guild
        plugin.getNameplateManager().updateNameplateForPlayer(playerUuid);

        // Log member leave
        String leaveName = playerData.getPlayerName() != null ? playerData.getPlayerName() : playerUuid.toString();
        logEvent(guildId, MEMBER_LEAVE, playerUuid, leaveName + " left the guild");

        LOGGER.at(Level.INFO).log("Player " + playerUuid + " left guild " + guild.getName());
        return true;
    }

    /**
     * Kicks a player from a guild.
     */
    public boolean kickPlayer(UUID guildId, UUID targetUuid, UUID kickerUuid) {
        Guild guild = getGuild(guildId);
        if (guild == null) {
            return false;
        }

        PlayerData kickerData = playerDataRepository.getPlayerData(kickerUuid);
        PlayerData targetData = playerDataRepository.getPlayerData(targetUuid);

        if (kickerData == null || targetData == null) {
            return false;
        }

        // Check permissions
        if (!kickerData.getGuildId().equals(guildId) || !targetData.getGuildId().equals(guildId)) {
            return false;
        }

        if (!kickerData.getGuildRole().canManage(targetData.getGuildRole())) {
            return false;
        }

        // Cannot kick the leader
        if (guild.getLeaderId().equals(targetUuid)) {
            return false;
        }

        // Kick
        targetData.leaveGuild();
        playerDataRepository.savePlayerData(targetData);
        plugin.getGuildChunkAccessManager().removeAssignmentsForMember(guildId, targetUuid);

        // Update guild power
        int defaultPower = plugin.getConfig().getGuildDefaultPower();
        guild.setMaxPower(Math.max(defaultPower, guild.getMaxPower() - defaultPower));
        guild.removePower(defaultPower);
        guildRepository.updateGuild(guild);

        // Cache
        playerGuildCache.remove(targetUuid);

        // Fire GuildLeaveEvent
        String targetName = targetData.getPlayerName();
        if (targetName == null) targetName = "unknown";
        HytaleServer.get().getEventBus().dispatchFor(GuildLeaveEvent.class).dispatch(new GuildLeaveEvent(
            targetUuid,
            targetName,
            guildId,
            guild.getName(),
            GuildLeaveEvent.Reason.KICKED
        ));

        // Update nameplate to remove guild tag
        plugin.getNameplateManager().updateNameplateForPlayer(targetUuid);

        // Log member kick
        String kickTargetName = targetData.getPlayerName() != null ? targetData.getPlayerName() : targetUuid.toString();
        String kickerName = kickerData.getPlayerName() != null ? kickerData.getPlayerName() : kickerUuid.toString();
        logEvent(guildId, MEMBER_KICK, kickerUuid, targetUuid,
                kickerName + " kicked " + kickTargetName);

        LOGGER.at(Level.INFO).log("Player " + targetUuid + " was kicked from guild " + guild.getName());
        return true;
    }

    /**
     * Promotes a player in a guild.
     */
    public boolean promotePlayer(UUID guildId, UUID targetUuid, UUID promoterUuid) {
        Guild guild = getGuild(guildId);
        if (guild == null) {
            return false;
        }

        PlayerData promoterData = playerDataRepository.getPlayerData(promoterUuid);
        PlayerData targetData = playerDataRepository.getPlayerData(targetUuid);

        if (promoterData == null || targetData == null) {
            return false;
        }

        if (!promoterData.getGuildId().equals(guildId) || !targetData.getGuildId().equals(guildId)) {
            return false;
        }

        // Need to be able to manage the target
        if (!promoterData.getGuildRole().canManage(targetData.getGuildRole())) {
            return false;
        }

        // Determine new role
        GuildRole currentRole = targetData.getGuildRole();
        GuildRole newRole = switch (currentRole) {
            case RECRUIT -> GuildRole.MEMBER;
            case MEMBER -> GuildRole.SENIOR;
            case SENIOR -> GuildRole.OFFICER;
            case OFFICER -> GuildRole.LEADER; // Transfer leadership
            case LEADER -> null; // Can't promote leader
        };

        if (newRole == null) {
            return false;
        }

        // If promoting to leader, transfer leadership
        if (newRole == GuildRole.LEADER) {
            // Only leader can transfer leadership
            if (!guild.getLeaderId().equals(promoterUuid)) {
                return false;
            }

            // Transfer
            guild.setLeaderId(targetUuid);
            guildRepository.updateGuild(guild);

            // Demote old leader to officer
            promoterData.setGuildRole(GuildRole.OFFICER);
            playerDataRepository.savePlayerData(promoterData);
        }

        targetData.setGuildRole(newRole);
        playerDataRepository.savePlayerData(targetData);

        // Log member promote
        String promoteTargetName = targetData.getPlayerName() != null ? targetData.getPlayerName() : targetUuid.toString();
        String promoterName = promoterData.getPlayerName() != null ? promoterData.getPlayerName() : promoterUuid.toString();
        logEvent(guildId, MEMBER_PROMOTE, promoterUuid, targetUuid,
                promoterName + " promoted " + promoteTargetName + " to " + newRole);

        LOGGER.at(Level.INFO).log("Player " + targetUuid + " promoted to " + newRole + " in " + guild.getName());
        return true;
    }

    /**
     * Demotes a player in a guild.
     */
    public boolean demotePlayer(UUID guildId, UUID targetUuid, UUID demoterUuid) {
        Guild guild = getGuild(guildId);
        if (guild == null) {
            return false;
        }

        PlayerData demoterData = playerDataRepository.getPlayerData(demoterUuid);
        PlayerData targetData = playerDataRepository.getPlayerData(targetUuid);

        if (demoterData == null || targetData == null) {
            return false;
        }

        if (!demoterData.getGuildId().equals(guildId) || !targetData.getGuildId().equals(guildId)) {
            return false;
        }

        // Need to be able to manage the target
        if (!demoterData.getGuildRole().canManage(targetData.getGuildRole())) {
            return false;
        }

        // Cannot demote leader
        if (guild.getLeaderId().equals(targetUuid)) {
            return false;
        }

        // Determine new role
        GuildRole currentRole = targetData.getGuildRole();
        GuildRole newRole = switch (currentRole) {
            case OFFICER -> GuildRole.SENIOR;
            case SENIOR -> GuildRole.MEMBER;
            case MEMBER -> GuildRole.RECRUIT;
            case RECRUIT -> null; // Can't demote further
            case LEADER -> null; // Can't demote leader
        };

        if (newRole == null) {
            return false;
        }

        targetData.setGuildRole(newRole);
        playerDataRepository.savePlayerData(targetData);

        // Log member demote
        String demoteTargetName = targetData.getPlayerName() != null ? targetData.getPlayerName() : targetUuid.toString();
        String demoterName = demoterData.getPlayerName() != null ? demoterData.getPlayerName() : demoterUuid.toString();
        logEvent(guildId, MEMBER_DEMOTE, demoterUuid, targetUuid,
                demoterName + " demoted " + demoteTargetName + " to " + newRole);

        LOGGER.at(Level.INFO).log("Player " + targetUuid + " demoted to " + newRole + " in " + guild.getName());
        return true;
    }

    /**
     * Gets all members of a guild.
     */
    public List<PlayerData> getGuildMembers(UUID guildId) {
        return playerDataRepository.getGuildMembers(guildId);
    }

    /**
     * Gets member count of a guild.
     */
    public int getMemberCount(UUID guildId) {
        return playerDataRepository.getGuildMemberCount(guildId);
    }

    // ═══════════════════════════════════════════════════════
    // HOME MANAGEMENT
    // ═══════════════════════════════════════════════════════

    /**
     * Sets the guild home location.
     */
    public boolean setHome(UUID guildId, String world, double x, double y, double z) {
        Guild guild = getGuild(guildId);
        if (guild == null) {
            return false;
        }

        guild.setHome(world, x, y, z);
        guildRepository.updateGuild(guild);

        // Log home set (no specific actor here - callers can use logEvent directly with actor)
        logEvent(guildId, HOME_SET, null,
                String.format("Home set to %s (%.0f, %.0f, %.0f)", world, x, y, z));

        return true;
    }

    // ═══════════════════════════════════════════════════════
    // BANK MANAGEMENT
    // ═══════════════════════════════════════════════════════

    /**
     * Deposits money into guild bank.
     */
    public boolean deposit(UUID guildId, double amount) {
        if (amount <= 0) return false;

        Guild guild = getGuild(guildId);
        if (guild == null) {
            return false;
        }

        guild.deposit(amount);
        guildRepository.updateGuild(guild);

        // Log bank deposit (no specific actor here - callers can use logEvent directly with actor)
        logEvent(guildId, BANK_DEPOSIT, null,
                String.format("Deposited %.2f (balance: %.2f)", amount, guild.getBankBalance()));

        return true;
    }

    /**
     * Withdraws money from guild bank.
     */
    public boolean withdraw(UUID guildId, double amount) {
        if (amount <= 0) return false;

        Guild guild = getGuild(guildId);
        if (guild == null) {
            return false;
        }

        if (!guild.withdraw(amount)) {
            return false;
        }

        guildRepository.updateGuild(guild);

        // Log bank withdrawal (no specific actor here - callers can use logEvent directly with actor)
        logEvent(guildId, BANK_WITHDRAW, null,
                String.format("Withdrew %.2f (balance: %.2f)", amount, guild.getBankBalance()));

        return true;
    }

    // ═══════════════════════════════════════════════════════
    // CACHE MANAGEMENT
    // ═══════════════════════════════════════════════════════

    /**
     * Invalidates cache for a player.
     */
    public void invalidateCache(UUID playerUuid) {
        playerGuildCache.remove(playerUuid);
    }

    /**
     * Clears all caches.
     */
    public void clearCache() {
        guildCache.clear();
        playerGuildCache.clear();
    }

    // ═══════════════════════════════════════════════════════
    // GUILD LOG HELPER
    // ═══════════════════════════════════════════════════════

    private void logEvent(UUID guildId, GuildLogType type, UUID actorUuid, String details) {
        GuildLogManager logManager = plugin.getGuildLogManager();
        if (logManager != null) {
            logManager.logEvent(guildId, type, actorUuid, details);
        }
    }

    private void logEvent(UUID guildId, GuildLogType type, UUID actorUuid, UUID targetUuid, String details) {
        GuildLogManager logManager = plugin.getGuildLogManager();
        if (logManager != null) {
            logManager.logEvent(guildId, type, actorUuid, targetUuid, details);
        }
    }
}
