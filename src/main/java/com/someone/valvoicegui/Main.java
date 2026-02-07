package com.someone.valvoicegui;

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

    private static void cleanupStaleMitmProcesses() {
        try {
            logger.info("Cleaning up stale MITM processes (best-effort)...");
            ProcessBuilder pb = new ProcessBuilder("taskkill", "/F", "/IM", "valvoice-mitm.exe");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            // Consume output to prevent blocking
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.debug("taskkill: {}", line);
                }
            }
            int exitCode = proc.waitFor();
            if (exitCode == 0) {
                logger.info("Stale MITM processes terminated successfully");
            } else {
                // Exit code 128 = no matching processes found (normal case)
                logger.debug("taskkill exit code: {} (128 = no processes found)", exitCode);
            }
        } catch (Exception e) {
            logger.warn("Failed to cleanup stale MITM processes (non-fatal): {}", e.getMessage());
        }
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

        // Phase 1: Stale MITM Process Cleanup
        // Kill any zombie valvoice-mitm.exe processes from previous crashed sessions
        cleanupStaleMitmProcesses();

        // 1. Bootstrap config directory
        try {
            Files.createDirectories(Paths.get(CONFIG_DIR));
            logger.info("Config directory: {}", CONFIG_DIR);
        } catch (IOException e) {
            logger.error("Could not create config directory", e);
        }

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
