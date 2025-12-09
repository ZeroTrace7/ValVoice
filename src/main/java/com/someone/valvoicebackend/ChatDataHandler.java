package com.someone.valvoicebackend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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
     * Process and handle a chat message
     * @param message the message to process
     */
    public void message(Message message) {
        if (message == null) {
            logger.warn("Received null message");
            return;
        }

        // Log message details for debugging
        logger.info("📥 Processing message: type={}, userId={}, content='{}', isOwn={}",
            message.getMessageType(),
            message.getUserId(),
            message.getContent() != null ?
                (message.getContent().length() > 50 ? message.getContent().substring(0, 47) + "..." : message.getContent())
                : "(null)",
            message.isOwnMessage());

        // Process message through Chat instance
        Chat chat = Chat.getInstance();

        // Record incoming message for stats
        chat.recordIncoming(message);

        boolean shouldNarrate = chat.shouldNarrate(message);
        logger.info("📊 shouldNarrate decision: {} for message type={}", shouldNarrate, message.getMessageType());

        if (shouldNarrate) {
            // Record narrated message for stats
            chat.recordNarrated(message);

            // Narrate the message via ValVoiceController
            try {
                logger.info("🎤 Sending message to TTS: '{}'",
                    message.getContent() != null ?
                        (message.getContent().length() > 50 ? message.getContent().substring(0, 47) + "..." : message.getContent())
                        : "(null)");
                com.someone.valvoicegui.ValVoiceController.narrateMessage(message);
            } catch (Exception e) {
                logger.error("Error narrating message", e);
            }
        } else {
            logger.debug("⏭️ Message skipped (shouldNarrate=false)");
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

