package com.hcfactions.systems;

import com.hcfactions.HC_FactionsPlugin;
import com.hcfactions.models.PlayerData;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.RootDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import javax.annotation.Nullable;
import java.awt.Color;
import java.util.Collections;
import java.util.Set;

/**
 * Handles PvP damage based on faction membership.
 * 
 * Rules:
 * - Same faction (any guild) -> BLOCKED (no friendly fire)
 * - Different faction -> ALLOWED (always can attack)
 * - No faction -> Configurable (protected until they choose)
 */
public class FactionDamageSystem extends DamageEventSystem {

    private static final Message MSG_NO_FRIENDLY_FIRE = Message.raw("You cannot attack your own faction members!").color(Color.RED);
    private static final Message MSG_NO_NEUTRAL = Message.raw("This player hasn't chosen a faction yet!").color(Color.RED);

    private final HC_FactionsPlugin plugin;

    public FactionDamageSystem(HC_FactionsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void handle(int index, @NonNullDecl ArchetypeChunk<EntityStore> archetypeChunk,
                       @NonNullDecl Store<EntityStore> store, @NonNullDecl CommandBuffer<EntityStore> commandBuffer,
                       @NonNullDecl Damage damage) {

        // Skip arena/instance worlds - no faction rules there
        var externalData = store.getExternalData();
        if (externalData == null || externalData.getWorld() == null) {
            return;
        }
        String worldName = externalData.getWorld().getName();
        if (HC_FactionsPlugin.isArenaWorld(worldName)) {
            return;
        }

        Ref<EntityStore> victimRef = archetypeChunk.getReferenceTo(index);
        PlayerRef victimPlayerRef = store.getComponent(victimRef, PlayerRef.getComponentType());

        if (victimPlayerRef == null) return;

        // Check if damage source is another player
        if (damage.getSource() instanceof Damage.EntitySource damageEntitySource) {
            Ref<EntityStore> attackerRef = damageEntitySource.getRef();
            if (attackerRef == null || !attackerRef.isValid()) return;

            PlayerRef attackerPlayerRef = (PlayerRef) commandBuffer.getComponent(attackerRef, PlayerRef.getComponentType());
            if (attackerPlayerRef == null) return;

            // Get faction data for both players
            PlayerData attackerData = plugin.getPlayerDataRepository().getPlayerData(attackerPlayerRef.getUuid());
            PlayerData victimData = plugin.getPlayerDataRepository().getPlayerData(victimPlayerRef.getUuid());

            String attackerFactionId = attackerData != null ? attackerData.getFactionId() : null;
            String victimFactionId = victimData != null ? victimData.getFactionId() : null;

            // Neutral player rules (one or both players have no faction)
            if (attackerFactionId == null || victimFactionId == null) {
                // Check if neutral players are protected
                if (plugin.getConfig().isPvpProtectNoFaction()) {
                    damage.setCancelled(true);
                    attackerPlayerRef.sendMessage(MSG_NO_NEUTRAL);
                    return;
                }
                // If not protected, allow damage
                return;
            }

            // Same faction - check config for friendly fire
            if (attackerFactionId.equals(victimFactionId)) {
                if (!plugin.getConfig().isPvpAllowSameFactionPvp()) {
                    damage.setCancelled(true);
                    attackerPlayerRef.sendMessage(MSG_NO_FRIENDLY_FIRE);
                    return;
                }
                // Friendly fire enabled - allow damage
                return;
            }

            // Different factions - damage is allowed (this is faction warfare)
        }
    }

    @Nullable
    @Override
    public SystemGroup<EntityStore> getGroup() {
        // Must be in the FilterDamageGroup to cancel damage BEFORE it's applied
        return DamageModule.get().getFilterDamageGroup();
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return PlayerRef.getComponentType();
    }

    @NonNullDecl
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return Collections.singleton(RootDependency.first());
    }
}
