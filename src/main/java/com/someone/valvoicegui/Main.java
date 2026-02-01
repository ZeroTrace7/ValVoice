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
    private static volatile boolean mitmFatalError = false;
    private static volatile String mitmFatalReason = null;
    private static final ExecutorService mitmIoPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mitm-io");
        t.setDaemon(true);
        return t;
    });


    // Pattern for extracting message stanzas from XMPP XML
    private static final Pattern MESSAGE_STANZA_PATTERN = Pattern.compile(
        "<message\\s[^>]*>.*?</message>",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final String XMPP_EXE_NAME_PRIMARY = "valvoice-mitm.exe";

    // Store lock file resources to prevent leak (application instance lock, not Riot lockfile)
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

        logger.info("Starting MITM proxy...");
        ValVoiceController.updateXmppStatus("Starting...", false);

        // Find valvoice-mitm.exe
        Path workingDir = Paths.get(System.getProperty("user.dir"));
        Path exePrimary = workingDir.resolve(XMPP_EXE_NAME_PRIMARY);
        Path exeCandidate = Files.isRegularFile(exePrimary) ? exePrimary : null;

        // Also check mitm/ subdirectory
        if (exeCandidate == null) {
            Path exeInMitm = workingDir.resolve("mitm").resolve(XMPP_EXE_NAME_PRIMARY);
            if (Files.isRegularFile(exeInMitm)) {
                exeCandidate = exeInMitm;
            }
        }

        if (exeCandidate == null || !Files.isReadable(exeCandidate)) {
            logger.error("FATAL: {} not found! ValVoice cannot function without the MITM proxy.", XMPP_EXE_NAME_PRIMARY);
            logger.error("Please ensure valvoice-mitm.exe is present in the application directory or mitm/ subdirectory.");
            ValVoiceController.updateXmppStatus("MITM exe missing", false);
            System.exit(1);
        }

        // Launch the exe
        // Observer-only: Java reads MITM stdout JSON, never writes to stdin
        ProcessBuilder pb = new ProcessBuilder(exeCandidate.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        pb.directory(workingDir.toFile());
        try {
            mitmProcess = pb.start();
            System.setProperty("valvoice.bridgeMode", "external-exe");
            ValVoiceController.updateBridgeModeLabel("external-exe");
            logger.info("MITM proxy started successfully from: {}", exeCandidate.toAbsolutePath());
        } catch (IOException e) {
            logger.error("FATAL: Failed to start MITM proxy: {}", e.getMessage());
            ValVoiceController.updateXmppStatus("Start failed", false);
            System.exit(1);
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

                        // Check for fatal error events during startup
                        if ("error".equals(type)) {
                            int code = obj.has("code") ? obj.get("code").getAsInt() : 0;
                            String reason = obj.has("reason") && !obj.get("reason").isJsonNull() ? obj.get("reason").getAsString() : "Unknown error";
                            logger.error("[MITM:error] code={} reason={}", code, reason);
                            // Fatal codes: 409=Riot running, 404=Valorant not found, 500=internal
                            if (code == 409 || code == 404 || code == 500) {
                                mitmFatalError = true;
                                mitmFatalReason = reason;
                            }
                        } else {
                            handleMitmEvent(type, obj);
                        }
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

        // === CRITICAL: Wait for MITM startup validation ===
        // Monitor for early exit or fatal errors during first 3 seconds
        logger.info("Validating MITM startup (waiting up to 3 seconds)...");
        long startTime = System.currentTimeMillis();
        long timeoutMs = 3000;

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            // Check for fatal error from stdout
            if (mitmFatalError) {
                logger.error("FATAL: MITM reported error during startup: {}", mitmFatalReason);
                if (mitmProcess.isAlive()) {
                    mitmProcess.destroyForcibly();
                }
                showFatalErrorAndExit(mitmFatalReason);
            }

            // Check if process exited early
            if (!mitmProcess.isAlive()) {
                int exitCode = mitmProcess.exitValue();
                logger.error("FATAL: MITM process exited early with code {}", exitCode);
                String reason = mitmFatalReason != null ? mitmFatalReason : "MITM proxy exited unexpectedly (code " + exitCode + ")";
                showFatalErrorAndExit(reason);
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // Final check after timeout
        if (mitmFatalError) {
            logger.error("FATAL: MITM reported error: {}", mitmFatalReason);
            if (mitmProcess.isAlive()) {
                mitmProcess.destroyForcibly();
            }
            showFatalErrorAndExit(mitmFatalReason);
        }

        if (!mitmProcess.isAlive()) {
            int exitCode = mitmProcess.exitValue();
            logger.error("FATAL: MITM process not alive after startup window (code {})", exitCode);
            String reason = mitmFatalReason != null ? mitmFatalReason : "MITM proxy failed to start (code " + exitCode + ")";
            showFatalErrorAndExit(reason);
        }

        logger.info("MITM proxy validated successfully - process is alive");
        ValVoiceController.updateXmppStatus("Active", true);
        ValVoiceController.updateBridgeModeLabel("external-exe");

        // Monitor MITM process exit (passive - just log, no restart)
        mitmIoPool.submit(() -> {
            try {
                int code = mitmProcess.waitFor();
                logger.warn("MITM proxy process exited with code {}", code);
                ValVoiceController.updateXmppStatus("Exited(" + code + ")", false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Register shutdown hook (cleanup MITM on app exit)
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
                    logger.debug("[MITM:incoming] {}", data);
                    processIncomingXml(data);
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

    // Patterns for parsing XMPP message stanzas
    private static final Pattern MESSAGE_FROM_PATTERN = Pattern.compile("from=['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);
    private static final Pattern MESSAGE_TYPE_PATTERN = Pattern.compile("type=['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);
    private static final Pattern MESSAGE_BODY_PATTERN = Pattern.compile("<body>([^<]*)</body>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * Process incoming XML from MITM and extract chat messages.
     * Ignores presence, iq, and non-chat stanzas.
     */
    private static void processIncomingXml(String xml) {
        if (xml == null || xml.isBlank()) return;

        // Ignore presence and iq stanzas
        if (xml.contains("<presence") || xml.contains("<iq")) {
            return;
        }

        // Only process message stanzas
        if (!xml.contains("<message")) {
            return;
        }

        // Find all message stanzas in the XML
        Matcher messageMatcher = MESSAGE_STANZA_PATTERN.matcher(xml);
        while (messageMatcher.find()) {
            String messageXml = messageMatcher.group();
            extractAndLogChat(messageXml);
        }
    }

    /**
     * Extract chat info from a single message stanza and log it.
     * Triggers TTS for valid chat messages.
     */
    private static void extractAndLogChat(String messageXml) {
        // Extract body
        Matcher bodyMatcher = MESSAGE_BODY_PATTERN.matcher(messageXml);
        if (!bodyMatcher.find()) {
            return; // No body, ignore
        }
        String body = bodyMatcher.group(1);
        if (body == null || body.isBlank()) {
            return; // Empty body, ignore
        }

        // Extract from attribute
        Matcher fromMatcher = MESSAGE_FROM_PATTERN.matcher(messageXml);
        String from = fromMatcher.find() ? fromMatcher.group(1) : "unknown";

        // Extract type attribute
        Matcher typeMatcher = MESSAGE_TYPE_PATTERN.matcher(messageXml);
        String msgType = typeMatcher.find() ? typeMatcher.group(1) : "";

        // Determine chat type based on from JID
        String chatType;
        if (from.contains("@ares-parties")) {
            chatType = "PARTY";
        } else if (from.contains("@ares-coregame")) {
            chatType = "TEAM";
        } else if ("chat".equalsIgnoreCase(msgType)) {
            chatType = "WHISPER";
        } else {
            return; // IGNORE - not a recognized chat type
        }

        // Extract sender name (resource part after /)
        String sender = from;
        int slashIndex = from.lastIndexOf('/');
        if (slashIndex >= 0 && slashIndex < from.length() - 1) {
            sender = from.substring(slashIndex + 1);
        }

        // Log the chat message
        logger.info("[CHAT] type={} from={} body=\"{}\"", chatType, sender, body);

        // Build spoken text based on chat type
        String spokenText;
        switch (chatType) {
            case "TEAM" -> spokenText = "Teammate says: " + body;
            case "PARTY" -> spokenText = "Party chat: " + body;
            case "WHISPER" -> spokenText = sender + " whispers: " + body;
            default -> { return; }
        }

        // Trigger TTS
        speakChatMessage(spokenText);
    }

    /**
     * Speak a chat message using the TTS engine.
     * Uses VoiceGenerator if available.
     */
    private static void speakChatMessage(String text) {
        try {
            if (VoiceGenerator.isInitialized()) {
                VoiceGenerator.getInstance().speak(text);
                logger.debug("[TTS] Speaking: {}", text);
            } else {
                logger.warn("[TTS] VoiceGenerator not initialized, cannot speak: {}", text);
            }
        } catch (Exception e) {
            logger.error("[TTS] Failed to speak: {}", e.getMessage());
        }
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
