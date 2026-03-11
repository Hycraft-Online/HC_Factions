package com.hcfactions.combat;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.Message;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.awt.Color;

/**
 * Ticks entities that have a {@link CombatTagComponent} and removes the tag
 * once the configured duration has elapsed.
 *
 * Queries for CombatTagComponent -- only entities currently tagged are processed.
 */
public class CombatTagSystem extends EntityTickingSystem<EntityStore> {

    private static final Message MSG_COMBAT_TAG_EXPIRED =
            Message.raw("[Combat] You are no longer in combat.").color(Color.GREEN);

    private final CombatTagManager manager;

    public CombatTagSystem(CombatTagManager manager) {
        this.manager = manager;
    }

    @Override
    public void tick(float delta, int index, @Nonnull ArchetypeChunk<EntityStore> chunk,
                     @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {

        CombatTagComponent tag = chunk.getComponent(index, CombatTagComponent.getComponentType());
        if (tag == null) return;

        long elapsed = System.currentTimeMillis() - tag.getTagStartTime();
        long durationMs = manager.getTagDurationSeconds() * 1000L;

        if (elapsed >= durationMs) {
            Ref<EntityStore> ref = chunk.getReferenceTo(index);
            buffer.removeComponent(ref, CombatTagComponent.getComponentType());

            // Notify the player their combat tag expired and clear in-memory tracker
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef != null) {
                playerRef.sendMessage(MSG_COMBAT_TAG_EXPIRED);
                manager.clearRecentTag(playerRef.getUuid());
            }
        }
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return CombatTagComponent.getComponentType();
    }

    @Override
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        return EntityTickingSystem.maybeUseParallel(archetypeChunkSize, taskCount);
    }
}
