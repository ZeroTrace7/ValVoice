package com.someone.valvoice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Singleton holder for chat-related runtime state (e.g., the local player's ID).
 * In a future iteration this could manage websockets / XMPP connections, listeners,
 * message dispatching, ignore lists, etc.
 */
public class ChatDataHandler {
    private static final Logger logger = LoggerFactory.getLogger(ChatDataHandler.class);
    private static final ChatDataHandler INSTANCE = new ChatDataHandler();

    private final ChatProperties properties = new ChatProperties();

    // Listeners notified whenever the self player ID changes.
    private final List<Consumer<String>> selfIdListeners = new CopyOnWriteArrayList<>();

    private ChatDataHandler() {
        // Default placeholder self ID; should be set once the real user identity is known.
        properties.setSelfID("SELF_PLAYER_ID");
    }

    public static ChatDataHandler getInstance() { return INSTANCE; }

    public ChatProperties getProperties() { return properties; }

    /**
     * Register a listener that will be invoked when the self player ID changes.
     * Listener will be invoked immediately with current ID if invokeImmediately is true.
     */
    public void registerSelfIdListener(Consumer<String> listener, boolean invokeImmediately) {
        if (listener == null) return;
        selfIdListeners.add(listener);
        if (invokeImmediately) {
            try { listener.accept(properties.getSelfID()); } catch (Exception ignored) { }
        }
    }

    /**
     * Update the current user's chat ID (PUUID or in-game chat identifier).
     */
    public void updateSelfId(String selfId) {
        if (selfId == null || selfId.isBlank()) return;
        String prev = properties.getSelfID();
        if (prev != null && prev.equalsIgnoreCase(selfId)) return; // no change
        logger.info("Updating self chat ID to: {} (previous: {})", selfId, prev);
        properties.setSelfID(selfId);
        // Notify listeners
        for (Consumer<String> c : selfIdListeners) {
            try { c.accept(selfId); } catch (Exception e) { logger.debug("SelfId listener threw", e); }
        }
    }

    /**
     * Initialize by reading Riot lockfile and resolving the self player ID via local API.
     * @param lockfilePath path to Riot lockfile.
     * @return true if lockfile loaded (ID may still be unresolved if endpoint failed).
     */
    public boolean initializeFromLockfile(String lockfilePath) {
        APIHandler api = APIHandler.getInstance();
        boolean loaded = api.loadLockfile(lockfilePath);
        if (!loaded) return false;
        api.resolveSelfPlayerId().ifPresentOrElse(
                id -> {
                    updateSelfId(id);
                    logger.info("Resolved self player ID from local API: {}", id);
                },
                () -> logger.warn("Could not resolve self player ID from /chat/v1/session; keeping existing ID: {}", properties.getSelfID())
        );
        return true;
    }

    /**
     * Attempt to refresh self ID if API handler is already prepared.
     */
    public void refreshSelfId() {
        APIHandler api = APIHandler.getInstance();
        if (!api.isReady()) {
            logger.debug("APIHandler not ready; cannot refresh self ID");
            return;
        }
        api.resolveSelfPlayerId().ifPresent(id -> {
            if (!id.equals(properties.getSelfID())) {
                updateSelfId(id);
                logger.info("Refreshed self player ID: {}", id);
            }
        });
    }

    /**
     * Parse a raw XML stanza into a Message object. Returns null on error.
     */
    public Message parseMessage(String xml) {
        try {
            return new Message(xml);
        } catch (Exception e) {
            logger.debug("Failed to parse chat message stanza", e);
            return null;
        }
    }

    /**
     * Core entry point for a parsed chat Message produced from a <message> XMPP stanza.
     *
     * ✅ AUTOMATIC WORKFLOW (No manual triggers needed!):
     * 1. Player types message in Valorant chat and presses ENTER
     * 2. XMPP bridge detects message
     * 3. This method is called automatically
     * 4. Applies filtering (ignored users, enabled channels, own-message rules)
     * 5. Records statistics
     * 6. Triggers TTS narration AUTOMATICALLY if permitted
     * 7. TTS speaks → VB-Cable → Valorant Open Mic → Teammates hear it!
     *
     * NOTE: No V key pressing or manual intervention required!
     * User must have Valorant Voice Activation set to OPEN MIC for automatic transmission.
     */
    public void message(Message msg) {
        if (msg == null) return;
        Chat chat = Chat.getInstance();
        chat.recordIncoming(msg);
        if (chat.shouldNarrate(msg)) {
            chat.recordNarrated(msg);
            chat.updateMessageStats(msg); // legacy stats
            // Narrate AUTOMATICALLY via controller (no manual trigger needed!)
            try {
                ValVoiceController.narrateMessage(msg);
            } catch (Exception e) {
                logger.debug("Narration failed for message {}", msg, e);
            }
        }
    }
}
