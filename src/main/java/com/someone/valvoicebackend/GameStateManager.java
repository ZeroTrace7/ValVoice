package com.someone.valvoicebackend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

/**
 * GameStateManager - Minimal, thread-safe holder for Valorant game state.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * PHASE 4: GAME STATE AWARENESS ("Smart Mute")
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * INTENTIONAL DEVIATION FROM VALORANTNARRATOR:
 * ValorantNarrator does NOT implement presence-based game state awareness.
 * This is a ValVoice enhancement that is strictly ADDITIVE and ISOLATED.
 *
 * NON-NEGOTIABLE INVARIANTS (must NOT regress):
 * - UTC + grace window timestamp gate remains unchanged
 * - MITM never crashes on ECONNRESET
 * - Archive IQ history is never narrated
 * - Teammates' messages are never narrated (self-only invariant)
 *
 * This class only stores game state extracted from XMPP presence stanzas.
 * The gating logic lives in ChatDataHandler.message() AFTER the self-only filter.
 * ═══════════════════════════════════════════════════════════════════════════════
 */
public class GameStateManager {
    private static final Logger logger = LoggerFactory.getLogger(GameStateManager.class);
    private static final GameStateManager INSTANCE = new GameStateManager();

    /**
     * Valorant session loop states extracted from presence &lt;p&gt; payload.
     * Values match Riot's sessionLoopState field exactly.
     */
    public enum GameState {
        UNKNOWN,    // Initial state or unparseable
        MENUS,      // In main menu / lobby
        PREGAME,    // Agent select
        INGAME      // Active match (rounds in progress)
    }

    // Thread-safe state holder using AtomicReference
    // volatile semantics are provided by AtomicReference
    private final AtomicReference<GameState> currentState = new AtomicReference<>(GameState.UNKNOWN);

    // Clutch mode toggle - when enabled AND in INGAME, narration is suppressed
    // Using AtomicBoolean for thread-safe toggle operations
    private final java.util.concurrent.atomic.AtomicBoolean clutchModeEnabled =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    private GameStateManager() {
        logger.info("[GameStateManager] Initialized with state=UNKNOWN, clutchMode=false");
    }

    public static GameStateManager getInstance() {
        return INSTANCE;
    }

    /**
     * Get the current game state.
     * Thread-safe read.
     */
    public GameState getCurrentState() {
        return currentState.get();
    }

    /**
     * Update the current game state.
     * Thread-safe write with logging on state transitions.
     *
     * @param newState The new game state
     */
    public void setCurrentState(GameState newState) {
        if (newState == null) {
            newState = GameState.UNKNOWN;
        }

        GameState oldState = currentState.getAndSet(newState);

        if (oldState != newState) {
            logger.info("[GameStateManager] State transition: {} → {}", oldState, newState);
        }
    }

    /**
     * Update state from sessionLoopState string (extracted from presence payload).
     *
     * @param sessionLoopState Raw value from Riot presence JSON (e.g., "MENUS", "PREGAME", "INGAME")
     */
    public void updateFromSessionLoopState(String sessionLoopState) {
        if (sessionLoopState == null || sessionLoopState.isEmpty()) {
            logger.debug("[GameStateManager] Ignoring null/empty sessionLoopState");
            return;
        }

        String upper = sessionLoopState.toUpperCase();
        GameState newState = switch (upper) {
            case "MENUS" -> GameState.MENUS;
            case "PREGAME" -> GameState.PREGAME;
            case "INGAME" -> GameState.INGAME;
            default -> {
                logger.debug("[GameStateManager] Unknown sessionLoopState '{}', treating as UNKNOWN", sessionLoopState);
                yield GameState.UNKNOWN;
            }
        };

        setCurrentState(newState);
    }

    /**
     * Check if clutch mode is enabled.
     */
    public boolean isClutchModeEnabled() {
        return clutchModeEnabled.get();
    }

    /**
     * Enable or disable clutch mode.
     * When clutch mode is ON and state is INGAME, narration is suppressed.
     *
     * @param enabled true to enable clutch mode
     */
    public void setClutchModeEnabled(boolean enabled) {
        boolean oldValue = clutchModeEnabled.getAndSet(enabled);

        if (oldValue != enabled) {
            logger.info("[GameStateManager] Clutch mode: {} → {}", oldValue, enabled);
        }
    }

    /**
     * Toggle clutch mode on/off.
     *
     * @return The new state after toggling
     */
    public boolean toggleClutchMode() {
        // Atomic toggle using compareAndSet pattern
        boolean current;
        boolean newValue;
        do {
            current = clutchModeEnabled.get();
            newValue = !current;
        } while (!clutchModeEnabled.compareAndSet(current, newValue));

        logger.info("[GameStateManager] Clutch mode toggled: now {}", newValue ? "ON" : "OFF");
        return newValue;
    }

    /**
     * Check if narration should be suppressed based on current game state and clutch mode.
     *
     * This is the gate condition used by ChatDataHandler AFTER the self-only filter.
     *
     * @return true if narration should be suppressed (dropped)
     */
    public boolean shouldSuppressNarration() {
        if (!clutchModeEnabled.get()) {
            // Clutch mode disabled - never suppress
            return false;
        }

        GameState state = currentState.get();
        if (state == GameState.INGAME) {
            // Clutch mode ON + INGAME = suppress narration
            logger.debug("[GameStateManager] Suppressing narration: clutchMode=ON, state=INGAME");
            return true;
        }

        // Clutch mode ON but not INGAME - allow narration
        return false;
    }

    /**
     * Reset state to UNKNOWN.
     * Called during cleanup or when presence is lost.
     */
    public void reset() {
        currentState.set(GameState.UNKNOWN);
        logger.info("[GameStateManager] State reset to UNKNOWN");
    }
}
