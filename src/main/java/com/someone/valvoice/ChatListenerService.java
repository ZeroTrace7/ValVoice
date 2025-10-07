package com.someone.valvoice;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
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

    public ChatListenerService(Consumer<String> xmlConsumer) {
        this.xmlConsumer = xmlConsumer;
    }

    public void start() {
        if (running) return;
        running = true;
        worker = new Thread(this::loop, "ValorantChatPoller");
        worker.setDaemon(true);
        worker.start();
        logger.info("ChatListenerService started");
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
            try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
        }
    }

    private void pollOnce() {
        if (!api.isReady()) return;
        // Try v6 then fallback to v5
        String[] paths = {"/chat/v6/messages", "/chat/v5/messages"};
        String json = null;
        for (String p : paths) {
            json = api.rawGet(p).orElse(null);
            if (json != null) break;
        }
        if (json == null) return;
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

