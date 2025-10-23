package com.someone.valvoice;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Polls Riot local chat REST endpoint for new messages and forwards them to a consumer
 * by synthesizing XML stanzas compatible with existing Message parser.
 *
 * NOTE: This is a heuristic implementation; field names / endpoints may change with patches.
 */
public class ChatListenerService {
    private static final Logger logger = LoggerFactory.getLogger(ChatListenerService.class);

    private final APIHandler api = APIHandler.getInstance();
    private final Gson gson = new Gson();
    private final Consumer<String> xmlConsumer;
    private volatile boolean running = false;
    private Thread worker;

    private final Set<String> seenIds = new HashSet<>(); // track message IDs to avoid duplicates
    private String lastMessageId = null; // optional progressive tracking
    private int consecutiveFailures = 0; // track consecutive API failures
    private static final int MAX_FAILURES_BEFORE_WARN = 5;
    private static final int MAX_POLL_INTERVAL_MS = 30_000; // max 30 seconds between polls

    // Track startup time to ignore old messages
    private long startupTimeMs = 0;
    private boolean initialLoadComplete = false;

    // Presence-derived fallback domain cache to avoid excessive API calls
    private volatile long lastPresenceProbeAtMs = 0;
    private static final long PRESENCE_PROBE_CACHE_MS = 5_000; // 5 seconds
    private volatile String lastPresenceDomain = "prod.chat"; // default

    public ChatListenerService(Consumer<String> xmlConsumer) {
        this.xmlConsumer = xmlConsumer;
    }

    public void start() {
        if (running) return;
        running = true;
        startupTimeMs = System.currentTimeMillis(); // Record startup time
        initialLoadComplete = false;
        consecutiveFailures = 0; // reset on start
        worker = new Thread(this::loop, "ValorantChatPoller");
        worker.setDaemon(true);
        worker.start();
        logger.info("ChatListenerService started at {}. Will skip old messages from chat history.", startupTimeMs);
    }

    public void stop() {
        running = false;
        if (worker != null) worker.interrupt();
        logger.info("ChatListenerService stopped");
    }

    private void loop() {
        while (running) {
            try {
                pollOnce();
            } catch (Exception e) {
                logger.debug("Chat poll failed", e);
            }
            // Exponential backoff based on consecutive failures
            int sleepTime = calculateBackoffTime();
            try {
                Thread.sleep(sleepTime);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private int calculateBackoffTime() {
        if (consecutiveFailures == 0) {
            return 2000; // Normal polling: 2 seconds
        } else if (consecutiveFailures < MAX_FAILURES_BEFORE_WARN) {
            // Gradual backoff: 2s, 4s, 6s, 8s, 10s
            return Math.min(2000 * (consecutiveFailures + 1), 10_000);
        } else {
            // After many failures, poll less frequently to reduce spam
            return Math.min(5000 * (consecutiveFailures - MAX_FAILURES_BEFORE_WARN + 2), MAX_POLL_INTERVAL_MS);
        }
    }

    private void pollOnce() {
        if (!api.isReady()) {
            consecutiveFailures++;
            return;
        }
        // Try v6 then fallback to v5
        String[] paths = {"/chat/v6/messages", "/chat/v5/messages"};
        String json = null;
        for (String p : paths) {
            json = api.rawGet(p).orElse(null);
            if (json != null) break;
        }

        if (json == null) {
            consecutiveFailures++;
            // Only log warning after several consecutive failures to avoid spam
            if (consecutiveFailures == MAX_FAILURES_BEFORE_WARN) {
                logger.warn("Chat API endpoints not responding after {} attempts. Valorant may not be running. Reducing poll frequency.", MAX_FAILURES_BEFORE_WARN);
            } else if (consecutiveFailures > MAX_FAILURES_BEFORE_WARN && consecutiveFailures % 10 == 0) {
                logger.debug("Chat API still unavailable ({} failures). Polling every {} seconds.",
                    consecutiveFailures, calculateBackoffTime() / 1000);
            }
            return;
        }

        // Success - reset failure counter
        if (consecutiveFailures >= MAX_FAILURES_BEFORE_WARN) {
            logger.info("Chat API connection restored after {} failures", consecutiveFailures);
        }
        consecutiveFailures = 0;

        JsonObject root;
        try {
            root = gson.fromJson(json, JsonObject.class);
        } catch (Exception e) {
            logger.debug("Invalid JSON from chat messages endpoint", e);
            return;
        }
        if (root == null) return;
        JsonArray messages = null;
        if (root.has("messages") && root.get("messages").isJsonArray()) {
            messages = root.getAsJsonArray("messages");
        } else if (root.has("msgs") && root.get("msgs").isJsonArray()) { // alternative
            messages = root.getAsJsonArray("msgs");
        } else if (root.has("data") && root.get("data").isJsonArray()) { // some builds use 'data'
            messages = root.getAsJsonArray("data");
        }
        if (messages == null) {
            logger.debug("No messages array found in API response. Keys present: {}", root.keySet());
            return;
        }

        // On first poll, mark all existing messages as "seen" but don't narrate them
        if (!initialLoadComplete) {
            int skippedCount = 0;
            for (JsonElement el : messages) {
                if (!el.isJsonObject()) continue;
                JsonObject m = el.getAsJsonObject();
                String mid = getString(m, "id", getString(m, "messageId", null));
                if (mid != null) {
                    seenIds.add(mid);
                    lastMessageId = mid;
                    skippedCount++;
                }
            }
            initialLoadComplete = true;
            logger.info("Initial chat history load complete. Skipped {} old messages. Now listening for new messages only.", skippedCount);
            return; // Don't process any messages from the first poll
        }

        // Log raw API response for debugging (only after initial load to avoid spam)
        logger.debug("\uD83D\uDCE5 Raw chat API response (abbrev): {}", abbreviate(json, 600));
        logger.debug("Found {} messages in API response", messages.size());

        // After initial load, process only NEW messages
        for (JsonElement el : messages) {
            if (!el.isJsonObject()) continue;
            JsonObject m = el.getAsJsonObject();
            String mid = getString(m, "id", getString(m, "messageId", null));
            if (mid != null && seenIds.contains(mid)) continue;
            String body = coalesceString(m, "body", "msg", "message", "content");
            if (body == null || body.isBlank()) continue;
            String from = coalesceString(m, "from", "sender", "fromId", "author", "participantId");
            if (from == null) from = "unknown";
            String cid = getChatChannel(m); // Use comprehensive channel detection with all fallbacks
            if (cid == null) cid = ""; // fallback to empty string if no channel found
            String type = getString(m, "type", "groupchat");

            logger.info("\uD83D\uDD14 NEW MESSAGE DETECTED:");
            logger.info("  - Raw from: '{}'", from);
            logger.info("  - Raw cid: '{}'", cid);
            logger.info("  - Raw type: '{}'", type);
            logger.info("  - Body: '{}'", body);
            logger.info("  - Message ID: '{}'", mid);
            logger.debug("  - FULL MESSAGE JSON: {}", abbreviate(m.toString(), 1200));

            // Extract username from 'from' field if it contains @ or is a full JID
            String username = from;
            if (from.contains("@")) {
                username = from.substring(0, from.indexOf("@"));
                logger.debug("  - Extracted username '{}' from JID '{}'", username, from);
            }

            // Basic classification heuristics via synthetic domain
            String domain = buildDomainForCid(cid);
            if ("prod.chat".equals(domain)) {
                // Fallback: probe presence to infer a better domain (TEAM/PARTY/PREGAME)
                String inferred = inferDomainFromPresence();
                if (!"prod.chat".equals(inferred)) {
                    logger.info("  - Fallback domain from presence: '{}' (cid was '{}')", inferred, cid);
                    domain = inferred;
                } else {
                    logger.debug("  - Using WHISPER fallback domain (prod.chat)");
                }
            }

            String syntheticFrom = username + "@" + domain + ".pvp.net"; // consistent with earlier parser expectations

            logger.info("  - Built domain: '{}'", domain);
            logger.info("  - Synthesized from: '{}'", syntheticFrom);

            String escapedBody = escapeXml(body); // ensure valid XML
            String xml = "<message from='" + syntheticFrom + "' type='" + type + "'><body>" + escapedBody + "</body></message>";

            logger.info("  - Final XML: {}", xml);
            logger.info("\uD83D\uDD14 END MESSAGE DETAILS\n");

            if (mid != null) {
                seenIds.add(mid);
                lastMessageId = mid;
            }
            xmlConsumer.accept(xml);
        }
        // Prune seenIds if it grows too large
        if (seenIds.size() > 5000) {
            seenIds.clear();
            if (lastMessageId != null) seenIds.add(lastMessageId);
            logger.debug("Pruned seen message ID set");
        }
    }

    /**
     * Extract chat channel identifier from message with comprehensive fallback strategies
     */
    private String getChatChannel(JsonObject m) {
        // Try multiple possible field names
        String cid = getString(m, "cid", null);
        if (cid != null) {
            logger.debug("\u2705 Found channel in 'cid' field: {}", cid);
            return cid;
        }

        cid = getString(m, "channel", null);
        if (cid != null) {
            logger.debug("\u2705 Found channel in 'channel' field: {}", cid);
            return cid;
        }

        cid = getString(m, "channelId", null);
        if (cid != null) {
            logger.debug("\u2705 Found channel in 'channelId' field: {}", cid);
            return cid;
        }

        cid = getString(m, "conversationId", null);
        if (cid != null) {
            logger.debug("\u2705 Found channel in 'conversationId' field: {}", cid);
            return cid;
        }

        cid = getString(m, "room", null);
        if (cid != null) {
            logger.debug("\u2705 Found channel in 'room' field: {}", cid);
            return cid;
        }

        // Nested structures occasionally used by some builds
        if (m.has("conversation") && m.get("conversation").isJsonObject()) {
            JsonObject conv = m.getAsJsonObject("conversation");
            cid = getString(conv, "id", getString(conv, "cid", null));
            if (cid != null) {
                logger.debug("\u2705 Found channel in 'conversation.id/cid': {}", cid);
                return cid;
            }
        }

        // Check if it's embedded in 'from' or 'to' fields
        String fromField = getString(m, "from", "");
        if (fromField.contains("ares-parties") || fromField.contains("ares-pregame")
            || fromField.contains("ares-coregame")) {
            logger.debug("\u2705 Found channel embedded in 'from' field: {}", fromField);
            return fromField;
        }

        String toField = getString(m, "to", "");
        if (toField.contains("ares-parties") || toField.contains("ares-pregame")
            || toField.contains("ares-coregame")) {
            logger.debug("\u2705 Found channel embedded in 'to' field: {}", toField);
            return toField;
        }

        logger.warn("\u26A0\uFE0F No channel identifier found in message. Full JSON: {}", abbreviate(m.toString(), 1200));
        return null;
    }

    private String buildDomainForCid(String cid) {
        if (cid == null) {
            logger.debug("\u26A0\uFE0F cid is null, defaulting to prod.chat (WHISPER)");
            return "prod.chat"; // fallback -> whisper classification if type=chat
        }
        String lower = cid.toLowerCase();

        // Party chat
        if (lower.startsWith("party") || lower.contains("ares-parties")) {
            logger.debug("\u2705 Detected PARTY chat from cid: {}", cid);
            return "ares-parties";
        }

        // Pregame (agent select)
        if (lower.contains("pregame") || lower.startsWith("ares-pregame")) {
            logger.debug("\u2705 Detected PREGAME/TEAM chat from cid: {}", cid);
            return "ares-pregame";
        }

        // In-game (coregame) - team or all chat
        if (lower.contains("coregame") || lower.contains("match") || lower.startsWith("ares-coregame")) {
            logger.debug("\u2705 Detected IN-GAME TEAM/ALL chat from cid: {}", cid);
            return "ares-coregame";
        }

        // If cid contains @ it's likely a direct message JID
        if (cid.contains("@")) {
            logger.debug("\u2139\uFE0F cid appears to be a player JID (direct message): {}", cid);
        } else {
            logger.debug("\u26A0\uFE0F Unknown cid format, defaulting to prod.chat: {}", cid);
        }

        return "prod.chat"; // unknown -> whisper if type=chat
    }

    /**
     * Presence-based fallback: probe /chat/v4/presences to infer current loop state and derive a domain.
     * Caches result briefly to avoid spamming the local API.
     */
    private String inferDomainFromPresence() {
        long now = System.currentTimeMillis();
        if (now - lastPresenceProbeAtMs < PRESENCE_PROBE_CACHE_MS) {
            return lastPresenceDomain;
        }
        lastPresenceProbeAtMs = now;

        try {
            String body = api.rawGet("/chat/v4/presences").orElse(null);
            if (body == null) return lastPresenceDomain;
            JsonObject root = gson.fromJson(body, JsonObject.class);
            if (root == null || !root.has("presences") || !root.get("presences").isJsonArray()) return lastPresenceDomain;
            String selfId = ChatDataHandler.getInstance().getProperties().getSelfID();
            for (JsonElement el : root.getAsJsonArray("presences")) {
                if (!el.isJsonObject()) continue;
                JsonObject p = el.getAsJsonObject();
                String puuid = getString(p, "puuid", null);
                if (puuid == null || selfId == null || !selfId.equalsIgnoreCase(puuid)) continue;
                String privB64 = getString(p, "private", null);
                if (privB64 == null) continue;
                String jsonPriv = new String(Base64.getDecoder().decode(privB64));
                JsonObject priv = gson.fromJson(jsonPriv, JsonObject.class);
                if (priv == null) continue;
                String loop = getString(priv, "sessionLoopState", "");
                String partyId = getString(priv, "partyId", null);
                if ("INGAME".equalsIgnoreCase(loop)) {
                    lastPresenceDomain = "ares-coregame";
                    logger.info("Presence inference: INGAME detected -> using domain '{}'", lastPresenceDomain);
                    return lastPresenceDomain;
                } else if ("PREGAME".equalsIgnoreCase(loop)) {
                    lastPresenceDomain = "ares-pregame";
                    logger.info("Presence inference: PREGAME detected -> using domain '{}'", lastPresenceDomain);
                    return lastPresenceDomain;
                } else if (("MENUS".equalsIgnoreCase(loop) || "IDLE".equalsIgnoreCase(loop)) && partyId != null && !partyId.isBlank()) {
                    lastPresenceDomain = "ares-parties";
                    logger.info("Presence inference: PARTY in menus detected -> using domain '{}'", lastPresenceDomain);
                    return lastPresenceDomain;
                }
            }
        } catch (Exception e) {
            logger.debug("Presence inference failed (non-fatal)", e);
        }
        return lastPresenceDomain;
    }

    private String getString(JsonObject o, String key, String def) {
        if (o == null || !o.has(key)) return def;
        JsonElement el = o.get(key);
        if (el == null || el.isJsonNull()) return def;
        try { return el.getAsString(); } catch (Exception e) { return def; }
    }

    private String coalesceString(JsonObject o, String... keys) {
        if (o == null || keys == null) return null;
        for (String k : keys) {
            String v = getString(o, k, null);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private String abbreviate(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 3)) + "...";
    }

    /**
     * Escape special XML characters to prevent parsing errors
     */
    private String escapeXml(String text) {
        if (text == null) return null;
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }
}
