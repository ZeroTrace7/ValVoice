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

    // === PHASE 1: App Start Time (ValorantNarrator behavior) ===
    // Immutable timestamp when the Java application starts.
    // DO NOT reset on reconnects, socket resets, or MITM events.
    // All messages with stamp < APP_START_TIME are historical and must be dropped.
    private static final long APP_START_TIME = System.currentTimeMillis();

    // Pattern to extract stamp attribute from archived messages
    // Format: stamp="YYYY-MM-DD HH:mm:ss" or stamp='YYYY-MM-DD HH:mm:ss'
    private static final Pattern STAMP_PATTERN = Pattern.compile(
        "stamp=['\"]([^'\"]+)['\"]",
        Pattern.CASE_INSENSITIVE
    );

    // Archive IQ detection - these messages are NEVER narrated
    private static final String ARCHIVE_NAMESPACE = "jabber:iq:riotgames:archive";

    // === RECONNECT STABILITY: Duplicate Suppression ===
    // Prevents the same message from triggering TTS multiple times during reconnects.
    // Uses a bounded LRU cache to avoid memory leaks.
    // Key = message content hash (body text), Value = timestamp when first seen
    private static final int DUPLICATE_CACHE_SIZE = 100;
    private static final java.util.Map<String, Long> recentMessageHashes =
        java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(DUPLICATE_CACHE_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<String, Long> eldest) {
                return size() > DUPLICATE_CACHE_SIZE;
            }
        });

    // Pattern to extract message ID attribute
    private static final Pattern MESSAGE_ID_PATTERN = Pattern.compile(
        "id=['\"]([^'\"]+)['\"]",
        Pattern.CASE_INSENSITIVE
    );

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
     * Show a fatal error dialog and exit the application.
     * Called before GUI is loaded, so uses pre-startup dialog.
     */
    private static void showFatalErrorAndExit(String message) {
        logger.error("FATAL ERROR: {}", message);

        // Determine user-friendly message
        String displayMessage;
        if (message != null && message.toLowerCase().contains("riot") && message.toLowerCase().contains("running")) {
            displayMessage = "Riot Client is already running.\n\nPlease close Riot Client completely and restart ValVoice.";
        } else if (message != null && (message.toLowerCase().contains("not found") || message.contains("404"))) {
            displayMessage = "Valorant installation not found.\n\nPlease install Valorant and try again.";
        } else {
            displayMessage = "MITM proxy failed to start.\n\n" + (message != null ? message : "Unknown error");
        }

        ValVoiceApplication.showPreStartupDialog(
            "ValVoice - Startup Error",
            displayMessage,
            MessageType.ERROR_MESSAGE
        );

        // Cleanup and exit
        if (mitmProcess != null && mitmProcess.isAlive()) {
            mitmProcess.destroyForcibly();
        }
        System.exit(1);
    }


    /**
     * Handle events from the MITM proxy process
     */
    private static void handleMitmEvent(String type, JsonObject obj) {
        switch (type) {
            case "incoming" -> {
                // Raw XML received from Riot server - use ValorantNarrator-style parsing
                handleIncomingStanza(obj);
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
                // Note: APP_START_TIME is immutable - we do NOT reset it on reconnects
                int socketID = obj.has("socketID") ? obj.get("socketID").getAsInt() : 0;
                logger.info("[MITM:open-riot] socketID={}", socketID);
            }
            case "close-riot" -> {
                // Riot server closed connection (may be ECONNRESET)
                // RECONNECT STABILITY: Do NOT reset any chat state - treat as continuation
                int socketID = obj.has("socketID") ? obj.get("socketID").getAsInt() : 0;
                logger.info("[MITM:close-riot] socketID={}", socketID);
            }
            case "close-valorant" -> {
                // Valorant client closed connection
                // RECONNECT STABILITY: Do NOT reset any chat state - treat as continuation
                int socketID = obj.has("socketID") ? obj.get("socketID").getAsInt() : 0;
                logger.info("[MITM:close-valorant] socketID={}", socketID);
            }
            case "error" -> {
                // Error event (may be ECONNRESET)
                // RECONNECT STABILITY: Do NOT reset any chat state - treat as continuation
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

    /**
     * Handle incoming XMPP stanzas from MITM.
     * Uses ValorantNarrator-style Message parsing and ChatDataHandler.
     *
     * PHASE 1 FIX: Implements ValorantNarrator's archive/history blocking:
     * 1. ARCHIVE IQ BLOCK (PRIMARY): Messages inside <iq type="result"> or jabber:iq:riotgames:archive are NEVER forwarded
     * 2. TIMESTAMP GUARD (SECONDARY): Messages with stamp < APP_START_TIME are dropped silently
     */

    private static void handleIncomingStanza(JsonObject obj) {
        if (!obj.has("data") || obj.get("data").isJsonNull()) return;
        String xml = obj.get("data").getAsString();
        if (xml == null || xml.isBlank()) return;

        String xmlLower = xml.toLowerCase();

        // === PHASE 1 FIX: ARCHIVE IQ BLOCK (HARD RULE) ===
        // If message is embedded inside <iq type="result"> or comes from jabber:iq:riotgames:archive
        // then NEVER forward to ChatDataHandler, NEVER trigger TTS
        if (xmlLower.contains("<iq") && xmlLower.contains("type=\"result\"")) {
            // This is an IQ result stanza - likely contains archived messages
            logger.debug("[ARCHIVE BLOCK] Dropping IQ result stanza (contains archived messages)");
            return;
        }
        if (xmlLower.contains(ARCHIVE_NAMESPACE.toLowerCase())) {
            // Contains archive namespace - definitely historical
            logger.debug("[ARCHIVE BLOCK] Dropping stanza with archive namespace: {}", ARCHIVE_NAMESPACE);
            return;
        }

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
                    // === PHASE 1 FIX: TIMESTAMP GUARD ===
                    // Check if message has a stamp attribute (historical message indicator)
                    // If stamp < APP_START_TIME, this is an old message - DROP IT
                    if (isHistoricalMessage(singleMessageXml)) {
                        logger.debug("[TIMESTAMP GATE] Dropping historical message");
                        continue; // Skip to next message
                    }

                    // === RECONNECT STABILITY: Duplicate Suppression ===
                    // Prevent same message from triggering TTS multiple times during reconnects
                    if (isDuplicateMessage(singleMessageXml)) {
                        logger.debug("[DUPLICATE GATE] Dropping duplicate message");
                        continue; // Skip to next message
                    }

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
                // === PHASE 1 FIX: TIMESTAMP GUARD (also for fallback) ===
                if (isHistoricalMessage(xml)) {
                    logger.debug("[TIMESTAMP GATE] Dropping historical message in fallback path");
                    return;
                }

                // === RECONNECT STABILITY: Duplicate Suppression (also for fallback) ===
                if (isDuplicateMessage(xml)) {
                    logger.debug("[DUPLICATE GATE] Dropping duplicate message in fallback path");
                    return;
                }

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
     * PHASE 1 FIX: Check if a message is historical (should not be narrated).
     *
     * Historical messages have a 'stamp' attribute indicating when they were originally sent.
     * If stamp < APP_START_TIME, the message was sent before this app started
     * and must be dropped silently to avoid TTS spam on startup/reconnection.
     *
     * @param xml The message XML to check
     * @return true if message is historical and should be dropped, false if it should be processed
     */
    private static boolean isHistoricalMessage(String xml) {
        // Check for stamp attribute (present on archived/historical messages)
        Matcher stampMatcher = STAMP_PATTERN.matcher(xml);
        if (!stampMatcher.find()) {
            // No stamp = live message, not historical
            return false;
        }

        String stampValue = stampMatcher.group(1);
        try {
            // Parse stamp: format is typically "YYYY-MM-DD HH:mm:ss" or ISO8601
            long messageEpochMillis = parseStampToEpochMillis(stampValue);

            if (messageEpochMillis < APP_START_TIME) {
                // DROP silently - do not log as error
                logger.debug("[TIMESTAMP GATE] Historical message dropped: stamp={} < appStart={}",
                    stampValue, APP_START_TIME);
                return true;
            }
        } catch (Exception e) {
            // If we can't parse the stamp, assume it's historical to be safe
            // (stamped messages are always historical in Riot's protocol)
            return true;
        }

        return false;
    }

    /**
     * RECONNECT STABILITY: Check if a message is a duplicate (already processed).
     *
     * During reconnects, Riot may resend messages that were already narrated.
     * This method uses a bounded LRU cache to track recently processed messages
     * and suppress duplicates silently.
     *
     * @param xml The message XML to check
     * @return true if this is a duplicate and should be dropped, false if it should be processed
     */
    private static boolean isDuplicateMessage(String xml) {
        // Generate a unique key for this message
        // Priority 1: Use message ID if present
        Matcher idMatcher = MESSAGE_ID_PATTERN.matcher(xml);
        String messageKey;

        if (idMatcher.find()) {
            // Use message ID as primary key
            messageKey = "id:" + idMatcher.group(1);
        } else {
            // Fallback: Use hash of body content + from attribute
            Pattern bodyPattern = Pattern.compile("<body>([\\s\\S]*?)</body>", Pattern.CASE_INSENSITIVE);
            Pattern fromPattern = Pattern.compile("from=['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);

            Matcher bodyMatcher = bodyPattern.matcher(xml);
            Matcher fromMatcher = fromPattern.matcher(xml);

            String body = bodyMatcher.find() ? bodyMatcher.group(1) : "";
            String from = fromMatcher.find() ? fromMatcher.group(1) : "";

            // Create composite key from from + body hash
            messageKey = "hash:" + from.hashCode() + ":" + body.hashCode();
        }

        // Check if we've seen this message before
        synchronized (recentMessageHashes) {
            if (recentMessageHashes.containsKey(messageKey)) {
                // Duplicate detected - already processed
                return true;
            }

            // First time seeing this message - record it
            recentMessageHashes.put(messageKey, System.currentTimeMillis());
            return false;
        }
    }

    /**
     * Parse XMPP stamp attribute to epoch milliseconds.
     * Handles formats: "YYYY-MM-DD HH:mm:ss" and ISO8601 variants.
     */
    private static long parseStampToEpochMillis(String stamp) {
        // Try common formats
        java.time.format.DateTimeFormatter[] formatters = {
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"),
            java.time.format.DateTimeFormatter.ISO_DATE_TIME,
            java.time.format.DateTimeFormatter.ISO_INSTANT
        };

        for (var formatter : formatters) {
            try {
                // Try parsing as LocalDateTime first (no timezone)
                java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(stamp, formatter);
                return ldt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (Exception ignored) {}

            try {
                // Try parsing as ZonedDateTime
                java.time.ZonedDateTime zdt = java.time.ZonedDateTime.parse(stamp, formatter);
                return zdt.toInstant().toEpochMilli();
            } catch (Exception ignored) {}
        }

        // Last resort: try Instant.parse for ISO8601 with Z suffix
        try {
            return java.time.Instant.parse(stamp).toEpochMilli();
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot parse stamp: " + stamp, e);
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
