package com.someone.valvoicebackend;

import com.someone.valvoicebackend.config.ConfigManager;
import com.someone.valvoicebackend.config.ValVoiceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.event.KeyEvent;

/**
 * PttRouter — resolves the correct PTT key based on game state and application mode.
 *
 * In VOICE_PROXY mode, the PTT key is dynamically selected based on
 * the current {@link GameStateManager.GameState}:
 *   MENUS / PREGAME / CUSTOM / UNKNOWN → Party PTT key
 *   INGAME                             → Team PTT key
 *
 * In ACCESSIBILITY mode, the legacy single global key is used.
 *
 * The user configures two PTT keys in ValVoiceConfig (partyPttKey, teamPttKey).
 * These default to "V" — matching current single-key behavior with zero migration.
 */
public final class PttRouter {
    private static final Logger logger = LoggerFactory.getLogger(PttRouter.class);

    private PttRouter() {} // static utility

    /**
     * Resolve the voice channel target for the given game state.
     *
     * @param state current game state
     * @return PARTY or TEAM
     */
    public static VoiceTarget resolveTarget(GameStateManager.GameState state) {
        return switch (state) {
            case MENUS, PREGAME, CUSTOM, UNKNOWN -> VoiceTarget.PARTY;
            case INGAME -> VoiceTarget.TEAM;
        };
    }

    /**
     * Resolve the physical key code for a given voice target.
     *
     * @param target PARTY or TEAM
     * @return the AWT key code to press
     */
    public static int resolveKey(VoiceTarget target) {
        ValVoiceConfig config = ConfigManager.get();
        String keyName = switch (target) {
            case PARTY -> config != null && config.partyPttKey != null ? config.partyPttKey : "V";
            case TEAM  -> config != null && config.teamPttKey  != null ? config.teamPttKey  : "V";
        };
        return resolveKeyCode(keyName);
    }

    /**
     * Resolve the PTT key code for the current game state and application mode.
     * This is the single entry point for VoiceGenerator PTT calls.
     *
     * @return the AWT key code to press
     */
    public static int resolveKeyForCurrentState() {
        if (AppMode.resolve() != AppMode.VOICE_PROXY) {
            // Accessibility mode: use legacy single global key from VoiceGenerator
            if (VoiceGenerator.isInitialized()) {
                return VoiceGenerator.getInstance().getCurrentKeybindCode();
            }
            return KeyEvent.VK_V; // safe fallback
        }

        GameStateManager.GameState state = GameStateManager.getInstance().getCurrentState();
        VoiceTarget target = resolveTarget(state);
        int keyCode = resolveKey(target);

        logger.debug("[PttRouter] state={} → target={} → key={}",
            state, target, KeyEvent.getKeyText(keyCode));

        return keyCode;
    }

    /**
     * Convert a key name string (e.g. "V", "B") to an AWT key code.
     * Falls back to VK_V if the name is unrecognizable.
     */
    private static int resolveKeyCode(String keyName) {
        if (keyName == null || keyName.isBlank()) {
            return KeyEvent.VK_V;
        }

        // Single character → direct mapping
        String upper = keyName.trim().toUpperCase();
        if (upper.length() == 1) {
            char c = upper.charAt(0);
            if (c >= 'A' && c <= 'Z') {
                return KeyEvent.VK_A + (c - 'A');
            }
            if (c >= '0' && c <= '9') {
                return KeyEvent.VK_0 + (c - '0');
            }
        }

        // Fallback
        logger.debug("[PttRouter] Cannot resolve key '{}', defaulting to V", keyName);
        return KeyEvent.VK_V;
    }
}
