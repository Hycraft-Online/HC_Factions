package com.hcfactions.managers;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a single entry in the guild activity log.
 */
public class GuildLogEntry {

    private final long id;
    private final UUID guildId;
    private final GuildLogType eventType;
    private final UUID actorUuid;
    private final UUID targetUuid;
    private final String details;
    private final Instant createdAt;

    public GuildLogEntry(long id, UUID guildId, GuildLogType eventType,
                         UUID actorUuid, UUID targetUuid, String details, Instant createdAt) {
        this.id = id;
        this.guildId = guildId;
        this.eventType = eventType;
        this.actorUuid = actorUuid;
        this.targetUuid = targetUuid;
        this.details = details;
        this.createdAt = createdAt;
    }

    public long getId() {
        return id;
    }

    public UUID getGuildId() {
        return guildId;
    }

    public GuildLogType getEventType() {
        return eventType;
    }

    public UUID getActorUuid() {
        return actorUuid;
    }

    public UUID getTargetUuid() {
        return targetUuid;
    }

    public String getDetails() {
        return details;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
