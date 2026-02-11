package com.hcfactions.events;

import com.hypixel.hytale.event.IEvent;

import java.util.UUID;

/**
 * Event fired when a player leaves a guild.
 */
public class GuildLeaveEvent implements IEvent<Void> {

    /**
     * Reasons a player might leave a guild.
     */
    public enum Reason {
        LEFT,      // Player voluntarily left
        KICKED,    // Player was kicked by an officer/leader
        DISBANDED  // Guild was disbanded
    }

    private final UUID playerUuid;
    private final String playerName;
    private final UUID guildId;
    private final String guildName;
    private final Reason reason;

    public GuildLeaveEvent(UUID playerUuid, String playerName, UUID guildId, String guildName, Reason reason) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.guildId = guildId;
        this.guildName = guildName;
        this.reason = reason;
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

    public Reason getReason() {
        return reason;
    }

    @Override
    public String toString() {
        return "GuildLeaveEvent{" +
            "playerUuid=" + playerUuid +
            ", playerName='" + playerName + '\'' +
            ", guildId=" + guildId +
            ", guildName='" + guildName + '\'' +
            ", reason=" + reason +
            '}';
    }
}
