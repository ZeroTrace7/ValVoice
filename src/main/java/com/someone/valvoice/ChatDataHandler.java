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
        logger.info("✓ Message will be narrated: ({}) from {}", message.getMessageType(), message.getUserId());


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

        // Update UI statistics
        Platform.runLater(() -> {
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

            Platform.runLater(() -> {
                ValVoiceController c = ValVoiceController.getLatestInstance();
                if (c != null && c.addIgnoredPlayer != null) {
                    c.addIgnoredPlayer.getItems().add(playerName);
                }
            });
        }
    }

    private String expandShortForms(String text) {
        if (text == null) return "";
        return text
            .replace("gg", "good game")
            .replace("GG", "good game")
            .replace("wp", "well played")
            .replace("WP", "well played")
            .replace("gj", "good job")
            .replace("GJ", "good job")
            .replace("ty", "thank you")
            .replace("TY", "thank you")
            .replace("np", "no problem")
            .replace("NP", "no problem")
            .replace("omw", "on my way")
            .replace("OMW", "on my way")
            .replace("brb", "be right back")
            .replace("BRB", "be right back");
    }

    private String getPlayerName(String userId) {
        if (userId == null || userId.isEmpty()) return "Unknown";
        Chat chat = Chat.getInstance();
        return chat.getDisplayName(userId).orElse(userId);
    }
}

