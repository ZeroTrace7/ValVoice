package com.someone.valvoice;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Singleton holder for chat-related runtime state.
 * Matches ValNarrator ChatDataHandler pattern with comprehensive message filtering and statistics.
 */
public class ChatDataHandler {
    private static final Logger logger = LoggerFactory.getLogger(ChatDataHandler.class);
    private static final ChatDataHandler INSTANCE = new ChatDataHandler();

    private final ChatProperties properties = new ChatProperties();
    private final APIHandler apiHandler;
    private final List<Consumer<String>> selfIdListeners = new CopyOnWriteArrayList<>();

    private ChatDataHandler() {
        apiHandler = APIHandler.getInstance();
        properties.setSelfID("SELF_PLAYER_ID");
        logger.info("ChatDataHandler initialized");
    }

    public static ChatDataHandler getInstance() { return INSTANCE; }

    public static void generateSingleton() {
        // Singleton already created in static initializer
    }

    public ChatProperties getProperties() { return properties; }

    public APIHandler getAPIHandler() {
        return apiHandler;
    }

    public void registerSelfIdListener(Consumer<String> listener, boolean invokeImmediately) {
        if (listener == null) return;
        selfIdListeners.add(listener);
        if (invokeImmediately) {
            try { listener.accept(properties.getSelfID()); } catch (Exception ignored) { }
        }
    }

    public void updateSelfId(String selfId) {
        if (selfId == null || selfId.isBlank()) return;
        String prev = properties.getSelfID();
        if (prev != null && prev.equalsIgnoreCase(selfId)) return;
        logger.info("Updating self chat ID to: {} (previous: {})", selfId, prev);
        properties.setSelfID(selfId);
        for (Consumer<String> c : selfIdListeners) {
            try { c.accept(selfId); } catch (Exception e) { logger.debug("SelfId listener threw", e); }
        }
    }

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

    public Message parseMessage(String xml) {
        try {
            return new Message(xml);
        } catch (Exception e) {
            logger.debug("Failed to parse chat message stanza", e);
            return null;
        }
    }

    /**
     * Core message processing entry point - matches ValNarrator logic exactly.
     * Applies comprehensive filtering and triggers TTS automatically.
     */
    public void message(Message message) {
        if (message == null) return;

        Chat chat = Chat.getInstance();

        // Record incoming message first (for statistics)
        chat.recordIncoming(message);

        // Let shouldNarrate() handle ALL filtering logic (no double filtering!)
        if (!chat.shouldNarrate(message)) {
            // Log WHY the message was filtered
            if (chat.isDisabled()) {
                logger.debug("Message filtered: app is disabled");
            } else if (chat.isIgnored(message.getUserId())) {
                logger.debug("Message filtered: user {} is ignored", message.getUserId());
            } else if (message.getMessageType() == ChatMessageType.WHISPER && !chat.isWhispersEnabled()) {
                logger.debug("Message filtered: whispers are disabled");
            } else if (message.getMessageType() == ChatMessageType.PARTY && !chat.isPartyState()) {
                logger.debug("Message filtered: party messages are disabled");
            } else if (message.getMessageType() == ChatMessageType.TEAM && !chat.isTeamState()) {
                logger.debug("Message filtered: team messages are disabled");
            } else if (message.getMessageType() == ChatMessageType.ALL && !chat.isAllState()) {
                logger.debug("Message filtered: all chat is disabled");
            } else if (message.isOwnMessage() && !chat.isIncludeOwnMessages()) {
                logger.debug("Message filtered: own messages are disabled");
            } else {
                logger.debug("Message filtered: unknown reason");
            }
            return;
        }

        // Message passed all filters!
        logger.info("\u2713 Message will be narrated: ({}) from {}", message.getMessageType(), message.getUserId());


        // Record narration statistics
        chat.recordNarrated(message);
        chat.updateMessageStats(message);

        // Clean message content
        String finalBody = message.getContent();
        if (finalBody != null) {
            finalBody = finalBody.replace("/", "").replace("\\", "");
        }
        final String textToSpeak = finalBody;

        // Asynchronously narrate (matches ValNarrator CompletableFuture pattern)
        CompletableFuture.runAsync(() -> {
            try {
                String expandedText = expandShortForms(textToSpeak);
                Message expandedMessage = new Message(message, expandedText);
                ValVoiceController.narrateMessage(expandedMessage);
            } catch (Exception e) {
                logger.warn("Failed to narrate message: {}", e.getMessage());
            }
        });

        // Update UI statistics (safe for headless tests)
        safeFx(() -> {
            ValVoiceController c = ValVoiceController.getLatestInstance();
            if (c != null) {
                c.setMessagesSentLabel(chat.getNarratedMessages());
                c.setCharactersNarratedLabel(chat.getNarratedCharacters());
            }
        });

        // Track player ID to name mapping
        if (!chat.getPlayerIDTable().containsKey(message.getUserId())) {
            final String playerID = message.getUserId();
            final String playerName = getPlayerName(playerID).trim();
            chat.getPlayerIDTable().put(playerID, playerName);
            chat.getPlayerNameTable().put(playerName, playerID);

            safeFx(() -> {
                ValVoiceController c = ValVoiceController.getLatestInstance();
                if (c != null && c.addIgnoredPlayer != null) {
                    c.addIgnoredPlayer.getItems().add(playerName);
                }
            });
        }
    }

    // Execute runnable on JavaFX thread if available; ignore if toolkit not initialized (e.g., unit tests)
    private void safeFx(Runnable r) {
        try {
            Platform.runLater(r);
        } catch (IllegalStateException ex) {
            // JavaFX toolkit not initialized; skip UI update in headless context
            logger.trace("Skipping FX update (toolkit not initialized)");
        } catch (Throwable t) {
            logger.trace("FX update skipped due to error", t);
        }
    }

    /**
     * Expands common gaming shortforms to full phrases for better TTS narration.
     * Uses case-insensitive regex with word boundaries to prevent false matches.
     * Comprehensive list includes Valorant-specific and general gaming terms.
     */
    private String expandShortForms(String text) {
        if (text == null || text.isEmpty()) return "";

        // Use replaceAll with word boundaries for accurate matching
        // (?i) = case insensitive, \\b = word boundary
        String expanded = text;

        // Common gaming shortforms (high priority)
        expanded = expanded.replaceAll("(?i)\\bGGWP\\b", "Good game, well played");
        expanded = expanded.replaceAll("(?i)\\bGG\\b", "Good game");
        expanded = expanded.replaceAll("(?i)\\bWP\\b", "Well played");
        expanded = expanded.replaceAll("(?i)\\bGLHF\\b", "Good luck, have fun");
        expanded = expanded.replaceAll("(?i)\\bGL\\b", "Good luck");
        expanded = expanded.replaceAll("(?i)\\bGJ\\b", "Good job");
        expanded = expanded.replaceAll("(?i)\\bNJ\\b", "Nice job");
        expanded = expanded.replaceAll("(?i)\\bNT\\b", "Nice try");
        expanded = expanded.replaceAll("(?i)\\bNS\\b", "Nice shot");
        expanded = expanded.replaceAll("(?i)\\bMB\\b", "My bad");
        expanded = expanded.replaceAll("(?i)\\bTY\\b", "Thank you");
        expanded = expanded.replaceAll("(?i)\\bNP\\b", "No problem");
        expanded = expanded.replaceAll("(?i)\\bSRY\\b", "Sorry");
        expanded = expanded.replaceAll("(?i)\\bPLS\\b", "Please");

        // Valorant-specific terms
        expanded = expanded.replaceAll("(?i)\\bGH\\b", "Good half");
        expanded = expanded.replaceAll("(?i)\\bHP\\b", "Health");
        expanded = expanded.replaceAll("(?i)\\bTPED\\b", "Teleported");
        expanded = expanded.replaceAll("(?i)\\bDM\\b", "Deathmatch");
        expanded = expanded.replaceAll("(?i)\\bUNR\\b", "Unrated");
        expanded = expanded.replaceAll("(?i)\\bCOMP\\b", "Competitive");

        // Common internet slang
        expanded = expanded.replaceAll("(?i)\\bBRB\\b", "Be right back");
        expanded = expanded.replaceAll("(?i)\\bOMW\\b", "On my way");
        expanded = expanded.replaceAll("(?i)\\bBTW\\b", "By the way");
        expanded = expanded.replaceAll("(?i)\\bNVM\\b", "Nevermind");
        expanded = expanded.replaceAll("(?i)\\bLOL\\b", "Laughing out loud");
        expanded = expanded.replaceAll("(?i)\\bFR\\b", "For real");
        expanded = expanded.replaceAll("(?i)\\bIC\\b", "I see");
        expanded = expanded.replaceAll("(?i)\\bIKR\\b", "I know right");
        expanded = expanded.replaceAll("(?i)\\bIG\\b", "I guess");
        expanded = expanded.replaceAll("(?i)\\bSMH\\b", "Shake my head");
        expanded = expanded.replaceAll("(?i)\\bWDYM\\b", "What do you mean");
        expanded = expanded.replaceAll("(?i)\\bEZ\\b", "Easy");
        expanded = expanded.replaceAll("(?i)\\bNC\\b", "Nice");

        // Pattern for "50hp" → "50 health"
        expanded = expanded.replaceAll("(?i)(\\d+)hp\\b", "$1 health");

        return expanded;
    }

    private String getPlayerName(String userId) {
        if (userId == null || userId.isEmpty()) return "Unknown";
        Chat chat = Chat.getInstance();
        return chat.getDisplayName(userId).orElse(userId);
    }
}
