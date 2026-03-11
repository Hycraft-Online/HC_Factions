package com.hcfactions.hud;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.lang.reflect.Method;
import java.util.logging.Level;

/**
 * Wrapper for HUD registration that transparently uses HC_MultiHud if available,
 * falling back to the native single-HUD manager otherwise.
 */
public class HudWrapper {

    private static final HytaleLogger LOGGER = HytaleLogger.getLogger().getSubLogger("HC_Factions-HudWrapper");
    private static final boolean MULTIPLE_HUD_AVAILABLE;
    private static Object multipleHudInstance;
    private static Method setCustomHudMethod;

    static {
        boolean available = false;
        try {
            Class<?> multiHudClass = Class.forName("com.hcmultihud.HC_MultiHudPlugin");
            Method getInstanceMethod = multiHudClass.getMethod("getInstance");
            multipleHudInstance = getInstanceMethod.invoke(null);
            setCustomHudMethod = multiHudClass.getMethod("setCustomHud",
                    Player.class, PlayerRef.class, String.class, CustomUIHud.class);
            available = true;
            LOGGER.at(Level.INFO).log("HC_MultiHud detected, using multi-HUD support");
        } catch (ClassNotFoundException e) {
            LOGGER.at(Level.INFO).log("HC_MultiHud not found, using standard HUD mode");
        } catch (Exception e) {
            LOGGER.at(Level.WARNING).log("Error initializing HC_MultiHud support: " + e.getMessage());
        }
        MULTIPLE_HUD_AVAILABLE = available;
    }

    /**
     * Show a HUD for the given player using MultiHud if available, native HUD otherwise.
     *
     * @return true if accepted, false if rejected (safe mode)
     */
    public static boolean setCustomHud(Player player, PlayerRef playerRef, String hudId, CustomUIHud hud) {
        if (MULTIPLE_HUD_AVAILABLE) {
            try {
                Object result = setCustomHudMethod.invoke(multipleHudInstance, player, playerRef, hudId, hud);
                if (result instanceof Boolean && !(Boolean) result) {
                    return false;
                }
                return true;
            } catch (Exception e) {
                LOGGER.at(Level.WARNING).log("Error setting HUD via HC_MultiHud: " + e.getMessage());
                // Fallback to native
                player.getHudManager().setCustomHud(playerRef, hud);
                return true;
            }
        } else {
            player.getHudManager().setCustomHud(playerRef, hud);
            return true;
        }
    }
}
