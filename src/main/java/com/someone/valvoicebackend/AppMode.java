package com.someone.valvoicebackend;

import com.someone.valvoicebackend.config.ConfigManager;
import com.someone.valvoicebackend.config.ValVoiceConfig;

/**
 * Application operation mode.
 *
 * VOICE_PROXY: Text-to-voice proxy. Only own messages, body-only narration,
 *              dynamic PTT routing based on game state.
 *
 * ACCESSIBILITY: VN-style accessibility reader. May read other players'
 *                messages, includes name prefixes, static PTT.
 */
public enum AppMode {
    VOICE_PROXY,
    ACCESSIBILITY;

    /**
     * Resolve the current application mode from persistent configuration.
     * Default: VOICE_PROXY (safe for new installs).
     *
     * @return the active AppMode
     */
    public static AppMode resolve() {
        ValVoiceConfig config = ConfigManager.get();
        if (config != null && "ACCESSIBILITY".equalsIgnoreCase(config.appMode)) {
            return ACCESSIBILITY;
        }
        return VOICE_PROXY;
    }
}
