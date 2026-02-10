package com.someone.valvoicegui;

import com.someone.valvoicebackend.Chat;
import com.someone.valvoicebackend.Source;
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

    /**
     * Phase 1: Startup Guard (Cold Boot Check)
     * Checks if Riot Client or Valorant is already running.
     * ValVoice must start BEFORE Riot Client to intercept XMPP traffic.
     */
    private static boolean isRiotOrValorantRunning() {
        try {
            ProcessBuilder pb = new ProcessBuilder("tasklist", "/FO", "CSV", "/NH");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String lower = line.toLowerCase(Locale.ROOT);
                    if (lower.contains("riotclientservices.exe") || lower.contains("valorant.exe")) {
                        logger.warn("Detected running process: {}", line);
                        return true;
                    }
                }
            }
            proc.waitFor();
        } catch (Exception e) {
            logger.error("Failed to check for Riot/Valorant processes", e);
            // If we can't check, allow startup (best-effort)
        }
        return false;
    }

    /**
     * VN-parity Process Reaper: Cold Boot Cleanup.
     * Kills orphaned processes from previous sessions to ensure ports are free.
     * Best-effort: failures are non-fatal (process may not exist).
     */
    private static void runProcessReaper() {
        String[] processesToKill = {
            "valvoice-mitm.exe",
            "RiotClientServices.exe",
            "VALORANT-Win64-Shipping.exe"
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

        // Wait for OS to release ports (avoid EADDRINUSE race condition)
        logger.debug("[Reaper] Waiting 1s for port release...");
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.debug("[Reaper] Port wait interrupted");
        }

        logger.info("[Reaper] Cold Boot cleanup complete");
    }

    /**
     * @deprecated Use {@link #runProcessReaper()} instead.
     * Kept for backwards compatibility - now delegates to Reaper.
     */
    @Deprecated
    private static void cleanupStaleMitmProcesses() {
        runProcessReaper();
    }

    /**
     * Show a pre-JavaFX error dialog using Swing.
     * Used only for critical startup failures before JavaFX is available.
     */
    private static void showStartupError(String title, String message) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}
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
        logger.info("Starting {} Application", APP_NAME);

        // Phase 1: Startup Guard (Cold Boot Check)
        // ValVoice must start BEFORE Riot Client to intercept XMPP traffic
        if (isRiotOrValorantRunning()) {
            logger.error("Riot Client or Valorant is already running - cannot start ValVoice");
            showStartupError(APP_NAME + " - Cannot Start",
                "Riot Client or Valorant is already running.\n\n" +
                "ValVoice must be started BEFORE launching Riot Client.\n" +
                "Please close Valorant and Riot Client, then restart ValVoice.");
            System.exit(0);
        }

        // VN-parity: Cold Boot Process Reaper
        // Kill any zombie processes from previous crashed sessions
        // Includes: valvoice-mitm.exe, RiotClientServices.exe, VALORANT-Win64-Shipping.exe
        runProcessReaper();

        // VN-parity: Shutdown Reaper Hook
        // Safety net to kill orphaned processes on exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("[Reaper] Running Shutdown Reaper...");
            String[] processesToKill = {"valvoice-mitm.exe"};
            for (String processName : processesToKill) {
                try {
                    ProcessBuilder pb = new ProcessBuilder("taskkill", "/F", "/IM", processName);
                    pb.redirectErrorStream(true);
                    Process proc = pb.start();
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

        // 4. Launch JavaFX (backend started by ValVoiceController.initialize())
        logger.info("Launching JavaFX Application");
        Application.launch(ValVoiceApplication.class, args);
    }
}
