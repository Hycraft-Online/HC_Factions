package com.hcfactions.models;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a pending guild invitation.
 */
public class GuildInvitation {

    private final UUID guildId;
    private final UUID playerUuid;
    private final UUID inviterUuid;
    private final String inviterName;
    private final Instant createdAt;

    public GuildInvitation(UUID guildId, UUID playerUuid, UUID inviterUuid, String inviterName, Instant createdAt) {
        this.guildId = guildId;
        this.playerUuid = playerUuid;
        this.inviterUuid = inviterUuid;
        this.inviterName = inviterName;
        this.createdAt = createdAt;
    }

    public UUID getGuildId() {
        return guildId;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public UUID getInviterUuid() {
        return inviterUuid;
    }

    public String getInviterName() {
        return inviterName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
