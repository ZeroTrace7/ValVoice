package com.someone.valvoicegui;

import com.someone.valvoicebackend.Chat;
import com.someone.valvoicebackend.EnvironmentValidator;
import com.someone.valvoicebackend.Source;
import com.someone.valvoicebackend.SystemAudioRouter;
import com.someone.valvoicebackend.config.ConfigManager;
import javafx.application.Application;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Properties;

/**
 * Main - Pure thin launcher for ValVoice application.
 *
 * Phase 1 Final: ValorantNarrator reference architecture compliance.
 *
 * Allowed responsibilities (and ONLY these):
 * - Single-instance lock management
 * - Config directory bootstrap (mkdirs)
 * - Configuration loading
 * - Launching JavaFX via Application.launch()
 *
 * All backend responsibilities are delegated to:
 * - ValVoiceBackend.java (MITM, parsing, identity, filtering)
 * - ValVoiceController.initialize() triggers backend startup
 *
 * This class has NO references to:
 * - MessageType, ValVoiceController, ChatDataHandler
 * - Regex, Gson, thread pools, or backend classes
 */
public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static final String APP_NAME = "ValVoice";
    public static final String CONFIG_DIR = Paths.get(System.getenv("APPDATA"), APP_NAME).toString();
    public static final String LOCK_FILE_NAME = "lockFile";
    public static final String LOCK_FILE = Paths.get(CONFIG_DIR, LOCK_FILE_NAME).toString();
    public static double currentVersion;

    // === VN-parity: User Config Persistence ===
    // Storage: %APPDATA%/ValVoice/config.properties
    private static final String USER_CONFIG_FILE = "config.properties";
    private static final Properties userProperties = new Properties();

    // Property keys
    public static final String PROP_VOICE = "voice";
    public static final String PROP_SPEED = "speed";
    public static final String PROP_PTT_KEY = "pttKey";
    public static final String PROP_PTT_ENABLED = "pttEnabled";
    public static final String PROP_SOURCE = "source"; // VN-parity: channel filter persistence

    // Lock file resources (application instance lock)
    private static RandomAccessFile lockFileAccess;
    private static FileLock instanceLock;

    // Phase 0A (OCR migration): isRiotOrValorantRunning() removed.
    // OCR mode has no startup ordering requirement with Riot or Valorant.
    // ValVoiceOCR.exe finds the Valorant window via polling after launch.

    /**
     * Process Reaper: Cold Boot Cleanup.
     * Kills orphaned ValVoice-owned processes from previous crashed sessions.
     *
     * Phase 0A (OCR migration):
     *   - ValVoiceOCR.exe: killed to clean up orphaned OCR sidecar from a previous crash
     *   - valvoice-mitm.exe: kept during transition period; remove after MITM fully retired
     *   - RiotClientServices.exe: NEVER killed (killing Riot destroys user's active game session)
     *   - VALORANT-Win64-Shipping.exe: NEVER killed (same reason)
     *
     * Best-effort: failures are non-fatal (process may not exist).
     */
    private static void runProcessReaper() {
        String[] processesToKill = {
            "ValVoiceOCR.exe",      // Orphaned OCR sidecar from previous crash
            "valvoice-mitm.exe"     // Transition period: remove after MITM fully retired
            // DO NOT add RiotClientServices.exe or VALORANT-Win64-Shipping.exe here
        };

        logger.info("[Reaper] Running Cold Boot Process Reaper (best-effort)...");

        for (String processName : processesToKill) {
            try {
                ProcessBuilder pb = new ProcessBuilder("taskkill", "/F", "/IM", processName);
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                // Consume output to prevent blocking
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        logger.debug("[Reaper] taskkill {}: {}", processName, line);
                    }
                }
                int exitCode = proc.waitFor();
                if (exitCode == 0) {
                    logger.info("[Reaper] Terminated: {}", processName);
                } else {
                    // Exit code 128 = no matching processes found (normal/expected case)
                    logger.debug("[Reaper] {} not running (exit code: {})", processName, exitCode);
                }
            } catch (Exception e) {
                logger.debug("[Reaper] Failed to kill {} (non-fatal): {}", processName, e.getMessage());
            }
        }

        // Phase 0A: Port-release sleep removed — OCR sidecar has no TCP network stack.
        logger.info("[Reaper] Cold Boot cleanup complete");
    }


    /**
     * Show a pre-JavaFX error dialog using Swing.
     * Used only for critical startup failures before JavaFX is available.
     */
    private static void showStartupError(String title, String message) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            logger.warn("Failed to set system LookAndFeel", e);
        }
        Toolkit.getDefaultToolkit().beep();
        JOptionPane.showMessageDialog(null, message, title, JOptionPane.ERROR_MESSAGE);
    }

    /**
     * Acquire single-instance lock to prevent multiple ValVoice instances.
     */
    private static void lockInstance() {
        try {
            Files.createDirectories(Path.of(CONFIG_DIR));
            File file = new File(LOCK_FILE);
            if (!file.exists()) {
                file.createNewFile();
            }
            lockFileAccess = new RandomAccessFile(file, "rw");
            instanceLock = lockFileAccess.getChannel().tryLock();

            if (instanceLock == null) {
                lockFileAccess.close();
                showStartupError(APP_NAME, "Another instance of this application is already running!");
                System.exit(0);
            }

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    if (instanceLock != null && instanceLock.isValid()) {
                        instanceLock.release();
                    }
                    if (lockFileAccess != null) {
                        lockFileAccess.close();
                    }
                    Files.deleteIfExists(Paths.get(LOCK_FILE));
                } catch (IOException e) {
                    logger.error("Error releasing lock", e);
                }
            }, "lock-cleanup"));
        } catch (IOException e) {
            logger.error("Error creating lock file", e);
        }
    }

    // ========== VN-parity: User Config Persistence ==========

    /**
     * Get the user properties (for reading/writing config values).
     * Thread-safe: callers should synchronize if doing read-modify-write.
     */
    public static Properties getProperties() {
        return userProperties;
    }

    /**
     * Get the full path to the user config file.
     */
    public static String getConfigPath() {
        return Paths.get(CONFIG_DIR, USER_CONFIG_FILE).toString();
    }

    /**
     * Save user config to %APPDATA%/ValVoice/config.properties.
     * VN-parity: Called immediately when default values need to be persisted (e.g., fresh install).
     * Non-blocking (async) to avoid blocking startup.
     */
    private static void saveConfig() {
        new Thread(() -> {
            try {
                Files.createDirectories(Paths.get(CONFIG_DIR));
                try (OutputStream out = new FileOutputStream(getConfigPath())) {
                    userProperties.store(out, "ValVoice User Configuration");
                }
                logger.debug("[Config] Config saved to: {}", getConfigPath());
            } catch (Exception e) {
                logger.warn("[Config] Could not save config: {}", e.getMessage());
            }
        }, "config-saver-startup").start();
    }

    /**
     * Load user config from %APPDATA%/ValVoice/config.properties.
     * VN-parity: If file missing or corrupt, use defaults (no crash).
     * Also applies persisted source (channel filters) to Chat runtime model.
     */
    private static void loadUserConfig() {
        Path configPath = Paths.get(CONFIG_DIR, USER_CONFIG_FILE);
        try {
            if (Files.exists(configPath)) {
                try (InputStream in = new FileInputStream(configPath.toFile())) {
                    userProperties.load(in);
                    logger.info("[Config] Loaded user config from: {}", configPath);
                }
            } else {
                logger.info("[Config] No user config file found, using defaults");
            }
        } catch (Exception e) {
            logger.warn("[Config] Failed to load user config (using defaults): {}", e.getMessage());
            userProperties.clear();
        }

        // VN-parity: Apply persisted source (channel filters) to Chat runtime model
        applySourceToChat();
    }

    /**
     * VN-parity: Apply persisted source property to Chat runtime model.
     * Called after config load to restore channel filter state.
     */
    private static void applySourceToChat() {
        String sourceStr = userProperties.getProperty(PROP_SOURCE);
        EnumSet<Source> sources;

        if (sourceStr == null || sourceStr.isBlank()) {
            // No persisted source - use default (SELF+PARTY+TEAM, excludes ALL chat)
            sources = Source.getDefault();
            logger.info("[Config] No persisted source, using default: {}", Source.toString(sources));

            // VN-parity: Persist the default immediately so fresh installs have a saved value
            userProperties.setProperty(PROP_SOURCE, Source.toString(sources));
            saveConfig();
            logger.info("[Config] Persisted default source for fresh install: {}", Source.toString(sources));
        } else {
            sources = Source.fromString(sourceStr);
            // fromString() now returns default for invalid input, so check if it matches the input
            String parsedStr = Source.toString(sources);
            if (!parsedStr.equalsIgnoreCase(sourceStr.replace(" ", ""))) {
                logger.warn("[Config] Source string '{}' parsed to '{}', using parsed value",
                    sourceStr, parsedStr);
            } else {
                logger.info("[Config] Restored source from config: {}", sourceStr);
            }
        }

        // Apply to Chat runtime model
        Chat.getInstance().setSources(sources);
    }

    /**
     * Application entry point - pure launcher.
     */
    public static void main(String[] args) {
        // ===== HIGH-DPI RENDERING FIX (must be BEFORE any JavaFX initialization) =====
        // Ensures crisp text rendering on Windows at 125%/150% display scaling
        System.setProperty("prism.text", "t2k");
        System.setProperty("prism.lcdtext", "true");
        System.setProperty("glass.win.uiScale", "1.0");

        // ===== GLOBAL CRASH LOGGING (Architecture-Agnostic) =====
        // Ensure logs directory exists before any logging occurs
        try {
            Path logsDir = Paths.get(System.getenv("LOCALAPPDATA"), "ValVoice", "logs");
            Files.createDirectories(logsDir);
        } catch (Exception e) {
            // Best-effort: if directory creation fails, logback will still attempt to write
            System.err.println("[ValVoice] Warning: Could not create logs directory: " + e.getMessage());
        }

        // Register global uncaught exception handler to capture fatal crashes
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            logger.error("Uncaught exception in thread: {}", thread.getName(), throwable);

            try {
                Path crashFile = Paths.get(
                    System.getenv("LOCALAPPDATA"),
                    "ValVoice",
                    "logs",
                    "crash.log"
                );

                // Extract full stack trace
                java.io.StringWriter sw = new java.io.StringWriter();
                throwable.printStackTrace(new java.io.PrintWriter(sw));

                // Create timestamp
                String time = java.time.LocalDateTime.now().toString();

                // Build formatted crash entry
                String logEntry = String.format(
                    "\n\n--- CRASH at %s ---\nThread: %s\n%s",
                    time,
                    thread.getName(),
                    sw.toString()
                );

                Files.writeString(
                    crashFile,
                    logEntry,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
                );
            } catch (Exception ignored) {
            }
        });

        logger.info("Starting {} Application", APP_NAME);

        // Phase 0A (OCR migration): Startup guard removed.
        // OCR mode reads the Valorant window directly — Riot/Valorant may already be running.
        // The OCR sidecar polls for the window handle and waits; no launch ordering is required.
        logger.info("[Startup] OCR mode — no startup ordering requirement with Riot or Valorant.");

        // Cold Boot Process Reaper: cleans up orphaned ValVoice-owned processes.
        // Never kills RiotClientServices.exe or VALORANT-Win64-Shipping.exe.
        runProcessReaper();

        // Shutdown Reaper Hook: safety net to kill orphaned ValVoice-owned processes on exit.
        // Phase 0A: targets ValVoiceOCR.exe (OCR sidecar). Never kills Riot or Valorant.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("[Reaper] Running Shutdown Reaper...");
            String[] processesToKill = {"ValVoiceOCR.exe", "valvoice-mitm.exe"};
            for (String processName : processesToKill) {
                try {
                    ProcessBuilder pb = new ProcessBuilder("taskkill", "/F", "/IM", processName);
                    pb.redirectErrorStream(true);
                    Process proc = pb.start();
                    // Consume output to prevent OS buffer deadlock
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                        while (reader.readLine() != null) { /* drain */ }
                    }
                    proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception e) {
                    // Best-effort, ignore failures
                }
            }
            logger.info("[Reaper] Shutdown Reaper complete");
        }, "valvoice-shutdown-reaper"));

        // 1. Bootstrap config directory
        try {
            Files.createDirectories(Paths.get(CONFIG_DIR));
            logger.info("Config directory: {}", CONFIG_DIR);
        } catch (IOException e) {
            logger.error("Could not create config directory", e);
        }

        // VN-parity: Load user config (voice, speed, PTT key)
        loadUserConfig();

        // 2. Load configuration
        try (InputStream in = Main.class.getResourceAsStream("/com/someone/valvoicegui/config.properties")) {
            if (in == null) {
                throw new FileNotFoundException("config.properties not found");
            }
            Properties props = new Properties();
            props.load(in);
            currentVersion = Double.parseDouble(props.getProperty("version", "0.0"));
            logger.info("Version: {}", currentVersion);
        } catch (Exception e) {
            logger.error("CRITICAL: Could not load config!", e);
            showStartupError("Configuration Error", "Could not load configuration. Please reinstall.");
            System.exit(-1);
        }

        // 3. Acquire single-instance lock
        lockInstance();

        // Phase 8: Startup environment validation (read-only diagnostics)
        // Checks SoundVolumeView, PowerShell, and VB-Cable before any audio operations
        EnvironmentValidator.runAllChecks();

        // Phase 3: Startup SoundVolumeView hijack for the current Java PID.
        // Must run BEFORE any TTS playback occurs.
        SystemAudioRouter.routeApplicationAudio();

        // Phase 7: Load persistent JSON configuration (%LOCALAPPDATA%\ValVoice\config.json)
        // Must execute before JavaFX launch so config values are available to all systems
        ConfigManager.load();

        // Cache safety: clean up stale .tmp files left by interrupted audio generation
        cleanupTempCacheFiles();

        // 4. Launch JavaFX (backend started by ValVoiceController.initialize())
        logger.info("Launching JavaFX Application");
        Application.launch(ValVoiceApplication.class, args);
    }

    /**
     * Clean up stale temporary files (.tmp) in the audio cache directory.
     * These files are left behind when audio generation is interrupted (crash, kill, etc.).
     * Runs once at startup. Only removes .tmp files — never touches .mp3 or .wav.
     */
    private static void cleanupTempCacheFiles() {
        try {
            Path cacheDir = Paths.get(System.getenv("LOCALAPPDATA"), "ValVoice", "cache");
            if (!Files.exists(cacheDir) || !Files.isDirectory(cacheDir)) {
                return;
            }

            File[] tmpFiles = cacheDir.toFile().listFiles((dir, name) -> name.endsWith(".tmp"));
            if (tmpFiles == null || tmpFiles.length == 0) {
                return;
            }

            int removed = 0;
            for (File tmpFile : tmpFiles) {
                try {
                    if (tmpFile.delete()) {
                        removed++;
                    }
                } catch (Exception e) {
                    logger.debug("[Cache] Could not delete temp file {}: {}", tmpFile.getName(), e.getMessage());
                }
            }

            if (removed > 0) {
                logger.info("[Cache] Removed {} stale temporary audio file{}", removed, removed == 1 ? "" : "s");
            }
        } catch (Exception e) {
            logger.debug("[Cache] Temp file cleanup failed (non-fatal): {}", e.getMessage());
        }
    }
}
