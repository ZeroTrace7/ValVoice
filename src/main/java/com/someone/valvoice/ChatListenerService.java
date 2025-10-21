package com.someone.valvoice;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        }
        if (messages == null) return;

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

        // After initial load, process only NEW messages
        for (JsonElement el : messages) {
            if (!el.isJsonObject()) continue;
            JsonObject m = el.getAsJsonObject();
            String mid = getString(m, "id", getString(m, "messageId", null));
            if (mid != null && seenIds.contains(mid)) continue;
            String body = getString(m, "body", getString(m, "msg", null));
            if (body == null || body.isBlank()) continue;
            String from = getString(m, "from", getString(m, "sender", getString(m, "fromId", null)));
            if (from == null) from = "unknown";
            String cid = getString(m, "cid", getString(m, "channel", ""));
            String type = getString(m, "type", "groupchat");

            // Basic classification heuristics via synthetic domain
            String domain = buildDomainForCid(cid);
            String syntheticFrom = from + "@" + domain + ".pvp.net"; // consistent with earlier parser expectations

            String escapedBody = HtmlEscape.escapeHtml(body); // ensure valid XML
            String xml = "<message from='" + syntheticFrom + "' type='" + type + "'><body>" + escapedBody + "</body></message>";
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

    private String buildDomainForCid(String cid) {
        if (cid == null) return "prod.chat"; // fallback -> whisper classification if type=chat
        String lower = cid.toLowerCase();
        if (lower.startsWith("party")) return "ares-parties";
        if (lower.contains("pregame") || lower.startsWith("ares-pregame")) return "ares-pregame";
        if (lower.contains("coregame") || lower.contains("match") || lower.startsWith("ares-coregame")) return "ares-coregame";
        return "prod.chat"; // unknown -> whisper if type=chat
    }

    private String getString(JsonObject o, String key, String def) {
        if (o == null || !o.has(key)) return def;
        JsonElement el = o.get(key);
        if (el == null || el.isJsonNull()) return def;
        try { return el.getAsString(); } catch (Exception e) { return def; }
    }
}
