package com.someone.valvoicebackend;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
    private String localPuuid = null; // Cached PUUID for deterministic matching
    private boolean sessionKeysLogged = false; // Phase 2.2: One-time diagnostic flag
    
    // Phase 2.1: Callback for identity capture side-effects
    private java.util.function.Consumer<String> onIdentityCaptured;

    /**
     * Set callback to fire when PUUID is first resolved.
     * Called by ValVoiceBackend to wire UI update + audio routing.
     */
    public void setOnIdentityCaptured(java.util.function.Consumer<String> callback) {
        this.onIdentityCaptured = callback;
    }

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
            localPuuid = null; // Invalidate cache when game exits
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

        // Deterministic matching: ensure we have the local PUUID
        if (localPuuid == null) {
            localPuuid = fetchLocalPuuid(port, password, protocol);
            if (localPuuid == null) {
                logger.debug("[RiotLocalApiPoller] Failed to acquire local PUUID, skipping presence poll.");
                return;
            }
            logger.debug("[RiotLocalApiPoller] Cached local PUUID: {}", localPuuid);

            // Phase 2.1: Propagate identity to central store
            boolean newlySet = ChatDataHandler.getInstance().setSelfId(localPuuid);
            logger.info("[RiotLocalApiPoller] Identity propagated to ChatDataHandler: {}", localPuuid);

            // Fire side-effects (UI update, audio routing) ONLY if newly set
            if (newlySet && onIdentityCaptured != null) {
                onIdentityCaptured.accept(localPuuid);
            }
        }

        String json = fetchPresences(port, password, protocol);
        if (json == null) return;

        String sessionLoopState = extractSessionLoopState(json, localPuuid);

        // If sessionLoopState is null (Riot running but no active game), feed UNKNOWN
        // GameStateManager.updateFromSessionLoopState handles unknown string -> UNKNOWN
        GameStateManager.getInstance().updateFromSessionLoopState(
            sessionLoopState != null ? sessionLoopState : "UNKNOWN");

        logger.debug("[RiotLocalApiPoller] sessionLoopState={}", sessionLoopState);
    }

    // ── HTTP fetch ─────────────────────────────────────────────────────────────

    /**
     * GET /chat/v1/session to acquire the local player's PUUID deterministically.
     */
    private String fetchLocalPuuid(int port, String password, String protocol) {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
            };
            SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());

            String authHeader = "Basic " + Base64.getEncoder().encodeToString(
                ("riot:" + password).getBytes(StandardCharsets.UTF_8));

            URL url = new URL(protocol + "://127.0.0.1:" + port + "/chat/v1/session");
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            
            // Apply connection-scoped SSL bypass
            conn.setSSLSocketFactory(sc.getSocketFactory());
            conn.setHostnameVerifier((h, s) -> true);
            
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", authHeader);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            if (conn.getResponseCode() != 200) return null;

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                JsonObject session = JsonParser.parseString(sb.toString()).getAsJsonObject();

                // Phase 2.2: One-time startup diagnostic — log actual field names
                if (!sessionKeysLogged) {
                    logger.info("[RiotLocalApiPoller] /chat/v1/session keySet: {}", session.keySet());
                    sessionKeysLogged = true;
                }

                if (session.has("puuid")) {
                    String puuid = session.get("puuid").getAsString();

                    // Phase 3.5: Extract display name from /chat/v1/session
                    // Primary: "game_name" (Riot ID display name, populated post-2023 migration)
                    // Fallback: "name" (legacy, deprecated — typically blank on modern accounts)
                    // Runtime proof: name='' game_name='Inocent Child' game_tag='1352'
                    //                across 6 identity captures on June 10-12, 2026.
                    String displayName = null;
                    if (session.has("game_name") && !session.get("game_name").isJsonNull()) {
                        displayName = session.get("game_name").getAsString();
                    }
                    if ((displayName == null || displayName.isBlank())
                            && session.has("name") && !session.get("name").isJsonNull()) {
                        displayName = session.get("name").getAsString();
                    }
                    if (displayName != null && !displayName.isBlank()) {
                        ChatDataHandler.getInstance().setSelfDisplayName(displayName);
                        logger.info("[RiotLocalApiPoller] Self display name: '{}'", displayName);
                    } else {
                        logger.warn("[RiotLocalApiPoller] Both 'game_name' and 'name' fields missing or blank in /chat/v1/session");
                    }

                    return puuid;
                }
            }
        } catch (Exception e) {
            logger.debug("[RiotLocalApiPoller] fetchLocalPuuid failed: {}", e.getMessage());
        }
        return null;
    }

    /**
     * GET /chat/v4/presences with Basic auth riot:<password>.
     * Uses a localhost-only trust-all SSL context.
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

            // Basic auth: riot:<password>
            String authHeader = "Basic " + Base64.getEncoder().encodeToString(
                ("riot:" + password).getBytes(StandardCharsets.UTF_8));

            URL url = new URL(protocol + "://127.0.0.1:" + port + "/chat/v4/presences");
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            
            // Apply connection-scoped SSL bypass
            conn.setSSLSocketFactory(sc.getSocketFactory());
            conn.setHostnameVerifier((hostname, session) -> true);
            
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
     * Extract sessionLoopState from /chat/v4/presences JSON response for a specific PUUID.
     *
     * Response structure:
     *   { "presences": [ { "puuid": "...", "private": "<base64>", ... }, ... ] }
     *
     * @return sessionLoopState string ("INGAME", "MENUS", "PREGAME"), or null if unavailable
     */
    private String extractSessionLoopState(String json, String targetPuuid) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray presences = root.getAsJsonArray("presences");
            if (presences == null || presences.isEmpty()) return null;

            JsonObject targetPresence = null;
            for (JsonElement element : presences) {
                JsonObject p = element.getAsJsonObject();
                if (p.has("puuid") && p.get("puuid").getAsString().equals(targetPuuid)) {
                    targetPresence = p;
                    break;
                }
            }

            if (targetPresence == null || !targetPresence.has("private")) return null;

            String privateB64 = targetPresence.get("private").getAsString();
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
