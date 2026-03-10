package com.someone.valvoicebackend.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * ConfigManager - Persistent JSON configuration manager.
 *
 * Phase 7: Loads and saves ValVoiceConfig to:
 *   %LOCALAPPDATA%\ValVoice\config.json
 *
 * Uses Gson (already in project) for serialization.
 * Thread-safe singleton access via get().
 *
 * Failure-safe: If config loading fails, defaults are used.
 * Application never crashes due to config issues.
 */
public final class ConfigManager {

    private static final Logger logger = LoggerFactory.getLogger(ConfigManager.class);

    /** Config directory name under %LOCALAPPDATA% */
    private static final String CONFIG_DIR_NAME = "ValVoice";

    /** Config file name */
    private static final String CONFIG_FILE_NAME = "config.json";

    /** Gson instance with pretty printing for human readability */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Singleton config instance */
    private static volatile ValVoiceConfig config;

    /** Prevent instantiation */
    private ConfigManager() {
        throw new UnsupportedOperationException("Utility class - do not instantiate");
    }

    /**
     * Get the path to the config file.
     *
     * @return Path to %LOCALAPPDATA%\ValVoice\config.json
     */
    public static Path getConfigPath() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isEmpty()) {
            // Fallback to user home
            localAppData = System.getProperty("user.home");
        }
        return Paths.get(localAppData, CONFIG_DIR_NAME, CONFIG_FILE_NAME);
    }

    /**
     * Load configuration from disk.
     * If file does not exist, creates it with defaults.
     * If file is corrupt, falls back to defaults.
     *
     * Must be called once at startup before any config reads.
     *
     * @return The loaded (or default) configuration
     */
    public static ValVoiceConfig load() {
        Path configPath = getConfigPath();

        try {
            // Ensure directory exists
            Files.createDirectories(configPath.getParent());

            if (Files.exists(configPath)) {
                // Load existing config
                String json = Files.readString(configPath, StandardCharsets.UTF_8);
                config = GSON.fromJson(json, ValVoiceConfig.class);

                if (config == null) {
                    logger.warn("[Config] Config file was empty or null, using defaults");
                    config = new ValVoiceConfig();
                    save();
                }

                logger.info("[Config] Loaded configuration");
            } else {
                // First launch: create default config
                logger.info("[Config] No config file found, creating default at: {}", configPath);
                config = new ValVoiceConfig();
                save();
            }
        } catch (Exception e) {
            logger.warn("[Config] Failed to load config (using defaults): {}", e.getMessage());
            config = new ValVoiceConfig();
        }

        // Log loaded values
        logger.info("[Config] PTT Key: {}", config.pttKey);
        logger.info("[Config] XTTS Enabled: {}", config.xttsEnabled);
        logger.info("[Config] SAPI Fallback: {}", config.sapiFallbackEnabled);
        logger.info("[Config] Volume: {}", config.playbackVolume);
        logger.info("[Config] Language: {}", config.language);

        return config;
    }

    /**
     * Save current configuration to disk using atomic write pattern.
     * Phase 7 Step 3: Writes to config.tmp then atomically renames to config.json.
     * Prevents corrupted config files if the application crashes mid-write.
     * Thread-safe. Fails silently on error.
     */
    public static void save() {
        if (config == null) {
            logger.warn("[Config] Cannot save: config not loaded yet");
            return;
        }

        Path configPath = getConfigPath();

        try {
            Files.createDirectories(configPath.getParent());
            String json = GSON.toJson(config);

            // Atomic write: write to .tmp first, then rename
            Path tmpPath = configPath.resolveSibling(CONFIG_FILE_NAME + ".tmp");
            Files.writeString(tmpPath, json, StandardCharsets.UTF_8);

            try {
                Files.move(tmpPath, configPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (UnsupportedOperationException e) {
                // Fallback to regular move if atomic not supported
                Files.move(tmpPath, configPath, StandardCopyOption.REPLACE_EXISTING);
            }

            logger.debug("[Config] Config saved to: {}", configPath);
        } catch (IOException e) {
            logger.warn("[Config] Failed to save config: {}", e.getMessage());
        }
    }

    /**
     * Reload configuration from disk.
     * Phase 7 Step 3: Re-reads config.json and replaces the in-memory instance.
     * If the file does not exist or is corrupt, the existing config is retained.
     * Never throws exceptions.
     */
    public static synchronized void reload() {
        Path configPath = getConfigPath();

        try {
            if (Files.exists(configPath)) {
                String json = Files.readString(configPath, StandardCharsets.UTF_8);
                ValVoiceConfig loaded = GSON.fromJson(json, ValVoiceConfig.class);

                if (loaded != null) {
                    config = loaded;
                    logger.info("[Config] Configuration reloaded from disk");
                } else {
                    logger.warn("[Config] Reload returned null, keeping existing config");
                }
            } else {
                logger.debug("[Config] Config file not found during reload, keeping existing config");
            }
        } catch (Exception e) {
            logger.warn("[Config] Failed to reload config (keeping existing): {}", e.getMessage());
        }
    }

    /**
     * Get the current configuration instance.
     * If load() has not been called, returns defaults.
     *
     * @return The current ValVoiceConfig instance (never null)
     */
    public static ValVoiceConfig get() {
        if (config == null) {
            synchronized (ConfigManager.class) {
                if (config == null) {
                    logger.warn("[Config] get() called before load(), using defaults");
                    config = new ValVoiceConfig();
                }
            }
        }
        return config;
    }
}

