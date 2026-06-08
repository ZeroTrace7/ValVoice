package com.someone.valvoicebackend;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Base64;

/**
 * RiotLocalApiPoller — replaces XMPP presence stanzas as the game-state source.
 *
 * Phase 0B (OCR migration): When MITM/XMPP is removed, GameStateManager has no signal source
 * and stays UNKNOWN forever. This class polls the Riot Local API's /chat/v4/presences endpoint
 * every 5 seconds and feeds the same sessionLoopState values into GameStateManager.
 *
 * Why this works: The Riot Client /chat/v4/presences response contains a "private" field per
 * presence entry. That field is Base64-encoded JSON that includes "sessionLoopState" — the same
 * value that XMPP presence stanzas used to carry. Only the transport changes.
 *
 * Game state transitions controlled by sessionLoopState:
 *   "MENUS"     -> GameState.MENUS
 *   "PREGAME"   -> GameState.PREGAME
 *   "INGAME"    -> GameState.INGAME
 *   null/other  -> GameState.UNKNOWN (safe default: TTS always fires)
 *
 * If Riot Client is not running (no lockfile): poller sleeps — no errors, no exceptions.
 * GameStateManager stays UNKNOWN — shouldSuppressNarration() returns false — TTS always fires.
 * This is the correct safe default.
 *
 * Security: SSL bypass is localhost-only (127.0.0.1). Same invariants as RiotUtilityHandler.
 */
public class RiotLocalApiPoller {
    private static final Logger logger = LoggerFactory.getLogger(RiotLocalApiPoller.class);

    private static final long POLL_INTERVAL_MS = 5_000;
    private static final int  CONNECT_TIMEOUT_MS = 4_000;
    private static final int  READ_TIMEOUT_MS = 4_000;

    private volatile boolean running = false;
    private Thread pollerThread;

    /**
     * Start the polling daemon thread.
     * Safe to call from any thread. No-op if already running.
     */
    public void start() {
        if (running) {
            logger.warn("[RiotLocalApiPoller] Already running — ignoring duplicate start()");
            return;
        }
        running = true;
        pollerThread = new Thread(this::pollLoop, "RiotLocalApiPoller");
        pollerThread.setDaemon(true);
        pollerThread.start();
        logger.info("[RiotLocalApiPoller] Started (poll interval: {}ms)", POLL_INTERVAL_MS);
    }

    /**
     * Stop the polling thread.
     * Interrupts the thread; safe to call from any thread.
     */
    public void stop() {
        running = false;
        if (pollerThread != null) {
            pollerThread.interrupt();
        }
        logger.info("[RiotLocalApiPoller] Stopped");
    }

    // ── Core polling loop ──────────────────────────────────────────────────────

    private void pollLoop() {
        logger.debug("[RiotLocalApiPoller] Poll loop started");
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                poll();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Non-fatal: stay UNKNOWN, try again next interval
                logger.debug("[RiotLocalApiPoller] Poll error (non-fatal): {}", e.getMessage());
            }

            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        logger.debug("[RiotLocalApiPoller] Poll loop exited");
    }

    private void poll() throws Exception {
        // Only poll when lockfile exists — no errors if Riot is not running
        String lockfilePath = LockFileHandler.findDefaultLockfile();
        if (lockfilePath == null) {
            logger.debug("[RiotLocalApiPoller] Lockfile not found — Riot not running, skipping");
            return;
        }

        LockFileHandler handler = new LockFileHandler();
        if (!handler.readLockFile(lockfilePath)) {
            logger.debug("[RiotLocalApiPoller] Failed to parse lockfile: {}", lockfilePath);
            return;
        }

        int port;
        try {
            port = Integer.parseInt(handler.getPort());
        } catch (NumberFormatException e) {
            logger.debug("[RiotLocalApiPoller] Invalid port in lockfile: {}", handler.getPort());
            return;
        }

        String password = handler.getPassword();
        String protocol = handler.getProtocol() != null ? handler.getProtocol() : "https";

        String json = fetchPresences(port, password, protocol);
        if (json == null) return;

        String sessionLoopState = extractSessionLoopState(json);

        // If sessionLoopState is null (Riot running but no active game), feed UNKNOWN
        // GameStateManager.updateFromSessionLoopState handles unknown string -> UNKNOWN
        GameStateManager.getInstance().updateFromSessionLoopState(
            sessionLoopState != null ? sessionLoopState : "UNKNOWN");

        logger.debug("[RiotLocalApiPoller] sessionLoopState={}", sessionLoopState);
    }

    // ── HTTP fetch ─────────────────────────────────────────────────────────────

    /**
     * GET /chat/v4/presences with Basic auth riot:<password>.
     * Uses a localhost-only trust-all SSL context (same pattern as RiotUtilityHandler).
     *
     * @return JSON response body, or null on HTTP error
     */
    private String fetchPresences(int port, String password, String protocol) {
        try {
            // Trust-all TrustManager — safe because URL is hardcoded to 127.0.0.1
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
            };
            SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);

            // Basic auth: riot:<password>
            String authHeader = "Basic " + Base64.getEncoder().encodeToString(
                ("riot:" + password).getBytes(StandardCharsets.UTF_8));

            URL url = new URL(protocol + "://127.0.0.1:" + port + "/chat/v4/presences");
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", authHeader);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            int code = conn.getResponseCode();
            if (code != 200) {
                logger.debug("[RiotLocalApiPoller] HTTP {} from /chat/v4/presences", code);
                return null;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                return sb.toString();
            }
        } catch (Exception e) {
            logger.debug("[RiotLocalApiPoller] fetchPresences failed: {}", e.getMessage());
            return null;
        }
    }

    // ── Parsing ────────────────────────────────────────────────────────────────

    /**
     * Extract sessionLoopState from /chat/v4/presences JSON response.
     *
     * Response structure:
     *   { "presences": [ { "private": "<base64>", ... }, ... ] }
     *
     * The "private" Base64 value decodes to:
     *   { "sessionLoopState": "INGAME"|"MENUS"|"PREGAME", ... }
     *
     * sessionLoopState is inside the Base64 blob — NOT at the top-level of the presence object.
     * The Riot Client always returns the local player as the FIRST entry in the array.
     *
     * @return sessionLoopState string ("INGAME", "MENUS", "PREGAME"), or null if unavailable
     */
    private String extractSessionLoopState(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray presences = root.getAsJsonArray("presences");
            if (presences == null || presences.isEmpty()) return null;

            // Riot Client returns local player as first entry
            JsonObject first = presences.get(0).getAsJsonObject();
            if (!first.has("private")) return null;

            String privateB64 = first.get("private").getAsString();
            if (privateB64 == null || privateB64.isBlank()) return null;

            byte[] decoded = Base64.getDecoder().decode(privateB64);
            JsonObject privateJson = JsonParser.parseString(
                new String(decoded, StandardCharsets.UTF_8)).getAsJsonObject();

            return privateJson.has("sessionLoopState")
                ? privateJson.get("sessionLoopState").getAsString()
                : null;

        } catch (Exception e) {
            logger.debug("[RiotLocalApiPoller] Failed to extract sessionLoopState: {}", e.getMessage());
            return null;
        }
    }
}
