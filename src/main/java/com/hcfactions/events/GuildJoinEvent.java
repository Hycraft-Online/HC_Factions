package com.hcfactions.events;

import com.hypixel.hytale.event.IEvent;

import java.util.UUID;

/**
 * Event fired when a player joins a guild.
 */
public class GuildJoinEvent implements IEvent<Void> {

    private final UUID playerUuid;
    private final String playerName;
    private final UUID guildId;
    private final String guildName;
    private final String factionId;

    public GuildJoinEvent(UUID playerUuid, String playerName, UUID guildId, String guildName, String factionId) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.guildId = guildId;
        this.guildName = guildName;
        this.factionId = factionId;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public UUID getGuildId() {
        return guildId;
    }

    public String getGuildName() {
        return guildName;
    }

    public String getFactionId() {
        return factionId;
    }

    @Override
    public String toString() {
        return "GuildJoinEvent{" +
            "playerUuid=" + playerUuid +
            ", playerName='" + playerName + '\'' +
            ", guildId=" + guildId +
            ", guildName='" + guildName + '\'' +
            ", factionId='" + factionId + '\'' +
            '}';
    }
}
