package com.someone.valvoicebackend.config;

/**
 * ValVoiceConfig - Plain configuration data model.
 *
 * Phase 7: Persistent configuration for runtime settings.
 * Serialized to/from JSON via Gson.
 *
 * Default values are defined here and used for fresh installs.
 * No logic in this class — it is a pure data holder.
 */
public class ValVoiceConfig {

    /** Push-to-Talk key (single character, e.g. "V") */
    public String pttKey = "V";

    /** Whether the XTTS backend engine is enabled */
    public boolean xttsEnabled = true;

    /** Whether Windows SAPI fallback is enabled when XTTS is unavailable */
    public boolean sapiFallbackEnabled = true;

    /** Playback volume (0.0 to 1.0) */
    public double playbackVolume = 1.0;

    /** TTS language code (e.g. "en") */
    public String language = "en";

    /** Whether the first-run setup wizard has been completed */
    public boolean firstRunCompleted = false;
}

