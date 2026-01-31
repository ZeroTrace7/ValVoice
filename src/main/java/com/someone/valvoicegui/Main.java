package com.someone.valvoicegui;

import com.someone.valvoicebackend.*;
import javafx.application.Application;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
        Thread t = new Thread(r, "xmpp-mitm-io");
        t.setDaemon(true);
        return t;
    });

    private static final Pattern IQ_ID_PATTERN = Pattern.compile("<iq[^>]*id=([\"'])_xmpp_bind1\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern JID_TAG_PATTERN = Pattern.compile("<jid>(.*?)</jid>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern IQ_START_PATTERN = Pattern.compile("<iq[\\s>]", Pattern.CASE_INSENSITIVE);
    // FIXED Issue 5: More robust pattern that handles attributes with special characters and whitespace
    private static final Pattern MESSAGE_STANZA_PATTERN = Pattern.compile(
        "<message\\s[^>]*>.*?</message>",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final String XMPP_EXE_NAME_PRIMARY = "valvoice-xmpp.exe";

    // Current XMPP room JID for sending messages
    private static volatile String currentRoomJid = null;

    // Track active MUC rooms to help with message classification
    private static final java.util.Set<String> activeRooms = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

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
                        MessageType.WARNING_MESSAGE
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

    /**
     * Send a chat message to XMPP via the bridge process
     * @param toJid destination JID (room or user)
     * @param body message body
     * @param msgType message type (groupchat for MUC, chat for whisper)
     */
    public static void sendMessageToXmpp(String toJid, String body, String msgType) {
        sendCommandToXmpp(String.format(
            "{\"type\":\"send\",\"to\":\"%s\",\"body\":\"%s\",\"msgType\":\"%s\"}",
            escapeJson(toJid),
            escapeJson(body),
            escapeJson(msgType)
        ));
    }

    /**
     * Send a raw command to the XMPP bridge process
     * @param jsonCommand JSON command string
     */
    private static void sendCommandToXmpp(String jsonCommand) {
        if (xmppNodeProcess == null || !xmppNodeProcess.isAlive()) {
            logger.warn("Cannot send command: XMPP bridge not running");
            return;
        }
        try {
            OutputStream os = xmppNodeProcess.getOutputStream();
            os.write((jsonCommand + "\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
            logger.debug("Sent command to XMPP bridge: {}", jsonCommand);
        } catch (IOException e) {
            logger.error("Failed to send command to XMPP bridge", e);
        }
    }

    /**
     * Escape special characters for JSON string
     */
    private static String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }


    private static void startXmppNodeProcess() {
        // Use direct XMPP bridge approach (Node.js or .exe)
        // This connects directly to Riot's XMPP server using credentials from local API
        startXmppBridge();
    }

    /**
     * Start the XMPP bridge for connecting to Riot XMPP server.
     * This approach:
     * 1. Reads lockfile to get local API credentials
     * 2. Queries /chat/v1/session for XMPP credentials
     * 3. Connects directly to Riot XMPP server (NOT as a proxy)
     * 4. Reads presence stanzas for game state
     *

     */
    private static void startXmppBridge() {
        if (xmppNodeProcess != null && xmppNodeProcess.isAlive()) {
            logger.warn("XMPP bridge process already running");
            return;
        }

        logger.info("Starting XMPP bridge (direct client approach)...");
        ValVoiceController.updateXmppStatus("Starting...", false);

        // Try standalone .exe first (preferred for production)
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

        // If .exe exists, use it
        if (exeCandidate != null && Files.isReadable(exeCandidate)) {
            ProcessBuilder pb = new ProcessBuilder(exeCandidate.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            pb.directory(workingDir.toFile());
            try {
                xmppNodeProcess = pb.start();
                System.setProperty("valvoice.bridgeMode", "external-exe");
                ValVoiceController.updateBridgeModeLabel("external-exe");
                logger.info("Started XMPP bridge (mode: external-exe)");
            } catch (IOException e) {
                logger.error("Failed to start XMPP bridge .exe: {}", e.getMessage());
                ValVoiceController.updateXmppStatus("Start failed", false);
                return;
            }
        } else {
            // Fallback to Node.js + index.js
            logger.info("XMPP .exe not found, trying Node.js fallback...");

            // Find Node.js executable
            String nodeCommand = findNodeExecutable();
            if (nodeCommand == null) {
                logger.error("Node.js not found! Please install Node.js from https://nodejs.org/ or build valvoice-xmpp.exe");
                System.setProperty("valvoice.bridgeMode", "unavailable");
                ValVoiceController.updateXmppStatus("Node.js missing", false);
                return;
            }

            // Find bridge directory
            Path bridgeDir;
            try {
                bridgeDir = getBridgeDirectory();
            } catch (IOException e) {
                logger.error("XMPP bridge directory not found: {}", e.getMessage());
                System.setProperty("valvoice.bridgeMode", "unavailable");
                ValVoiceController.updateXmppStatus("Bridge missing", false);
                return;
            }

            // Start Node.js process
            ProcessBuilder pb = new ProcessBuilder(nodeCommand, "index.js");
            pb.directory(bridgeDir.toFile());
            pb.redirectErrorStream(true);

            try {
                xmppNodeProcess = pb.start();
                System.setProperty("valvoice.bridgeMode", "node-script");
                ValVoiceController.updateBridgeModeLabel("node-script");
                logger.info("Started XMPP bridge (mode: node-script) from: {}", bridgeDir);
            } catch (IOException e) {
                logger.error("Failed to start Node.js XMPP bridge: {}", e.getMessage());
                ValVoiceController.updateXmppStatus("Start failed", false);
                return;
            }
        }

        // Read output from the XMPP bridge process
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
                        handleBridgeEvent(type, obj);
                    } catch (Exception ex) {
                        // Non-JSON bridge output
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
                logger.info("XMPP bridge output closed");
            }
        });

        // Monitor process exit
        xmppIoPool.submit(() -> {
            try {
                int code = xmppNodeProcess.waitFor();
                logger.warn("XMPP bridge process exited with code {}", code);
                ValVoiceController.updateXmppStatus("Exited(" + code + ")", false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (xmppNodeProcess != null && xmppNodeProcess.isAlive()) {
                logger.info("Destroying XMPP bridge process");
                xmppNodeProcess.destroy();
                try { xmppNodeProcess.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
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

    /**
     * Handle events from the XMPP bridge process
     */
    private static void handleBridgeEvent(String type, JsonObject obj) {
        switch (type) {
            case "incoming" -> handleIncomingStanza(obj);
            case "outgoing" -> {
                // XMPP stanza sent to server (for debugging)
                if (obj.has("data") && !obj.get("data").isJsonNull()) {
                    String data = obj.get("data").getAsString();
                    logger.debug("[XmppBridge:outgoing] {}", abbreviateXml(data));
                }
            }
            case "open-v" -> {
                // Connection opened to Riot XMPP server
                String host = obj.has("host") ? obj.get("host").getAsString() : "unknown";
                int port = obj.has("port") ? obj.get("port").getAsInt() : 0;
                logger.info("[XmppBridge] Connecting to {}:{}", host, port);
                ValVoiceController.updateXmppStatus("Connecting...", false);
            }
            case "open-riot" -> {
                // Riot server acknowledged connection
                logger.info("[XmppBridge] Connection established");
                ValVoiceController.updateXmppStatus("Connected", true);
            }
            case "error" -> {
                String err = "(unknown)";
                if (obj.has("error") && !obj.get("error").isJsonNull()) {
                    err = obj.get("error").getAsString();
                } else if (obj.has("reason") && !obj.get("reason").isJsonNull()) {
                    err = obj.get("reason").getAsString();
                }
                if (obj.has("code") && !obj.get("code").isJsonNull()) {
                    err = "(" + obj.get("code").getAsInt() + ") " + err;
                }
                logger.warn("XMPP error event: {}", err);
                ValVoiceController.updateXmppStatus("Error", false);
            }
            case "debug", "info" -> {
                String msg = obj.has("message") && !obj.get("message").isJsonNull() ? obj.get("message").getAsString() : "";
                logger.debug("[XmppBridge:{}] {}", type, msg);
                // Map key phases to UI status
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
            case "shutdown" -> {
                logger.debug("[XmppBridge:shutdown] {}", obj);
                ValVoiceController.updateXmppStatus("Stopped", false);
            }
            case "message-handlers-ready" -> {
                logger.info("[XmppBridge] ✅ Message handlers registered and ready");
            }
            case "socket-listener-active" -> {
                logger.info("[XmppBridge] ✅ Socket listener active - ready to receive stanzas");
            }
            case "room-joined" -> {
                String room = obj.has("room") ? obj.get("room").getAsString() : null;
                String roomType = obj.has("roomType") ? obj.get("roomType").getAsString() : "UNKNOWN";
                if (room != null) {
                    currentRoomJid = room;
                    activeRooms.add(room);
                    logger.info("[XmppBridge] Joined room [{}]: {}", roomType, room);
                }
            }
            case "room-left" -> {
                String room = obj.has("room") ? obj.get("room").getAsString() : null;
                String roomType = obj.has("roomType") ? obj.get("roomType").getAsString() : "UNKNOWN";
                if (room != null) {
                    activeRooms.remove(room);
                    if (room.equals(currentRoomJid)) {
                        currentRoomJid = null;
                    }
                    logger.info("[XmppBridge] Left room [{}]: {}", roomType, room);
                }
            }
            case "groupchat-received" -> {
                // Explicit logging for groupchat stanzas received by the bridge
                String roomType = obj.has("roomType") ? obj.get("roomType").getAsString() : "UNKNOWN";
                String sender = obj.has("sender") ? obj.get("sender").getAsString() : "unknown";
                String body = obj.has("body") ? obj.get("body").getAsString() : "";
                logger.info("════════════════════════════════════════");
                logger.info("📥 GROUPCHAT RECEIVED BY JAVA");
                logger.info("   Room Type: {}", roomType);
                logger.info("   Sender: {}", sender.length() > 12 ? sender.substring(0, 12) + "..." : sender);
                logger.info("   Body: \"{}\"", body.length() > 50 ? body.substring(0, 47) + "..." : body);
                logger.info("════════════════════════════════════════");
            }
            case "message-detected" -> {
                // Structured message detection event from bridge
                String roomType = obj.has("roomType") ? obj.get("roomType").getAsString() : "UNKNOWN";
                String messageType = obj.has("messageType") ? obj.get("messageType").getAsString() : "unknown";
                boolean isMuc = obj.has("isMuc") && obj.get("isMuc").getAsBoolean();
                logger.debug("[XmppBridge:message-detected] type={} roomType={} isMuc={}", messageType, roomType, isMuc);
            }
            default -> logger.debug("[XmppBridge:?] {}", obj);
        }
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

    /**
     * Find Node.js executable on the system.
     * Tries multiple common installation locations.
     * @return Path to node executable, or null if not found
     */
    private static String findNodeExecutable() {
        // Try common locations
        String[] possiblePaths = {
            "node",                                                        // System PATH
            "node.exe",                                                    // Windows
            "C:\\Program Files\\nodejs\\node.exe",                         // Default Windows install
            System.getenv("PROGRAMFILES") + "\\nodejs\\node.exe",         // Program Files
            System.getenv("ProgramFiles(x86)") + "\\nodejs\\node.exe",    // Program Files (x86)
            System.getenv("LOCALAPPDATA") + "\\Programs\\nodejs\\node.exe" // Local install
        };

        for (String path : possiblePaths) {
            if (path == null) continue; // Skip if env var is null
            try {
                Process process = new ProcessBuilder(path, "--version")
                    .redirectErrorStream(true)
                    .start();
                boolean finished = process.waitFor(3, TimeUnit.SECONDS);
                if (finished && process.exitValue() == 0) {
                    logger.info("Found Node.js at: {}", path);
                    return path;
                }
                process.destroyForcibly();
            } catch (Exception ignored) {
                // Try next path
            }
        }

        logger.warn("Node.js not found in any common location");
        return null;
    }

    /**
     * Locate the xmpp-bridge directory.
     * Checks multiple possible locations relative to the application.
     * @return Path to xmpp-bridge directory
     * @throws IOException if bridge directory cannot be found
     */
    private static Path getBridgeDirectory() throws IOException {
        // Try multiple possible locations
        List<Path> candidatePaths = new ArrayList<>();

        // 1. Current working directory
        candidatePaths.add(Paths.get(System.getProperty("user.dir"), "xmpp-bridge"));

        // 2. JAR location (for packaged app)
        try {
            Path jarPath = Paths.get(Main.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
            if (jarPath.toString().endsWith(".jar")) {
                // If running from JAR, bridge should be next to it
                Path appDir = jarPath.getParent();
                candidatePaths.add(appDir.resolve("xmpp-bridge"));
                // Also try one level up (for jpackage structure)
                if (appDir.getParent() != null) {
                    candidatePaths.add(appDir.getParent().resolve("xmpp-bridge"));
                }
            }
        } catch (Exception e) {
            logger.debug("Could not determine JAR location", e);
        }

        // 3. Check each candidate
        for (Path candidate : candidatePaths) {
            if (Files.isDirectory(candidate)) {
                Path indexJs = candidate.resolve("index.js");
                if (Files.isRegularFile(indexJs)) {
                    logger.info("Found xmpp-bridge at: {}", candidate.toAbsolutePath());
                    return candidate;
                }
            }
        }

        // Not found
        throw new IOException("xmpp-bridge directory not found. Checked: " + candidatePaths);
    }

    private static void handleIncomingStanza(JsonObject obj) {
        if (!obj.has("data") || obj.get("data").isJsonNull()) return;
        String xml = obj.get("data").getAsString();
        if (xml == null || xml.isBlank()) return;

        String xmlLower = xml.toLowerCase();

        // === COMPREHENSIVE DEBUG LOGGING FOR ALL MESSAGES ===
        if (xmlLower.contains("<message")) {
            logger.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            logger.info("📨 RAW MESSAGE XML RECEIVED:");
            // Show full XML for messages - this is critical for debugging
            logger.info(xml);

            Pattern fromPattern = Pattern.compile("from=['\"]([^'\"]+)['\"]");
            Pattern typePattern = Pattern.compile("type=['\"]([^'\"]+)['\"]");
            Pattern jidPattern = Pattern.compile("jid=['\"]([^'\"]+)['\"]");
            Pattern bodyPattern = Pattern.compile("<body>([\\s\\S]*?)</body>");

            Matcher fromM = fromPattern.matcher(xml);
            Matcher typeM = typePattern.matcher(xml);
            Matcher jidM = jidPattern.matcher(xml);
            Matcher bodyM = bodyPattern.matcher(xml);

            if (jidM.find()) {
                logger.info("JID: {}", jidM.group(1));
            }

            if (fromM.find()) {
                String from = fromM.group(1);
                logger.info("FROM: {}", from);

                // Check which MUC type
                boolean isAriesParty = from.contains("@ares-parties");
                boolean isAresPregame = from.contains("@ares-pregame");
                boolean isAresCoregame = from.contains("@ares-coregame");

                logger.info("  - Contains @ares-parties: {}", isAriesParty);
                logger.info("  - Contains @ares-pregame: {}", isAresPregame);
                logger.info("  - Contains @ares-coregame: {}", isAresCoregame);
                logger.info("  - Is MUC message: {}", (isAriesParty || isAresPregame || isAresCoregame));

                // Extract and log server type
                if (from.contains("@")) {
                    String[] parts = from.split("@");
                    if (parts.length > 1) {
                        String domain = parts[1];
                        if (domain.contains("/")) {
                            domain = domain.substring(0, domain.indexOf("/"));
                        }
                        String serverType = domain.split("\\.")[0];
                        logger.info("  - Extracted serverType: '{}'", serverType);
                        logger.info("  - Full domain: '{}'", parts[1]);
                    }
                }
            }

            if (typeM.find()) {
                String msgTypeAttr = typeM.group(1);
                logger.info("TYPE: {}", msgTypeAttr);
                logger.info("  - Is 'groupchat': {}", "groupchat".equalsIgnoreCase(msgTypeAttr));
                logger.info("  - Is 'chat': {}", "chat".equalsIgnoreCase(msgTypeAttr));
            } else {
                logger.info("TYPE: (not present)");
            }

            if (bodyM.find()) {
                String bodyPreview = bodyM.group(1);
                if (bodyPreview.length() > 100) {
                    bodyPreview = bodyPreview.substring(0, 97) + "...";
                }
                logger.info("BODY: {}", bodyPreview);
            } else {
                logger.info("BODY: (not present)");
            }

            logger.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        }

        // Skip non-message stanzas early
        if (!xmlLower.contains("<message") || !xmlLower.contains("<body>")) {
            // Check for IQ stanzas (self ID detection)
            if (IQ_START_PATTERN.matcher(xml).find()) {
                detectSelfIdFromIq(xml);
            }
            return;
        }

        try {
            // Extract individual message stanzas
            Matcher messageMatcher = MESSAGE_STANZA_PATTERN.matcher(xml);
            int messageCount = 0;

            while (messageMatcher.find()) {
                String singleMessageXml = messageMatcher.group();
                messageCount++;

                if (singleMessageXml.toLowerCase().contains("<body>")) {
                    try {
                        // Parse message using ValorantNarrator's simple domain-first approach
                        Message msg = new Message(singleMessageXml);
                        logger.debug("Parsed message: type={}, from={}", msg.getMessageType(), msg.getUserId());
                        ChatDataHandler.getInstance().message(msg);
                    } catch (Exception ex) {
                        logger.warn("Failed to parse message: {}", ex.getMessage());
                    }
                }
            }

            // Fallback: try parsing whole XML if pattern didn't match
            if (messageCount == 0) {
                try {
                    Message msg = new Message(xml);
                    if (msg.getContent() != null && !msg.getContent().isBlank()) {
                        ChatDataHandler.getInstance().message(msg);
                    }
                } catch (Exception ex) {
                    logger.debug("Fallback parse failed: {}", ex.getMessage());
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
        try (InputStream inputStream = Main.class.getResourceAsStream("/com/someone/valvoicegui/config.properties")) {
            if (inputStream == null) {
                throw new FileNotFoundException("config.properties not found on classpath (expected at /com/someone/valvoicegui/config.properties)");
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
                    MessageType.ERROR_MESSAGE
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
