package com.hcfactions.combat;

import com.hcfactions.HC_FactionsPlugin;
import com.hcfactions.models.PlayerData;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.RootDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Set;

/**
 * Listens for PvP damage events (post-filter, in InspectDamageGroup) and applies
 * combat tags to both attacker and victim.
 *
 * Only tags for cross-faction PvP. Same-faction friendly fire (when allowed) does
 * not trigger combat tags, since same-faction PvP is a controlled opt-in setting.
 */
public class CombatTagDamageSystem extends DamageEventSystem {

    private final HC_FactionsPlugin plugin;
    private final CombatTagManager combatTagManager;

    public CombatTagDamageSystem(HC_FactionsPlugin plugin, CombatTagManager combatTagManager) {
        this.plugin = plugin;
        this.combatTagManager = combatTagManager;
    }

    @Override
    public void handle(int index, @NonNullDecl ArchetypeChunk<EntityStore> archetypeChunk,
                       @NonNullDecl Store<EntityStore> store,
                       @NonNullDecl CommandBuffer<EntityStore> commandBuffer,
                       @NonNullDecl Damage damage) {

        // Skip cancelled damage (friendly fire blocked by FactionDamageSystem, etc.)
        if (damage.isCancelled()) return;

        // Skip if damage is zero or negative
        if (damage.getAmount() <= 0) return;

        // Skip arena/instance worlds -- no faction combat tag there
        var externalData = store.getExternalData();
        if (externalData == null || externalData.getWorld() == null) return;
        String worldName = externalData.getWorld().getName();
        if (HC_FactionsPlugin.isArenaWorld(worldName)) return;

        // Victim must be a player
        Ref<EntityStore> victimRef = archetypeChunk.getReferenceTo(index);
        PlayerRef victimPlayerRef = store.getComponent(victimRef, PlayerRef.getComponentType());
        if (victimPlayerRef == null) return;

        // Attacker must be a player (direct entity damage or projectile)
        Ref<EntityStore> attackerRef = null;
        if (damage.getSource() instanceof Damage.EntitySource entitySource) {
            attackerRef = entitySource.getRef();
        } else if (damage.getSource() instanceof Damage.ProjectileSource projectileSource) {
            attackerRef = projectileSource.getRef();
        }
        if (attackerRef == null || !attackerRef.isValid()) return;

        PlayerRef attackerPlayerRef = commandBuffer.getComponent(attackerRef, PlayerRef.getComponentType());
        if (attackerPlayerRef == null) {
            attackerPlayerRef = store.getComponent(attackerRef, PlayerRef.getComponentType());
        }
        if (attackerPlayerRef == null) return;

        // Don't tag if same player (self-damage)
        if (attackerPlayerRef.getUuid().equals(victimPlayerRef.getUuid())) return;

        // Only tag for cross-faction PvP
        PlayerData attackerData = plugin.getPlayerDataRepository().getPlayerData(attackerPlayerRef.getUuid());
        PlayerData victimData = plugin.getPlayerDataRepository().getPlayerData(victimPlayerRef.getUuid());

        String attackerFactionId = attackerData != null ? attackerData.getFactionId() : null;
        String victimFactionId = victimData != null ? victimData.getFactionId() : null;

        // Skip tagging for same-faction PvP (even if friendly fire is enabled -- it's opt-in)
        if (attackerFactionId != null && attackerFactionId.equals(victimFactionId)) return;

        // Tag both players
        combatTagManager.tagPlayer(store, commandBuffer, victimRef, attackerPlayerRef.getUuid(), true);
        combatTagManager.tagPlayer(store, commandBuffer, attackerRef, victimPlayerRef.getUuid(), true);
    }

    @Nullable
    @Override
    public SystemGroup<EntityStore> getGroup() {
        // InspectDamageGroup runs AFTER filters -- damage that was cancelled by
        // FactionDamageSystem will have isCancelled() == true and we skip it.
        return DamageModule.get().getInspectDamageGroup();
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        // Match all player entities
        return PlayerRef.getComponentType();
    }

    @NonNullDecl
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return Collections.singleton(RootDependency.first());
    }
}
