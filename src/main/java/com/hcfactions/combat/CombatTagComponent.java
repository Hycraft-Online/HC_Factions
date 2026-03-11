package com.hcfactions.combat;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * Transient ECS component that marks a player as "in combat".
 * Applied when PvP damage occurs between cross-faction players.
 * Cleared automatically by {@link CombatTagSystem} after the configured duration expires.
 *
 * Registered as transient (no persistence) -- tags clear on server restart.
 */
public class CombatTagComponent implements Component<EntityStore> {

    private static ComponentType<EntityStore, CombatTagComponent> componentType;

    private long tagStartTime;
    private UUID attackerUuid;

    /** Default constructor (required for transient registration). */
    public CombatTagComponent() {
        this(0L, null);
    }

    public CombatTagComponent(long tagStartTime, UUID attackerUuid) {
        this.tagStartTime = tagStartTime;
        this.attackerUuid = attackerUuid;
    }

    /** Copy constructor. */
    public CombatTagComponent(CombatTagComponent other) {
        this.tagStartTime = other.tagStartTime;
        this.attackerUuid = other.attackerUuid;
    }

    // ─── Static component type management ───

    public static void setComponentType(ComponentType<EntityStore, CombatTagComponent> type) {
        componentType = type;
    }

    @Nonnull
    public static ComponentType<EntityStore, CombatTagComponent> getComponentType() {
        return componentType;
    }

    // ─── Getters / Setters ───

    public long getTagStartTime() {
        return tagStartTime;
    }

    public void setTagStartTime(long tagStartTime) {
        this.tagStartTime = tagStartTime;
    }

    public UUID getAttackerUuid() {
        return attackerUuid;
    }

    public void setAttackerUuid(UUID attackerUuid) {
        this.attackerUuid = attackerUuid;
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        return new CombatTagComponent(this);
    }
}
