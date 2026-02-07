package com.someone.valvoicegui;

import com.someone.valvoicebackend.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * ValVoiceBackend - Backend service for MITM process management, XMPP parsing, and identity capture.
 *
 * Phase 2B: Production Cutover - StAX-based XML parsing is now the ONLY parser.
 * All regex-based XMPP stanza parsing has been removed.
 *
 * Responsibilities (ValorantNarrator reference architecture):
 * - MITM process lifecycle (start/stop)
 * - Reading MITM stdout/stderr
 * - XMPP stanza parsing via XmppStreamParser (StAX-based)
 * - Identity capture from &lt;auth mechanism="X-Riot-RSO-PAS"&gt;
 * - Timestamp gate + grace period
 * - History filtering
 * - Duplicate message filtering
 *
 * This class is started by ValVoiceController.initialize() after the UI is ready.
 */
public class ValVoiceBackend {
    private static final Logger logger = LoggerFactory.getLogger(ValVoiceBackend.class);

    // Singleton instance
    private static ValVoiceBackend instance;
    private static final Object instanceLock = new Object();

    // MITM proxy process management
    private Process mitmProcess;
    private volatile boolean mitmFatalError = false;
    private volatile String mitmFatalReason = null;
    private final ExecutorService mitmIoPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mitm-io");
        t.setDaemon(true);
        return t;
    });

    // MITM executable name
    private static final String XMPP_EXE_NAME_PRIMARY = "valvoice-mitm.exe";

    // === PHASE 1: App Start Time (ValorantNarrator behavior) ===
    // Immutable timestamp when the Java application starts.
    // DO NOT reset on reconnects, socket resets, or MITM events.
    // All messages with stamp < APP_START_TIME are historical and must be dropped.
    private final long appStartTime;

    // === GRACE PERIOD: Tolerate clock skew and network latency (ValorantNarrator reference) ===
    // Live messages may appear a few seconds older than app start due to:
    // - Clock skew between client and server
    // - Timezone differences
    // - Network delay
    // A 60-second grace window ensures live Team Chat (ares-coregame) is NOT dropped.
    // This matches ValorantNarrator reference exactly.
    private static final long GRACE_PERIOD_MS = 60_000; // 60 seconds (ValorantNarrator parity)


    // === RECONNECT STABILITY: Duplicate Suppression ===
    // Prevents the same message from triggering TTS multiple times during reconnects.
    // Uses a bounded LRU cache to avoid memory leaks.
    // Key = message content hash (body text), Value = timestamp when first seen
    private static final int DUPLICATE_CACHE_SIZE = 100;
    private final java.util.Map<String, Long> recentMessageHashes =
        java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(DUPLICATE_CACHE_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<String, Long> eldest) {
                return size() > DUPLICATE_CACHE_SIZE;
            }
        });

    // === PHASE 1: PUUID Identity Capture ===
    // Pattern to detect Riot RSO-PAS authentication mechanism (kept for auth parsing only)
    private static final Pattern RSO_PAS_AUTH_PATTERN = Pattern.compile(
        "<auth\\s+[^>]*mechanism=['\"]X-Riot-RSO-PAS['\"][^>]*>",
        Pattern.CASE_INSENSITIVE
    );
    // Pattern to extract rso_token from the auth XML (kept for auth parsing only)
    private static final Pattern RSO_TOKEN_PATTERN = Pattern.compile(
        "<rso_token>([^<]+)</rso_token>",
        Pattern.CASE_INSENSITIVE
    );

    // === PHASE 1: ONCE-PER-LAUNCH GUARD ===
    // Flag to ensure PUUID identity is captured ONLY ONCE per app launch.
    // This is NOT reset on ECONNRESET, reconnects, or any MITM events.
    // Once identity is captured, it persists for the entire session.
    private volatile boolean identityCaptured = false;

    // === PHASE 5: EVENT-DRIVEN UI (Reactive Backend) ===
    // Thread-safe listener list for decoupling backend from UI.
    // Replaces direct ValVoiceController.updateXxx() calls with event dispatch.
    // This is an intentional deviation from ValorantNarrator for production hardening.
    private final List<ValVoiceEventListener> listeners = new CopyOnWriteArrayList<>();

    // Backend running state
    private volatile boolean running = false;
    private volatile boolean started = false;

    /**
     * Private constructor - use getInstance() or start()
     */
    private ValVoiceBackend() {
        // Capture app start time at construction (immutable for session lifetime)
        this.appStartTime = System.currentTimeMillis();
        logger.info("[ValVoiceBackend] Instance created, appStartTime={}", appStartTime);
    }

    /**
     * Get the singleton instance of ValVoiceBackend.
     * Creates the instance if it doesn't exist.
     */
    public static ValVoiceBackend getInstance() {
        synchronized (instanceLock) {
            if (instance == null) {
                instance = new ValVoiceBackend();
            }
            return instance;
        }
    }

    /**
     * Start the backend services.
     * This is the main entry point called by ValVoiceController after UI is ready.
     * Thread-safe and idempotent - can be called multiple times safely.
     */
    public void start() {
        synchronized (instanceLock) {
            if (started) {
                logger.warn("[ValVoiceBackend] Already started, ignoring duplicate start() call");
                return;
            }
            started = true;
            running = true;
        }

        logger.info("[ValVoiceBackend] Starting backend services...");

        // === STARTUP WARM-UP DELAY ===
        // Small delay to let Riot Client networking stack initialize.
        // This reduces early ECONNRESET events (socketID=1,2,3...) that occur
        // when MITM starts before Riot Client is fully ready.
        // This is cosmetic - ECONNRESET handling remains non-fatal regardless.
        try {
            logger.debug("[ValVoiceBackend] Warm-up delay (2s) before MITM launch...");
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.debug("[ValVoiceBackend] Warm-up delay interrupted");
        }

        launchMitmProxy();
    }

    /**
     * Stop the backend services and cleanup resources.
     */
    public void stop() {
        synchronized (instanceLock) {
            if (!running) {
                return;
            }
            running = false;
        }

        logger.info("[ValVoiceBackend] Stopping backend services...");

        // Destroy MITM process (Java Process API)
        if (mitmProcess != null && mitmProcess.isAlive()) {
            logger.info("[ValVoiceBackend] Destroying MITM proxy process");
            mitmProcess.destroy();
            try {
                if (!mitmProcess.waitFor(3, TimeUnit.SECONDS)) {
                    logger.warn("[ValVoiceBackend] MITM process did not exit gracefully, forcing...");
                    mitmProcess.destroyForcibly();
                    mitmProcess.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                mitmProcess.destroyForcibly();
            }
        }

        // Phase 1: OS-level kill as fallback (ensures no orphaned processes)
        killMitmProcessOsLevel();

        // Shutdown thread pool
        mitmIoPool.shutdown();
        try {
            if (!mitmIoPool.awaitTermination(5, TimeUnit.SECONDS)) {
                mitmIoPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            mitmIoPool.shutdownNow();
            Thread.currentThread().interrupt();
        }

        logger.info("[ValVoiceBackend] Backend services stopped");
    }

    /**
     * Phase 1: OS-level MITM process kill.
     * Uses taskkill to ensure no zombie valvoice-mitm.exe processes remain.
     * This is a fallback for cases where Java's Process.destroy() fails.
     */
    private void killMitmProcessOsLevel() {
        try {
            logger.debug("[ValVoiceBackend] Running OS-level MITM cleanup...");
            ProcessBuilder pb = new ProcessBuilder("taskkill", "/F", "/IM", "valvoice-mitm.exe");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            // Consume output (non-blocking best-effort)
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.debug("[taskkill] {}", line);
                }
            }
            proc.waitFor(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.debug("[ValVoiceBackend] OS-level MITM cleanup failed (non-fatal): {}", e.getMessage());
        }
    }

    /**
     * Check if the backend is currently running.
     */
    public boolean isRunning() {
        return running && mitmProcess != null && mitmProcess.isAlive();
    }

    /**
     * Get the app start time (immutable, used for timestamp gate).
     */
    public long getAppStartTime() {
        return appStartTime;
    }

    // ========== MITM Process Management ==========

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
    private void launchMitmProxy() {
        if (mitmProcess != null && mitmProcess.isAlive()) {
            logger.warn("[ValVoiceBackend] MITM proxy process already running");
            return;
        }

        logger.info("[ValVoiceBackend] Starting MITM proxy...");
        fireStatusChanged("xmpp", "Starting...", false);

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
            logger.error("[ValVoiceBackend] FATAL: {} not found! ValVoice cannot function without the MITM proxy.", XMPP_EXE_NAME_PRIMARY);
            logger.error("[ValVoiceBackend] Please ensure valvoice-mitm.exe is present in the application directory or mitm/ subdirectory.");
            fireStatusChanged("xmpp", "MITM exe missing", false);
            showFatalErrorAndExit("MITM executable not found: " + XMPP_EXE_NAME_PRIMARY);
            return;
        }

        // Launch the exe
        // Observer-only: Java reads MITM stdout JSON, never writes to stdin
        ProcessBuilder pb = new ProcessBuilder(exeCandidate.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        pb.directory(workingDir.toFile());
        try {
            mitmProcess = pb.start();
            System.setProperty("valvoice.bridgeMode", "external-exe");
            fireStatusChanged("bridge", "external-exe", true);
            logger.info("[ValVoiceBackend] MITM proxy started successfully from: {}", exeCandidate.toAbsolutePath());
        } catch (IOException e) {
            logger.error("[ValVoiceBackend] FATAL: Failed to start MITM proxy: {}", e.getMessage());
            fireStatusChanged("xmpp", "Start failed", false);
            showFatalErrorAndExit("Failed to start MITM proxy: " + e.getMessage());
            return;
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
                logger.warn("[ValVoiceBackend] MITM proxy stdout reader terminating", e);
            } finally {
                logger.info("[ValVoiceBackend] MITM proxy output closed");
            }
        });

        // === CRITICAL: Wait for MITM startup validation ===
        // Monitor for early exit or fatal errors during first 3 seconds
        logger.info("[ValVoiceBackend] Validating MITM startup (waiting up to 3 seconds)...");
        long startTime = System.currentTimeMillis();
        long timeoutMs = 3000;

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            // Check for fatal error from stdout
            if (mitmFatalError) {
                logger.error("[ValVoiceBackend] FATAL: MITM reported error during startup: {}", mitmFatalReason);
                if (mitmProcess.isAlive()) {
                    mitmProcess.destroyForcibly();
                }
                showFatalErrorAndExit(mitmFatalReason);
                return;
            }

            // Check if process exited early
            if (!mitmProcess.isAlive()) {
                int exitCode = mitmProcess.exitValue();
                logger.error("[ValVoiceBackend] FATAL: MITM process exited early with code {}", exitCode);
                String reason = mitmFatalReason != null ? mitmFatalReason : "MITM proxy exited unexpectedly (code " + exitCode + ")";
                showFatalErrorAndExit(reason);
                return;
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
            logger.error("[ValVoiceBackend] FATAL: MITM reported error: {}", mitmFatalReason);
            if (mitmProcess.isAlive()) {
                mitmProcess.destroyForcibly();
            }
            showFatalErrorAndExit(mitmFatalReason);
            return;
        }

        if (!mitmProcess.isAlive()) {
            int exitCode = mitmProcess.exitValue();
            logger.error("[ValVoiceBackend] FATAL: MITM process not alive after startup window (code {})", exitCode);
            String reason = mitmFatalReason != null ? mitmFatalReason : "MITM proxy failed to start (code " + exitCode + ")";
            showFatalErrorAndExit(reason);
            return;
        }

        logger.info("[ValVoiceBackend] MITM proxy validated successfully - process is alive");
        fireStatusChanged("xmpp", "Active", true);
        fireStatusChanged("bridge", "external-exe", true);

        // Monitor MITM process exit (passive - just log, no restart)
        mitmIoPool.submit(() -> {
            try {
                int code = mitmProcess.waitFor();
                logger.warn("[ValVoiceBackend] MITM proxy process exited with code {}", code);
                fireStatusChanged("xmpp", "Exited(" + code + ")", false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Register shutdown hook (cleanup MITM on app exit)
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stop();
        }, "valvoice-backend-shutdown"));
    }

    /**
     * Show a fatal error dialog and exit the application.
     */
    private void showFatalErrorAndExit(String message) {
        logger.error("[ValVoiceBackend] FATAL ERROR: {}", message);

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

    // ========== MITM Event Handling ==========

    /**
     * Handle events from the MITM proxy process
     */
    private void handleMitmEvent(String type, JsonObject obj) {
        switch (type) {
            case "incoming" -> {
                // Raw XML received from Riot server - use ValorantNarrator-style parsing
                handleIncomingStanza(obj);
            }
            case "outgoing" -> {
                // Raw XML sent to Riot server - check for RSO-PAS auth to capture identity
                if (obj.has("data") && !obj.get("data").isJsonNull()) {
                    String data = obj.get("data").getAsString();
                    logger.info("[MITM:outgoing] {}", data);

                    // === PHASE 1: PUUID Identity Capture ===
                    // Detect RSO-PAS authentication mechanism and extract PUUID from JWT
                    tryExtractPuuidFromAuth(data);
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
                // Note: appStartTime is immutable - we do NOT reset it on reconnects
                int socketID = obj.has("socketID") ? obj.get("socketID").getAsInt() : 0;
                logger.info("[MITM:open-riot] socketID={}", socketID);
            }
            case "close-riot" -> {
                // Riot server closed connection (may be ECONNRESET)
                // RECONNECT STABILITY: Do NOT reset any chat state - treat as continuation
                // PHASE 3: currentUserPuuid (in ChatDataHandler.selfId) is NOT touched here
                int socketID = obj.has("socketID") ? obj.get("socketID").getAsInt() : 0;
                logger.info("[MITM:close-riot] socketID={}", socketID);
                String currentIdentity = ChatDataHandler.getInstance().getSelfId();
                logger.debug("[RECONNECT SAFE] Identity preserved after close-riot: {}",
                            currentIdentity != null ? currentIdentity.substring(0, Math.min(8, currentIdentity.length())) + "..." : "(null)");
            }
            case "close-valorant" -> {
                // Valorant client closed connection
                // RECONNECT STABILITY: Do NOT reset any chat state - treat as continuation
                // PHASE 3: currentUserPuuid (in ChatDataHandler.selfId) is NOT touched here
                int socketID = obj.has("socketID") ? obj.get("socketID").getAsInt() : 0;
                logger.info("[MITM:close-valorant] socketID={}", socketID);
                String currentIdentity = ChatDataHandler.getInstance().getSelfId();
                logger.debug("[RECONNECT SAFE] Identity preserved after close-valorant: {}",
                            currentIdentity != null ? currentIdentity.substring(0, Math.min(8, currentIdentity.length())) + "..." : "(null)");
            }
            case "error" -> {
                // Error event (may be ECONNRESET)
                // RECONNECT STABILITY: Do NOT reset any chat state - treat as continuation
                // PHASE 3: currentUserPuuid (in ChatDataHandler.selfId) is NOT touched here
                int code = obj.has("code") ? obj.get("code").getAsInt() : 0;
                String reason = obj.has("reason") && !obj.get("reason").isJsonNull() ? obj.get("reason").getAsString() : "unknown";
                logger.warn("[MITM:error] code={} reason={}", code, reason);
                // Log identity preservation for ECONNRESET scenarios
                if (reason.contains("ECONNRESET") || reason.contains("socket error")) {
                    String currentIdentity = ChatDataHandler.getInstance().getSelfId();
                    logger.info("[RECONNECT SAFE] ECONNRESET detected - identity preserved: {}",
                               currentIdentity != null ? currentIdentity.substring(0, Math.min(8, currentIdentity.length())) + "..." : "(null)");
                }
            }
            default -> {
                // Unknown event type
                logger.debug("[MITM:unknown] {}", obj);
            }
        }
    }

    // ========== PUUID Identity Capture ==========

    /**
     * PHASE 1: PUUID Identity Capture (ValorantNarrator Reference Architecture)
     *
     * Intercepts the outgoing XMPP auth stanza during MITM startup:
     *   &lt;auth mechanism="X-Riot-RSO-PAS"&gt;
     *
     * Extracts the &lt;rso_token&gt; JWT, Base64URL-decodes the payload, parses JSON, and stores:
     *   { "sub": "&lt;PUUID&gt;" }
     *
     * CRITICAL REQUIREMENTS (ValorantNarrator parity):
     * - PUUID is captured ONCE per app launch (not reset on ECONNRESET/reconnects)
     * - Stored in ChatDataHandler.selfId (volatile field)
     * - Used by ChatDataHandler for raw PUUID comparison (not by parser utilities)
     *
     * @param xml The outgoing XMPP stanza XML
     */
    private void tryExtractPuuidFromAuth(String xml) {
        if (xml == null || xml.isBlank()) return;

        // === ONCE-PER-LAUNCH GUARD ===
        // If identity has already been captured this session, skip processing.
        // This prevents overwrites from duplicate auth packets during reconnects.
        if (identityCaptured) {
            // Still log that we saw an auth packet (for debugging reconnect behavior)
            if (RSO_PAS_AUTH_PATTERN.matcher(xml).find()) {
                logger.debug("[ValVoiceBackend] RSO-PAS auth packet detected but identity already captured - ignoring");
            }
            return;
        }

        // Check if this is an RSO-PAS authentication stanza
        Matcher authMatcher = RSO_PAS_AUTH_PATTERN.matcher(xml);
        if (!authMatcher.find()) {
            return; // Not an RSO-PAS auth packet
        }

        logger.info("[ValVoiceBackend] RSO-PAS auth packet detected - extracting identity...");

        // Extract the rso_token value
        Matcher tokenMatcher = RSO_TOKEN_PATTERN.matcher(xml);
        if (!tokenMatcher.find()) {
            logger.warn("[ValVoiceBackend] RSO-PAS auth detected but no rso_token found");
            return;
        }

        String rsoToken = tokenMatcher.group(1);
        if (rsoToken == null || rsoToken.isBlank()) {
            logger.warn("[ValVoiceBackend] rso_token is empty");
            return;
        }

        // Decode JWT and extract PUUID from 'sub' field
        String puuid = extractPuuidFromJwt(rsoToken);
        if (puuid != null && !puuid.isBlank()) {
            // Store in ChatDataHandler for global access
            ChatDataHandler.getInstance().setSelfId(puuid);

            // Set the once-per-launch flag AFTER successful capture
            identityCaptured = true;

            // Log confirmation (required format per ValorantNarrator reference)
            logger.info("[ValVoiceBackend] Identity captured: {}", puuid);
            logger.info("[ValVoiceBackend] Identity persists across ECONNRESET reconnects - will NOT be re-captured this session");

            // Update UI status via listener (Phase 5: Event-Driven UI)
            fireIdentityCaptured(puuid);
        }
    }

    /**
     * Extracts the PUUID (sub field) from a JWT token.
     *
     * JWT structure: header.payload.signature (Base64URL encoded)
     * The payload is a JSON object containing the 'sub' field which is the PUUID.
     *
     * @param jwt The JWT token string
     * @return The PUUID (sub field) or null if extraction fails
     */
    private String extractPuuidFromJwt(String jwt) {
        try {
            // JWT format: header.payload.signature
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) {
                logger.warn("[ValVoiceBackend] Invalid JWT format - expected 3 parts, got {}", parts.length);
                return null;
            }

            // Decode the payload (second part) - Base64 URL-safe
            String payloadBase64 = parts[1];

            // Base64 URL-safe decoding: replace - with + and _ with /
            // Also add padding if needed
            String base64Standard = payloadBase64
                .replace('-', '+')
                .replace('_', '/');

            // Add padding if necessary
            int padding = (4 - base64Standard.length() % 4) % 4;
            base64Standard = base64Standard + "====".substring(0, padding);

            // Decode using standard Java Base64
            byte[] decodedBytes = java.util.Base64.getDecoder().decode(base64Standard);
            String payloadJson = new String(decodedBytes, StandardCharsets.UTF_8);

            // Parse JSON and extract 'sub' field
            JsonObject payload = JsonParser.parseString(payloadJson).getAsJsonObject();

            if (payload.has("sub") && !payload.get("sub").isJsonNull()) {
                return payload.get("sub").getAsString();
            } else {
                logger.warn("[ValVoiceBackend] JWT payload does not contain 'sub' field");
                return null;
            }
        } catch (Exception e) {
            logger.warn("[ValVoiceBackend] Failed to decode JWT: {}", e.getMessage());
            return null;
        }
    }

    // ========== XMPP Stanza Handling ==========

    /**
     * Handle incoming XMPP stanzas from MITM.
     *
     * Phase 2B: Production Cutover - Now uses XmppStreamParser (StAX) exclusively.
     * All regex-based message parsing has been removed.
     *
     * Processing Pipeline:
     * 0. PRESENCE: Extract game state for Smart Mute (Phase 4 - additive)
     * 1. ROSTER IQ: Parse to build PUUID→Name mapping (Phase 3)
     * 2. ARCHIVE IQ BLOCK: Drop historical messages in IQ results
     * 3. StAX PARSE: Extract message fields via XmppStreamParser
     * 4. TIMESTAMP GATE: Drop messages older than APP_START_TIME (with grace period)
     * 5. DUPLICATE GATE: Suppress already-processed messages
     * 6. MESSAGE HANDLER: Forward to ChatDataHandler for TTS
     */
    private void handleIncomingStanza(JsonObject obj) {
        if (!obj.has("data") || obj.get("data").isJsonNull()) return;
        String xml = obj.get("data").getAsString();
        if (xml == null || xml.isBlank()) return;


        // ═══════════════════════════════════════════════════════════════════════════
        // PHASE 4: PRESENCE STANZA HANDLING (Smart Mute / Game State Awareness)
        // ═══════════════════════════════════════════════════════════════════════════
        // INTENTIONAL DEVIATION FROM VALORANTNARRATOR:
        // ValorantNarrator does NOT implement presence-based game state awareness.
        // This is a ValVoice enhancement that is strictly ADDITIVE and ISOLATED.
        //
        // NON-NEGOTIABLE INVARIANTS (preserved):
        // - UTC + grace window timestamp gate: UNCHANGED
        // - MITM ECONNRESET resilience: UNCHANGED
        // - Archive IQ blocking: UNCHANGED
        // - Self-only narration: UNCHANGED (gate is in ChatDataHandler)
        //
        // Presence stanzas are processed HERE and NEVER reach the chat pipeline.
        // ═══════════════════════════════════════════════════════════════════════════
        if (XmppStreamParser.isPresenceStanza(xml)) {
            handlePresenceStanza(xml);
            // Presence stanzas are NOT chat messages - do not continue to message parsing
            return;
        }

        // === PHASE 3: ROSTER IQ PARSING ===
        // Parse roster IQ packets to build PUUID→Name mapping for TTS announcements
        // This must happen BEFORE archive blocking since roster IQs are also type="result"
        if (Roster.getInstance().isRosterIq(xml)) {
            int count = Roster.getInstance().parseRosterIq(xml);
            logger.info("[ROSTER] Parsed {} roster entries from IQ packet", count);
            // Roster IQs are processed - return immediately to prevent IQ SKIP block
            return;
        }

        // === ARCHIVE IQ BLOCK (HARD RULE) ===
        // If message is embedded inside <iq type="result"> with jabber:iq:riotgames:archive
        // then NEVER forward to ChatDataHandler, NEVER trigger TTS
        if (XmppStreamParser.isIqStanza(xml)) {
            if (XmppStreamParser.isArchiveStanza(xml)) {
                logger.debug("[ARCHIVE BLOCK] Dropping archive IQ result stanza");
                return;
            }
            // Non-archive IQ results (like roster) don't contain chat messages
            if (!XmppStreamParser.containsMessageWithBody(xml)) {
                logger.debug("[IQ SKIP] IQ stanza with no chat messages, skipping");
                return;
            }
            // IQ result containing messages - these are archived messages, block them
            logger.debug("[ARCHIVE BLOCK] Dropping IQ result containing archived messages");
            return;
        }
        if (XmppStreamParser.isArchiveStanza(xml)) {
            logger.debug("[ARCHIVE BLOCK] Dropping stanza with archive namespace");
            return;
        }

        // === QUICK FILTER: Skip non-message stanzas ===
        if (!XmppStreamParser.containsMessageWithBody(xml)) {
            return;
        }

        // === STAX PARSING: Extract messages using streaming XML parser ===
        try {
            List<ParsedMessage> messages = XmppStreamParser.parseMessages(xml);

            if (messages.isEmpty()) {
                logger.debug("[StAX] No messages extracted from stanza");
                return;
            }

            // === DEBUG LOGGING (using parsed data, not regex) ===
            for (ParsedMessage parsed : messages) {
                logger.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                logger.info("📨 PARSED MESSAGE (StAX):");
                logger.info("FROM: {}", parsed.getFrom());
                logger.info("TYPE: {}", parsed.getType());
                logger.info("ID: {}", parsed.getId());
                logger.info("STAMP: {}", parsed.getStamp());
                logger.info("BODY: {}", parsed.getBody() != null ?
                    (parsed.getBody().length() > 100 ? parsed.getBody().substring(0, 97) + "..." : parsed.getBody())
                    : "(null)");

                // Log MUC classification
                String from = parsed.getFrom();
                if (from != null) {
                    boolean isParty = from.contains("@ares-parties");
                    boolean isPregame = from.contains("@ares-pregame");
                    boolean isCoregame = from.contains("@ares-coregame");
                    logger.info("  - MUC Type: party={}, pregame={}, coregame={}", isParty, isPregame, isCoregame);
                }
                logger.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            }

            // === PROCESS EACH MESSAGE ===
            for (ParsedMessage parsed : messages) {
                if (!parsed.hasBody()) {
                    continue;
                }

                // === TIMESTAMP GATE ===
                // Check stamp attribute for historical messages
                if (isHistoricalMessage(parsed.getStamp())) {
                    logger.debug("[TIMESTAMP GATE] Dropping historical message (stamp={})", parsed.getStamp());
                    continue;
                }

                // === DUPLICATE GATE ===
                // Suppress messages already processed
                if (isDuplicateMessage(parsed.getId(), parsed.getFrom(), parsed.getBody())) {
                    logger.debug("[DUPLICATE GATE] Dropping duplicate message (id={})", parsed.getId());
                    continue;
                }

                // === FORWARD TO MESSAGE HANDLER ===
                try {
                    // Use the raw XML to construct the Message object
                    // (Message class still needs XML for full parsing)
                    Message msg = new Message(parsed.getRawXml());
                    logger.debug("Forwarding message: type={}, from={}", msg.getMessageType(), msg.getUserId());
                    ChatDataHandler.getInstance().message(msg);
                } catch (Exception ex) {
                    logger.warn("Failed to create Message object: {}", ex.getMessage());
                }
            }

        } catch (Exception e) {
            // Safety: Never crash MITM on parse errors
            logger.debug("[StAX] Error handling incoming stanza: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // PHASE 4: PRESENCE STANZA HANDLING (Smart Mute / Game State Awareness)
    // ═══════════════════════════════════════════════════════════════════════════════
    // INTENTIONAL DEVIATION FROM VALORANTNARRATOR:
    // ValorantNarrator does NOT implement presence-based game state awareness.
    // This method is a ValVoice enhancement - strictly ADDITIVE and ISOLATED.
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Handle presence stanzas to extract game state for Smart Mute.
     *
     * Presence stanzas contain a Base64-encoded JSON payload in the &lt;p&gt; element.
     * The JSON includes a "sessionLoopState" field with values:
     * - "MENUS"   : Player is in main menu / lobby
     * - "PREGAME" : Player is in agent select
     * - "INGAME"  : Player is in an active match
     *
     * This method is ADDITIVE - it does NOT affect any existing parsing logic.
     * Presence stanzas NEVER reach the chat message pipeline.
     *
     * @param xml Raw presence stanza XML
     */
    private void handlePresenceStanza(String xml) {
        // Quick check: does this presence have a <p> payload?
        if (!XmppStreamParser.presenceHasPayload(xml)) {
            logger.debug("[PRESENCE] Stanza has no <p> payload, ignoring");
            return;
        }

        // Extract the Base64-encoded payload from <p> element
        String base64Payload = XmppStreamParser.extractPresencePayload(xml);
        if (base64Payload == null || base64Payload.isEmpty()) {
            logger.debug("[PRESENCE] Could not extract <p> payload");
            return;
        }

        try {
            // Decode Base64 to JSON string
            byte[] decodedBytes = java.util.Base64.getDecoder().decode(base64Payload);
            String jsonPayload = new String(decodedBytes, StandardCharsets.UTF_8);

            // Parse JSON and extract sessionLoopState
            JsonObject payload = JsonParser.parseString(jsonPayload).getAsJsonObject();

            if (payload.has("sessionLoopState") && !payload.get("sessionLoopState").isJsonNull()) {
                String sessionLoopState = payload.get("sessionLoopState").getAsString();
                logger.debug("[PRESENCE] sessionLoopState={}", sessionLoopState);

                // Update GameStateManager (thread-safe)
                GameStateManager.getInstance().updateFromSessionLoopState(sessionLoopState);
            } else {
                logger.debug("[PRESENCE] JSON payload does not contain sessionLoopState");
            }

        } catch (IllegalArgumentException e) {
            // Base64 decoding failed - not a valid payload
            logger.debug("[PRESENCE] Failed to decode Base64 payload: {}", e.getMessage());
        } catch (Exception e) {
            // JSON parsing failed or other error - never crash
            logger.debug("[PRESENCE] Failed to parse presence payload: {}", e.getMessage());
        }
    }

    // ========== Timestamp Gate (refactored for StAX) ==========

    /**
     * Check if a message is historical based on its stamp attribute.
     *
     * Phase 2B: Now accepts extracted stamp value instead of raw XML.
     *
     * @param stamp The stamp attribute value (may be null)
     * @return true if message is historical and should be dropped
     */
    private boolean isHistoricalMessage(String stamp) {
        if (stamp == null || stamp.isEmpty()) {
            // No stamp = live message, not historical
            return false;
        }

        try {
            long messageEpochMillis = parseStampToEpochMillis(stamp);

            if (messageEpochMillis < (appStartTime - GRACE_PERIOD_MS)) {
                logger.debug("[TIMESTAMP GATE] Historical: stamp={} < (appStart={} - grace={})",
                    stamp, appStartTime, GRACE_PERIOD_MS);
                return true;
            }
        } catch (Exception e) {
            // If we can't parse the stamp, assume it's historical to be safe
            logger.debug("[TIMESTAMP GATE] Unparseable stamp '{}', treating as historical", stamp);
            return true;
        }

        return false;
    }

    // ========== Duplicate Suppression (refactored for StAX) ==========

    /**
     * Check if a message is a duplicate (already processed).
     *
     * Phase 2B: Now accepts extracted fields instead of raw XML.
     *
     * @param id The message ID attribute (may be null)
     * @param from The from attribute (may be null)
     * @param body The body content (may be null)
     * @return true if this is a duplicate and should be dropped
     */
    private boolean isDuplicateMessage(String id, String from, String body) {
        String messageKey;

        if (id != null && !id.isEmpty()) {
            // Use message ID as primary key
            messageKey = "id:" + id;
        } else {
            // Fallback: Use hash of from + body
            String fromPart = from != null ? from : "";
            String bodyPart = body != null ? body : "";
            messageKey = "hash:" + fromPart.hashCode() + ":" + bodyPart.hashCode();
        }

        synchronized (recentMessageHashes) {
            if (recentMessageHashes.containsKey(messageKey)) {
                return true;
            }
            recentMessageHashes.put(messageKey, System.currentTimeMillis());
            return false;
        }
    }

    // ========== Utility Methods ==========

    /**
     * Parse XMPP stamp attribute to epoch milliseconds.
     * Handles formats: "YYYY-MM-DD HH:mm:ss" and ISO8601 variants.
     *
     * CRITICAL: Riot XMPP servers send timestamps in UTC.
     * We must parse them as UTC, NOT as local time.
     * Failure to do this causes a timezone offset (e.g., 5.5 hours for IST)
     * which makes live messages appear historical and get dropped.
     */
    private long parseStampToEpochMillis(String stamp) {
        // Try common formats - ALL parsed as UTC since Riot sends UTC timestamps
        java.time.format.DateTimeFormatter[] formatters = {
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"),
            java.time.format.DateTimeFormatter.ISO_DATE_TIME,
            java.time.format.DateTimeFormatter.ISO_INSTANT
        };

        for (var formatter : formatters) {
            try {
                // Parse as LocalDateTime, then convert to UTC (NOT system default!)
                // Riot sends timestamps in UTC - this is critical for correct filtering
                java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(stamp, formatter);
                return ldt.atZone(java.time.ZoneId.of("UTC")).toInstant().toEpochMilli();
            } catch (Exception ignored) {}

            try {
                // Try parsing as ZonedDateTime (already has timezone info)
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

    // ═══════════════════════════════════════════════════════════════════════════════
    // PHASE 5: EVENT-DRIVEN UI (Reactive Backend)
    // ═══════════════════════════════════════════════════════════════════════════════
    // INTENTIONAL DEVIATION FROM VALORANTNARRATOR:
    // ValorantNarrator uses direct Controller singleton calls wrapped in Platform.runLater().
    // This refactor decouples backend from UI for production hardening and testability.
    // NO RUNTIME BEHAVIOR IS CHANGED - all UI updates still occur on JavaFX thread.
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Register a listener for backend events.
     * Listeners are notified of status changes, identity capture, and stats updates.
     *
     * @param listener The listener to register
     */
    public void addListener(ValVoiceEventListener listener) {
        if (listener != null) {
            listeners.add(listener);
            logger.debug("[ValVoiceBackend] Listener registered: {}", listener.getClass().getSimpleName());
        }
    }

    /**
     * Remove a previously registered listener.
     *
     * @param listener The listener to remove
     */
    public void removeListener(ValVoiceEventListener listener) {
        listeners.remove(listener);
    }

    /**
     * Notify all listeners of a status change.
     * Thread-safe - can be called from any thread.
     */
    private void fireStatusChanged(String component, String status, boolean ok) {
        for (ValVoiceEventListener l : listeners) {
            try {
                l.onStatusChanged(component, status, ok);
            } catch (Exception e) {
                logger.debug("[ValVoiceBackend] Listener error on status change: {}", e.getMessage());
            }
        }
    }

    /**
     * Notify all listeners of identity capture.
     * Thread-safe - can be called from any thread.
     */
    private void fireIdentityCaptured(String puuid) {
        for (ValVoiceEventListener l : listeners) {
            try {
                l.onIdentityCaptured(puuid);
            } catch (Exception e) {
                logger.debug("[ValVoiceBackend] Listener error on identity capture: {}", e.getMessage());
            }
        }
    }


    /**
     * Event listener interface for decoupling backend from UI.
     *
     * PHASE 5: EVENT-DRIVEN UI
     * This interface allows the UI controller to receive events without
     * the backend having direct knowledge of UI classes.
     *
     * Implementors (e.g., ValVoiceController) must wrap UI updates in Platform.runLater().
     */
    public interface ValVoiceEventListener {
        /**
         * Called when a component's status changes.
         *
         * @param component Component identifier (e.g., "xmpp", "bridge", "selfId")
         * @param status Human-readable status text
         * @param ok true if status indicates healthy/connected state
         */
        void onStatusChanged(String component, String status, boolean ok);

        /**
         * Called when user identity is captured from RSO-PAS authentication.
         *
         * @param puuid The player's PUUID (UUID format)
         */
        void onIdentityCaptured(String puuid);

        /**
         * Called when narration statistics are updated.
         *
         * @param messagesSent Total messages narrated
         * @param charactersSent Total characters narrated
         */
        void onStatsUpdated(long messagesSent, long charactersSent);
    }
}
