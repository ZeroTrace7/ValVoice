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

    // MITM proxy process management (external exe stdout reader)
    private static Process mitmProcess;
    private static final ExecutorService mitmIoPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mitm-io");
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
    private static final String XMPP_EXE_NAME_PRIMARY = "valvoice-mitm.exe";

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
     * Send a chat message to XMPP via the MITM proxy process
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
        if (mitmProcess == null || !mitmProcess.isAlive()) {
            logger.warn("Cannot send command: XMPP bridge not running");
            return;
        }
        try {
            OutputStream os = mitmProcess.getOutputStream();
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


    private static void startMitmProxy() {
        // Entry point for starting the MITM proxy
        launchMitmProxy();
    }

    /**
     * Launch the MITM proxy for intercepting Riot XMPP traffic.
     *
     * Flow:
     * 1. Start ConfigMITM HTTP server
     * 2. Start XmppMITM TLS proxy
     * 3. Launch Riot Client with modified config
     * 4. Read decrypted XMPP stanzas from stdout (JSON)
     * 5. Parse messages → Trigger TTS
     *
     * Architecture:
     * Riot Client → ConfigMITM → XmppMITM → Riot Server
     *
     * Data Flow:
     * XMPP → MITM logs → Java Parser → TTS
     */
    private static void launchMitmProxy() {
        if (mitmProcess != null && mitmProcess.isAlive()) {
            logger.warn("MITM proxy process already running");
            return;
        }

        logger.info("Starting MITM proxy (transparent interception approach)...");
        ValVoiceController.updateXmppStatus("Starting...", false);

        // Try standalone .exe first (preferred for production)
        Path workingDir = Paths.get(System.getProperty("user.dir"));
        Path exePrimary = workingDir.resolve(XMPP_EXE_NAME_PRIMARY);
        Path exeCandidate = Files.isRegularFile(exePrimary) ? exePrimary : null;

        if (exeCandidate == null) {
            logger.warn("{} not found; attempting to auto-build...", XMPP_EXE_NAME_PRIMARY);
            Path built = tryBuildMitmExecutable(workingDir);
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
                mitmProcess = pb.start();
                System.setProperty("valvoice.bridgeMode", "external-exe");
                ValVoiceController.updateBridgeModeLabel("external-exe");
                logger.info("Started MITM proxy (mode: external-exe)");
            } catch (IOException e) {
                logger.error("Failed to start MITM proxy .exe: {}", e.getMessage());
                ValVoiceController.updateXmppStatus("Start failed", false);
                return;
            }
        } else {
            // Fallback to Node.js + index.js
            logger.info("MITM .exe not found, trying Node.js fallback...");

            // Find Node.js executable
            String nodeCommand = findNodeExecutable();
            if (nodeCommand == null) {
                logger.error("Node.js not found! Please install Node.js from https://nodejs.org/ or build valvoice-xmpp.exe");
                System.setProperty("valvoice.bridgeMode", "unavailable");
                ValVoiceController.updateXmppStatus("Node.js missing", false);
                return;
            }

            // Find mitm directory
            Path bridgeDir;
            try {
                bridgeDir = getMitmDirectory();
            } catch (IOException e) {
                logger.error("MITM directory not found: {}", e.getMessage());
                System.setProperty("valvoice.bridgeMode", "unavailable");
                ValVoiceController.updateXmppStatus("MITM missing", false);
                return;
            }

            // Start Node.js process
            ProcessBuilder pb = new ProcessBuilder(nodeCommand, "dist/main.js");
            pb.directory(bridgeDir.toFile());
            pb.redirectErrorStream(true);

            try {
                mitmProcess = pb.start();
                System.setProperty("valvoice.bridgeMode", "node-script");
                ValVoiceController.updateBridgeModeLabel("node-script");
                logger.info("Started MITM proxy (mode: node-script) from: {}", bridgeDir);
            } catch (IOException e) {
                logger.error("Failed to start Node.js MITM proxy: {}", e.getMessage());
                ValVoiceController.updateXmppStatus("Start failed", false);
                return;
            }
        }

        // Read output from the MITM proxy process
        mitmIoPool.submit(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(mitmProcess.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.isBlank()) continue;
                    JsonElement parsed;
                    try {
                        parsed = JsonParser.parseString(line);
                        if (!parsed.isJsonObject()) {
                            logger.debug("[MITM raw] {}", line);
                            continue;
                        }
                        JsonObject obj = parsed.getAsJsonObject();
                        String type = obj.has("type") && !obj.get("type").isJsonNull() ? obj.get("type").getAsString() : "";
                        handleMitmEvent(type, obj);
                    } catch (Exception ex) {
                        // Non-JSON bridge output
                        String lower = line.toLowerCase();
                        if (lower.contains("presence")) {
                            logger.debug("[MITM presence] {}", line);
                        } else {
                            logger.info("[MITM log] {}", line);
                        }
                    }
                }
            } catch (IOException e) {
                logger.warn("MITM proxy stdout reader terminating", e);
            } finally {
                logger.info("MITM proxy output closed");
            }
        });

        // Monitor process exit
        mitmIoPool.submit(() -> {
            try {
                int code = mitmProcess.waitFor();
                logger.warn("MITM proxy process exited with code {}", code);
                ValVoiceController.updateXmppStatus("Exited(" + code + ")", false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (mitmProcess != null && mitmProcess.isAlive()) {
                logger.info("Destroying MITM proxy process");
                mitmProcess.destroy();
                try { mitmProcess.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            mitmIoPool.shutdown();
            try {
                if (!mitmIoPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    mitmIoPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                mitmIoPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }, "mitm-shutdown"));
    }

    /**
     * Handle events from the MITM proxy process
     */
    private static void handleMitmEvent(String type, JsonObject obj) {
        switch (type) {
            case "incoming" -> {
                // Raw XML received from Riot server
                if (obj.has("data") && !obj.get("data").isJsonNull()) {
                    String data = obj.get("data").getAsString();
                    logger.info("[MITM:incoming] {}", data);
                }
            }
            case "outgoing" -> {
                // Raw XML sent to Riot server
                if (obj.has("data") && !obj.get("data").isJsonNull()) {
                    String data = obj.get("data").getAsString();
                    logger.info("[MITM:outgoing] {}", data);
                }
            }
            case "open-valorant" -> {
                // Valorant client connected to MITM
                int socketID = obj.has("socketID") ? obj.get("socketID").getAsInt() : 0;
                String host = obj.has("host") ? obj.get("host").getAsString() : "unknown";
                int port = obj.has("port") ? obj.get("port").getAsInt() : 0;
                logger.info("[MITM:open-valorant] socketID={} host={} port={}", socketID, host, port);
            }
            case "open-riot" -> {
                // MITM connected to Riot server
                int socketID = obj.has("socketID") ? obj.get("socketID").getAsInt() : 0;
                logger.info("[MITM:open-riot] socketID={}", socketID);
            }
            case "close-riot" -> {
                // Riot server closed connection
                int socketID = obj.has("socketID") ? obj.get("socketID").getAsInt() : 0;
                logger.info("[MITM:close-riot] socketID={}", socketID);
            }
            case "close-valorant" -> {
                // Valorant client closed connection
                int socketID = obj.has("socketID") ? obj.get("socketID").getAsInt() : 0;
                logger.info("[MITM:close-valorant] socketID={}", socketID);
            }
            case "error" -> {
                // Error event
                int code = obj.has("code") ? obj.get("code").getAsInt() : 0;
                String reason = obj.has("reason") && !obj.get("reason").isJsonNull() ? obj.get("reason").getAsString() : "unknown";
                logger.warn("[MITM:error] code={} reason={}", code, reason);
            }
            default -> {
                // Unknown event type
                logger.debug("[MITM:unknown] {}", obj);
            }
        }
    }

    // Attempt to build valvoice-mitm.exe using the included mitm project.
    // Returns path to built exe if successful, else null.
    private static Path tryBuildMitmExecutable(Path workingDir) {
        try {
            Path mitmDir = workingDir.resolve("mitm");
            Path pkgJson = mitmDir.resolve("package.json");
            if (!Files.isRegularFile(pkgJson)) {
                logger.info("mitm folder not found ({}); skipping auto-build.", mitmDir.toAbsolutePath());
                return null;
            }
            if (!isCommandAvailable("node") || !isCommandAvailable("npm")) {
                logger.info("Node.js or npm not available; cannot auto-build valvoice-mitm.exe.");
                return null;
            }
            logger.info("Auto-building valvoice-mitm.exe (clean cache) ...");
            // Best effort: clear pkg cache before build
            runAndWait(new ProcessBuilder("cmd.exe", "/c", "if exist %LOCALAPPDATA%\\pkg rmdir /s /q %LOCALAPPDATA%\\pkg").directory(workingDir.toFile()), 30, TimeUnit.SECONDS);
            runAndWait(new ProcessBuilder("cmd.exe", "/c", "if exist %USERPROFILE%\\.pkg-cache rmdir /s /q %USERPROFILE%\\.pkg-cache").directory(workingDir.toFile()), 30, TimeUnit.SECONDS);
            // npm install
            int npmInstall = runAndWait(new ProcessBuilder("cmd.exe", "/c", "npm ci").directory(mitmDir.toFile()), 10, TimeUnit.MINUTES);
            if (npmInstall != 0) {
                logger.warn("npm ci failed with exit code {}", npmInstall);
                return null;
            }
            // npm run build:all (compiles TypeScript and packages exe)
            int npmBuild = runAndWait(new ProcessBuilder("cmd.exe", "/c", "npm run build:all").directory(mitmDir.toFile()), 10, TimeUnit.MINUTES);
            if (npmBuild != 0) {
                logger.warn("npm run build:all failed with exit code {}", npmBuild);
                return null;
            }
            Path exe = workingDir.resolve("target").resolve("valvoice-xmpp.exe");
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
     * Locate the mitm directory.
     * Checks multiple possible locations relative to the application.
     * @return Path to mitm directory
     * @throws IOException if mitm directory cannot be found
     */
    private static Path getMitmDirectory() throws IOException {
        // Try multiple possible locations
        List<Path> candidatePaths = new ArrayList<>();

        // 1. Current working directory
        candidatePaths.add(Paths.get(System.getProperty("user.dir"), "mitm"));

        // 2. JAR location (for packaged app)
        try {
            Path jarPath = Paths.get(Main.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
            if (jarPath.toString().endsWith(".jar")) {
                // If running from JAR, mitm should be next to it
                Path appDir = jarPath.getParent();
                candidatePaths.add(appDir.resolve("mitm"));
                // Also try one level up (for jpackage structure)
                if (appDir.getParent() != null) {
                    candidatePaths.add(appDir.getParent().resolve("mitm"));
                }
            }
        } catch (Exception e) {
            logger.debug("Could not determine JAR location", e);
        }

        // 3. Check each candidate
        for (Path candidate : candidatePaths) {
            if (Files.isDirectory(candidate)) {
                Path mainJs = candidate.resolve("dist/main.js");
                if (Files.isRegularFile(mainJs)) {
                    logger.info("Found mitm at: {}", candidate.toAbsolutePath());
                    return candidate;
                }
            }
        }

        // Not found
        throw new IOException("mitm directory not found. Checked: " + candidatePaths);
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
        startMitmProxy();

        logger.info("Launching JavaFX Application");
        Application.launch(ValVoiceApplication.class, args);
    }
}
