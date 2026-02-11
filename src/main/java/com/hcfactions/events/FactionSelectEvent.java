package com.hcfactions.events;

import com.hypixel.hytale.event.IEvent;

import java.util.UUID;

/**
 * Event fired when a player selects their faction for the first time.
 */
public class FactionSelectEvent implements IEvent<Void> {

    private final UUID playerUuid;
    private final String playerName;
    private final String factionId;
    private final String factionName;

    public FactionSelectEvent(UUID playerUuid, String playerName, String factionId, String factionName) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.factionId = factionId;
        this.factionName = factionName;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getFactionId() {
        return factionId;
    }

    public String getFactionName() {
        return factionName;
    }

    @Override
    public String toString() {
        return "FactionSelectEvent{" +
            "playerUuid=" + playerUuid +
            ", playerName='" + playerName + '\'' +
            ", factionId='" + factionId + '\'' +
            ", factionName='" + factionName + '\'' +
            '}';
    }
}
