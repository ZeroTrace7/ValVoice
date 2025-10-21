package com.someone.valvoice;

import javafx.application.Application;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static final String APP_NAME = "ValVoice";
    public static final String CONFIG_DIR = Paths.get(System.getenv("APPDATA"), APP_NAME).toString();
    public static final String LOCK_FILE_NAME = "lockFile";
    public static final String LOCK_FILE = Paths.get(CONFIG_DIR, LOCK_FILE_NAME).toString();
    public static double currentVersion;
    private static Properties properties;

    // Node.js XMPP process management (external exe stdout reader)
    private static Process xmppNodeProcess;
    private static final ExecutorService xmppIoPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "xmpp-node-io");
        t.setDaemon(true);
        return t;
    });

    private static final Pattern IQ_ID_PATTERN = Pattern.compile("<iq[^>]*id=([\"'])_xmpp_bind1\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern JID_TAG_PATTERN = Pattern.compile("<jid>(.*?)</jid>", Pattern.CASE_INSENSITIVE);
    private static final Pattern MESSAGE_START_PATTERN = Pattern.compile("^<message", Pattern.CASE_INSENSITIVE);
    private static final Pattern IQ_START_PATTERN = Pattern.compile("^<iq", Pattern.CASE_INSENSITIVE);
    private static final String XMPP_EXE_NAME_PRIMARY = "valvoice-xmpp.exe";

    // Store lock file resources to prevent leak
    private static RandomAccessFile lockFileAccess;
    private static FileLock instanceLock;

    private static boolean lockInstance() {
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
                ValVoiceApplication.showPreStartupDialog(
                        APP_NAME,
                        "Another instance of this application is already running!",
                        MessageType.WARNING
                );
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
        return true;
    }

    private static void startXmppNodeProcess() {
        if (xmppNodeProcess != null && xmppNodeProcess.isAlive()) {
            logger.warn("Xmpp-Node process already running");
            return;
        }

        Path workingDir = Paths.get(System.getProperty("user.dir"));
        Path exePrimary = workingDir.resolve(XMPP_EXE_NAME_PRIMARY);
        Path exeCandidate = Files.isRegularFile(exePrimary) ? exePrimary : null;

        if (exeCandidate == null) {
            logger.warn("{} not found; attempting to auto-build...", XMPP_EXE_NAME_PRIMARY);
            Path built = tryBuildBridgeExecutable(workingDir);
            if (built != null && Files.isRegularFile(built)) {
                exeCandidate = built;
            }
        }

        if (exeCandidate == null || !Files.isReadable(exeCandidate)) {
            logger.error("Cannot start XMPP bridge: missing {} and Node.js fallback is disabled for production.", XMPP_EXE_NAME_PRIMARY);
            System.setProperty("valvoice.bridgeMode", "unavailable");
            ValVoiceController.updateXmppStatus("Unavailable", false);
            return;
        }

        ProcessBuilder pb = new ProcessBuilder(exeCandidate.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        pb.directory(workingDir.toFile());
        try {
            xmppNodeProcess = pb.start();
            System.setProperty("valvoice.bridgeMode", "external-exe");
            ValVoiceController.updateBridgeModeLabel("external-exe");
            logger.info("Started XMPP bridge (mode: external-exe)");
        } catch (IOException e) {
            logger.error("Failed to start XMPP bridge process", e);
            ValVoiceController.updateXmppStatus("Start failed", false);
            return;
        }

        xmppIoPool.submit(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(xmppNodeProcess.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.isBlank()) continue;
                    JsonElement parsed;
                    try {
                        parsed = JsonParser.parseString(line);
                        if (!parsed.isJsonObject()) {
                            logger.debug("[XmppBridge raw] {}", line);
                            continue;
                        }
                        JsonObject obj = parsed.getAsJsonObject();
                        String type = obj.has("type") && !obj.get("type").isJsonNull() ? obj.get("type").getAsString() : "";
                        switch (type) {
                            case "incoming" -> handleIncomingStanza(obj);
                            case "outgoing" -> {
                                // XMPP stanza sent to server (for debugging)
                                if (obj.has("data") && !obj.get("data").isJsonNull()) {
                                    String data = obj.get("data").getAsString();
                                    logger.debug("[XmppBridge:outgoing] {}", abbreviateXml(data));
                                }
                            }
                            case "open-valorant" -> {
                                // Connection opened to Riot XMPP server
                                String host = obj.has("host") ? obj.get("host").getAsString() : "unknown";
                                int port = obj.has("port") ? obj.get("port").getAsInt() : 0;
                                logger.info("[XmppBridge] Connecting to {}:{}", host, port);
                                ValVoiceController.updateXmppStatus("Connecting...", false);
                            }
                            case "open-riot" -> {
                                // Riot server acknowledged connection
                                logger.info("[XmppBridge] Connection established, authenticating...");
                                ValVoiceController.updateXmppStatus("Connected", true);
                            }
                            case "error" -> {
                                // Support both "error" and "reason" fields for compatibility
                                String err = "(unknown)";
                                if (obj.has("error") && !obj.get("error").isJsonNull()) {
                                    err = obj.get("error").getAsString();
                                } else if (obj.has("reason") && !obj.get("reason").isJsonNull()) {
                                    err = obj.get("reason").getAsString();
                                }
                                // Include error code if present
                                if (obj.has("code") && !obj.get("code").isJsonNull()) {
                                    err = "(" + obj.get("code").getAsInt() + ") " + err;
                                }
                                logger.warn("XMPP error event: {}", err);
                                ValVoiceController.updateXmppStatus("Error", false);
                            }
                            case "debug" -> {
                                String msg = obj.has("message") && !obj.get("message").isJsonNull() ? obj.get("message").getAsString() : "";
                                logger.debug("[XmppBridge:debug] {}", msg);

                                // Mirror the info -> UI mapping so important debug messages also update status
                                if (msg.contains("Connected to Riot XMPP server")) {
                                    ValVoiceController.updateXmppStatus("Connected", true);
                                } else if (msg.contains("Connected to XMPP server")) {
                                    ValVoiceController.updateXmppStatus("TLS OK", true);
                                } else if (msg.contains("Getting authentication credentials")) {
                                    ValVoiceController.updateXmppStatus("Authenticating...", false);
                                } else if (msg.contains("Fetching PAS token") || msg.toLowerCase().contains("pas token")) {
                                    ValVoiceController.updateXmppStatus("Auth: PAS token", false);
                                } else if (msg.contains("Fetching entitlements")) {
                                    ValVoiceController.updateXmppStatus("Auth: entitlements", false);
                                } else if (msg.contains("Connecting to XMPP server")) {
                                    ValVoiceController.updateXmppStatus("Connecting...", false);
                                } else if (msg.contains("XMPP connection closed")) {
                                    ValVoiceController.updateXmppStatus("Closed", false);
                                } else if (msg.contains("Reconnecting")) {
                                    ValVoiceController.updateXmppStatus("Reconnecting...", false);
                                }
                            }
                            case "startup" -> {
                                logger.debug("[XmppBridge:startup] {}", obj);
                                String mode = System.getProperty("valvoice.bridgeMode", "external-exe");
                                ValVoiceController.updateBridgeModeLabel(mode);
                                ValVoiceController.updateXmppStatus("Starting...", false);
                            }
                            case "info" -> {
                                String msg = obj.has("message") && !obj.get("message").isJsonNull() ? obj.get("message").getAsString() : "";
                                logger.debug("[XmppBridge:info] {}", msg);
                                // Map key phases to UI status
                                if (msg.contains("Connected to Riot XMPP server")) {
                                    ValVoiceController.updateXmppStatus("Connected", true);
                                } else if (msg.contains("Connected to XMPP server")) {
                                    ValVoiceController.updateXmppStatus("TLS OK", true);
                                } else if (msg.contains("Getting authentication credentials")) {
                                    ValVoiceController.updateXmppStatus("Authenticating...", false);
                                } else if (msg.contains("Fetching PAS token")) {
                                    ValVoiceController.updateXmppStatus("Auth: PAS token", false);
                                } else if (msg.contains("Fetching entitlements")) {
                                    ValVoiceController.updateXmppStatus("Auth: entitlements", false);
                                } else if (msg.contains("Connecting to XMPP server")) {
                                    ValVoiceController.updateXmppStatus("Connecting...", false);
                                } else if (msg.contains("XMPP connection closed")) {
                                    ValVoiceController.updateXmppStatus("Closed", false);
                                } else if (msg.contains("Reconnecting")) {
                                    ValVoiceController.updateXmppStatus("Reconnecting...", false);
                                }
                            }
                            case "shutdown" -> {
                                logger.debug("[XmppBridge:shutdown] {}", obj);
                                ValVoiceController.updateXmppStatus("Stopped", false);
                            }
                            default -> logger.debug("[XmppBridge:?] {}", obj);
                        }
                    } catch (Exception ex) {
                        // Non-JSON bridge output (often presence notifications) can be noisy; lower to DEBUG
                        String lower = line.toLowerCase();
                        if (lower.contains("presence")) {
                            logger.debug("[XmppBridge presence] {}", line);
                        } else {
                            logger.info("[XmppBridge log] {}", line);
                        }
                    }
                }
            } catch (IOException e) {
                logger.warn("XMPP bridge stdout reader terminating", e);
            } finally {
                logger.info("XMPP bridge output closed (external-exe)");
            }
        });

        xmppIoPool.submit(() -> {
            try {
                int code = xmppNodeProcess.waitFor();
                logger.warn("XMPP bridge process exited with code {}", code);
                ValVoiceController.updateXmppStatus("Exited(" + code + ")", false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (xmppNodeProcess != null && xmppNodeProcess.isAlive()) {
                logger.info("Destroying XMPP bridge process");
                xmppNodeProcess.destroy();
                try { xmppNodeProcess.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            // Shutdown executor service to prevent thread leak
            xmppIoPool.shutdown();
            try {
                if (!xmppIoPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    xmppIoPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                xmppIoPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }, "xmpp-bridge-shutdown"));
    }

    // Attempt to build valvoice-xmpp.exe using the included xmpp-bridge project.
    // Returns path to built exe if successful, else null.
    private static Path tryBuildBridgeExecutable(Path workingDir) {
        try {
            Path bridgeDir = workingDir.resolve("xmpp-bridge");
            Path pkgJson = bridgeDir.resolve("package.json");
            if (!Files.isRegularFile(pkgJson)) {
                logger.info("xmpp-bridge folder not found ({}); skipping auto-build.", bridgeDir.toAbsolutePath());
                return null;
            }
            if (!isCommandAvailable("node") || !isCommandAvailable("npm")) {
                logger.info("Node.js or npm not available; cannot auto-build valvoice-xmpp.exe.");
                return null;
            }
            logger.info("Auto-building valvoice-xmpp.exe (clean cache) ...");
            // Best effort: clear pkg cache before build
            runAndWait(new ProcessBuilder("cmd.exe", "/c", "if exist %LOCALAPPDATA%\\pkg rmdir /s /q %LOCALAPPDATA%\\pkg").directory(workingDir.toFile()), 30, TimeUnit.SECONDS);
            runAndWait(new ProcessBuilder("cmd.exe", "/c", "if exist %USERPROFILE%\\.pkg-cache rmdir /s /q %USERPROFILE%\\.pkg-cache").directory(workingDir.toFile()), 30, TimeUnit.SECONDS);
            // npm install
            int npmInstall = runAndWait(new ProcessBuilder("cmd.exe", "/c", "npm ci").directory(bridgeDir.toFile()), 10, TimeUnit.MINUTES);
            if (npmInstall != 0) {
                logger.warn("npm ci failed with exit code {}", npmInstall);
                return null;
            }
            // npm run build:exe
            int npmBuild = runAndWait(new ProcessBuilder("cmd.exe", "/c", "npm run build:exe").directory(bridgeDir.toFile()), 10, TimeUnit.MINUTES);
            if (npmBuild != 0) {
                logger.warn("npm run build:exe failed with exit code {}", npmBuild);
                return null;
            }
            Path exe = workingDir.resolve(XMPP_EXE_NAME_PRIMARY);
            if (Files.isRegularFile(exe)) {
                logger.info("Successfully built {}", exe.toAbsolutePath());
                return exe;
            } else {
                logger.warn("Auto-build completed but {} not found", exe.toAbsolutePath());
                return null;
            }
        } catch (Exception e) {
            logger.warn("Auto-build of valvoice-xmpp.exe failed", e);
            return null;
        }
    }

    private static boolean isCommandAvailable(String command) {
        try {
            Process p = new ProcessBuilder(command, "--version").redirectErrorStream(true).start();
            boolean ok = p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
            if (!ok) p.destroyForcibly();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    private static int runAndWait(ProcessBuilder pb, long timeout, TimeUnit unit) throws IOException, InterruptedException {
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        ExecutorService logExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "proc-log");
            t.setDaemon(true);
            return t;
        });
        try {
            logExecutor.submit(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        logger.info("[bridge-build] {}", line);
                    }
                } catch (IOException ignored) {}
            });

            if (!proc.waitFor(timeout, unit)) {
                proc.destroyForcibly();
                logger.warn("Process timed out: {}", String.join(" ", pb.command()));
                return -1;
            }
            return proc.exitValue();
        } finally {
            logExecutor.shutdown();
            try {
                if (!logExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    logExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                logExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void handleIncomingStanza(JsonObject obj) {
        if (!obj.has("data") || obj.get("data").isJsonNull()) return;
        String xml = obj.get("data").getAsString();
        if (xml == null || xml.isBlank()) return;

        // Debug: Log ALL incoming stanzas to identify party/team messages
        String xmlLower = xml.toLowerCase();
        if (xmlLower.contains("<message") && !xmlLower.contains("<presence")) {
            logger.info("📨 RAW MESSAGE STANZA: {}", abbreviateXml(xml));
        }

        try {
            if (MESSAGE_START_PATTERN.matcher(xml).find()) {
                Message msg = new Message(xml);
                logger.info("Received message: {}", msg);
                ChatDataHandler.getInstance().message(msg);
            } else if (IQ_START_PATTERN.matcher(xml).find()) {
                detectSelfIdFromIq(xml);
            } else if (xmlLower.contains("<message")) {
                // Message stanza that didn't match pattern - try to parse it anyway
                logger.warn("⚠️ Message stanza didn't match pattern, attempting parse anyway...");
                try {
                    Message msg = new Message(xml);
                    logger.info("✓ Successfully parsed non-standard message: {}", msg);
                    ChatDataHandler.getInstance().message(msg);
                } catch (Exception ex) {
                    logger.warn("Failed to parse non-standard message stanza", ex);
                }
            } else {
                // presence/other stanzas ignored (but log if it looks message-like)
                if (xmlLower.contains("body") || xmlLower.contains("chat")) {
                    logger.debug("Ignored stanza with body/chat: {}", abbreviateXml(xml));
                }
            }
        } catch (Exception e) {
            logger.debug("Failed handling incoming stanza", e);
        }
    }

    private static void detectSelfIdFromIq(String xml) {
        try {
            Matcher idMatcher = IQ_ID_PATTERN.matcher(xml);
            if (!idMatcher.find()) return; // not the bind response
            Matcher jidMatcher = JID_TAG_PATTERN.matcher(xml);
            if (jidMatcher.find()) {
                String fullJid = jidMatcher.group(1);
                if (fullJid != null && fullJid.contains("@")) {
                    String userPart = fullJid.substring(0, fullJid.indexOf('@'));
                    if (!userPart.isBlank()) {
                        ChatDataHandler.getInstance().updateSelfId(userPart);
                        logger.info("Self ID (bind) detected: {}", userPart);
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to parse bind iq for self ID", e);
        }
    }

    /**
     * Abbreviates XML strings for logging to avoid excessive output
     */
    private static String abbreviateXml(String xml) {
        if (xml == null) return null;
        if (xml.length() <= 200) return xml;
        return xml.substring(0, 197) + "...";
    }

    public static void main(String[] args) {
        logger.info("Starting {} Application", APP_NAME);
        try (InputStream inputStream = Main.class.getResourceAsStream("config.properties")) {
            if (inputStream == null) {
                throw new FileNotFoundException("config.properties not found on classpath (expected in same package)");
            }
            properties = new Properties();
            properties.load(inputStream);
            currentVersion = Double.parseDouble(properties.getProperty("version", "0.0"));
            logger.info("Version: {}", currentVersion);
            logger.info("Build date: {}", properties.getProperty("buildTimestamp", "unknown"));
        } catch (IOException | NumberFormatException e) {
            logger.error("CRITICAL: Could not load config properties!", e);
            ValVoiceApplication.showPreStartupDialog(
                    "Configuration Error",
                    "Could not load application configuration. Please reinstall.",
                    MessageType.ERROR
            );
            System.exit(-1);
        }

        try {
            Files.createDirectories(Paths.get(CONFIG_DIR));
            logger.info("Config directory: {}", CONFIG_DIR);
        } catch (IOException e) {
            logger.error("Could not create config directory", e);
        }

        lockInstance();
        startXmppNodeProcess();

        logger.info("Launching JavaFX Application");
        Application.launch(ValVoiceApplication.class, args);
    }
}
