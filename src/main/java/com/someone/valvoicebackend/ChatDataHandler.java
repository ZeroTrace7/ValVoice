package com.someone.valvoicebackend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Handles chat data and player identity resolution.
 * Manages self player ID and provides listeners for ID changes.
 */
public class ChatDataHandler {
    private static final Logger logger = LoggerFactory.getLogger(ChatDataHandler.class);
    private static final ChatDataHandler INSTANCE = new ChatDataHandler();

    private volatile String selfId = null;
    private final List<Consumer<String>> selfIdListeners = new CopyOnWriteArrayList<>();

    private ChatDataHandler() {}

    public static ChatDataHandler getInstance() {
        return INSTANCE;
    }

    /**
     * Initialize from Riot lockfile
     * @param lockfilePath path to the lockfile
     * @return true if successfully initialized
     */
    public boolean initializeFromLockfile(String lockfilePath) {
        try {
            Path path = Paths.get(lockfilePath);
            if (!Files.exists(path)) {
                logger.warn("Lockfile not found at: {}", lockfilePath);
                return false;
            }

            // Read lockfile content
            List<String> lines = Files.readAllLines(path);
            if (lines.isEmpty()) {
                logger.warn("Lockfile is empty");
                return false;
            }

            String line = lines.get(0);
            String[] parts = line.split(":");
            if (parts.length < 4) {
                logger.warn("Invalid lockfile format");
                return false;
            }

            // Lockfile format: name:pid:port:password:protocol
            String protocol = parts.length > 4 ? parts[4] : "https";
            int port = Integer.parseInt(parts[2]);
            String password = parts[3];

            logger.info("Lockfile parsed - port: {}, protocol: {}", port, protocol);

            // Initialize connection and resolve self ID
            resolveSelfId(port, password, protocol);

            return true;
        } catch (IOException e) {
            logger.error("Failed to read lockfile", e);
            return false;
        } catch (Exception e) {
            logger.error("Failed to initialize from lockfile", e);
            return false;
        }
    }

    /**
     * Resolve self player ID from Riot local API
     */
    private void resolveSelfId(int port, String password, String protocol) {
        try {
            // Use RiotUtilityHandler to get self player ID
            String resolvedId = RiotUtilityHandler.resolveSelfPlayerId(port, password, protocol);
            if (resolvedId != null && !resolvedId.isEmpty()) {
                setSelfId(resolvedId);
                logger.info("Self player ID resolved: {}", resolvedId);
            } else {
                logger.warn("Failed to resolve self player ID");
            }
        } catch (Exception e) {
            logger.error("Error resolving self player ID", e);
        }
    }

    /**
     * Get the current self player ID
     * @return self ID or null if not set
     */
    public String getSelfId() {
        return selfId;
    }

    /**
     * Legacy method for compatibility
     */
    public String getSelfID() {
        return getSelfId();
    }

    /**
     * Set the self player ID and notify listeners
     * @param id new self ID
     */
    public synchronized void setSelfId(String id) {
        String oldId = this.selfId;
        this.selfId = id;

        if (id != null && !id.equals(oldId)) {
            logger.info("Self ID updated: {}", id);
            notifySelfIdListeners(id);
        }
    }

    /**
     * Legacy method for compatibility
     */
    public void setSelfID(String selfID) {
        setSelfId(selfID);
    }

    /**
     * Update self ID (alias for setSelfId)
     */
    public void updateSelfId(String id) {
        setSelfId(id);
    }

    /**
     * Process and handle a chat message.
     * @param message the message to process
     */
    public void message(Message message) {
        if (message == null) {
            logger.debug("❌ Message is null, skipping");
            return;
        }

        Chat chat = Chat.getInstance();

        // Log message details
        String msgType = message.getMessageType();
        boolean isOwn = message.isOwnMessage();
        String content = message.getContent();
        String userId = message.getUserId();
        String fullId = message.getId();

        logger.info("┌─ Processing message ─────────────────");
        logger.info("│ Type: {} | Own: {} | UserId: {} | Content: '{}'",
                   msgType, isOwn, userId,
                   content != null ? (content.length() > 30 ? content.substring(0, 27) + "..." : content) : "(null)");
        logger.info("│ Full ID: {}", fullId);
        logger.info("│ Chat States:");
        logger.info("│   - Disabled: {}", chat.isDisabled());
        logger.info("│   - Self State: {}", chat.isSelfState());
        logger.info("│   - Private State (Whisper): {}", chat.isPrivateState());
        logger.info("│   - Party State: {}", chat.isPartyState());
        logger.info("│   - Team State: {}", chat.isTeamState());
        logger.info("│   - All State: {}", chat.isAllState());
        logger.info("│   - User Ignored: {}", chat.isIgnoredPlayerID(userId));
        logger.info("│ Self ID: {}", selfId);

        // Safety check: null message type means we couldn't classify the message
        if (msgType == null) {
            logger.warn("└─ ⚠️ FILTERED: Message type is null (could not classify from JID)");
            return;
        }

        // Skip if disabled or player is ignored
        if (chat.isDisabled()) {
            logger.info("└─ ❌ FILTERED: Chat is disabled");
            return;
        }
        if (chat.isIgnoredPlayerID(message.getUserId())) {
            logger.info("└─ ❌ FILTERED: Player is ignored");
            return;
        }

        // MATCHES ValorantNarrator FILTERING LOGIC EXACTLY
        // Reference: ChatDataHandler.message() in ValorantNarrator

        // Check whisper state first
        if (Chat.TYPE_WHISPER.equals(msgType) && !chat.isPrivateState()) {
            logger.info("└─ ❌ FILTERED: WHISPER but privateState=false");
            return;
        }

        // CRITICAL FIX: If self messages are enabled AND this is own message,
        // SKIP ALL OTHER FILTERING - narrate everything the user sends!
        // This matches ValorantNarrator's logic exactly.
        if (isOwn && chat.isSelfState()) {
            logger.info("└─ ✅ PASSED (SELF MESSAGE): Own message with selfState=true - skipping other filters");
        } else {
            // Only check party/team/all state if NOT a self message (or if self is disabled)

            if (Chat.TYPE_PARTY.equals(msgType) && !chat.isPartyState()) {
                logger.info("└─ ❌ FILTERED: PARTY but partyState=false");
                return;
            }

            if (Chat.TYPE_TEAM.equals(msgType) && !chat.isTeamState()) {
                logger.info("└─ ❌ FILTERED: TEAM but teamState=false");
                return;
            }

            if (Chat.TYPE_ALL.equals(msgType) && !chat.isAllState()) {
                logger.info("└─ ❌ FILTERED: ALL but allState=false");
                return;
            }

            logger.info("└─ ✅ PASSED ALL FILTERS - Proceeding to TTS");
        }

        // Clean and narrate - handle null content
        String cleanContent = content != null ? content.replace("/", "").replace("\\", "") : "";

        // Safety check: only proceed if content is non-empty
        if (cleanContent.isEmpty()) {
            logger.debug("TTS skipped: empty content after cleaning");
            return;
        }

        logger.info("🔊 TTS DISPATCH: type={} content='{}'", msgType,
            cleanContent.length() > 40 ? cleanContent.substring(0, 37) + "..." : cleanContent);

        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                // narrateMessage will check if voice system is initialized
                com.someone.valvoicegui.ValVoiceController.narrateMessage(message);
            } catch (Exception e) {
                logger.error("Failed to narrate: {}", e.getMessage());
            }
        });

        // Update stats
        chat.updateMessageStats(message);
        updateUI(chat);

        // Add new player to mapping
        if (!chat.getPlayerIDTable().containsKey(message.getUserId())) {
            String playerId = message.getUserId();
            String playerName = ChatUtilityHandler.getPlayerName(playerId).trim();
            chat.putPlayerId(playerName, playerId);
            chat.putPlayerName(playerId, playerName);
        }
    }

    private void updateUI(Chat chat) {
        try {
            javafx.application.Platform.runLater(() -> {
                var controller = com.someone.valvoicegui.ValVoiceController.getLatestInstance();
                if (controller != null) {
                    controller.setMessagesSentLabel(chat.getMessagesSent());
                    controller.setCharactersNarratedLabel(chat.getCharactersSent());
                }
            });
        } catch (Exception e) {
            // Ignore UI update failures
        }
    }

    /**
     * Parse a raw message string into a Message object
     * @param rawMessage raw message data
     * @return parsed Message object or null if parsing failed
     */
    public Message parseMessage(String rawMessage) {
        try {
            // Parse JSON message - this is a placeholder
            // Actual implementation depends on the message format
            logger.debug("Parsing message: {}", rawMessage);

            // For now, return null - this should be implemented based on
            // the actual XMPP message format
            return null;
        } catch (Exception e) {
            logger.error("Failed to parse message: {}", rawMessage, e);
            return null;
        }
    }

    /**
     * Register a listener for self ID changes
     * @param listener consumer to be notified when self ID changes
     * @param invokeImmediately if true, invoke listener with current ID immediately
     */
    public void registerSelfIdListener(Consumer<String> listener, boolean invokeImmediately) {
        if (listener == null) return;
        selfIdListeners.add(listener);

        if (invokeImmediately && selfId != null) {
            try {
                listener.accept(selfId);
            } catch (Exception e) {
                logger.error("Error invoking self ID listener", e);
            }
        }
    }

    /**
     * Notify all registered listeners of self ID change
     */
    private void notifySelfIdListeners(String id) {
        for (Consumer<String> listener : selfIdListeners) {
            try {
                listener.accept(id);
            } catch (Exception e) {
                logger.error("Error notifying self ID listener", e);
            }
        }
    }

    /**
     * Clear all listeners (cleanup)
     */
    public void clearListeners() {
        selfIdListeners.clear();
    }
}

