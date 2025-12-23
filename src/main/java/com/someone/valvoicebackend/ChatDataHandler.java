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
     * MATCHES ValorantNarrator's ChatDataHandler.message() logic exactly.
     * @param message the message to process
     */
    public void message(Message message) {
        if (message == null) {
            logger.warn("Received null message");
            return;
        }

        Chat properties = Chat.getInstance();

        // Check if globally disabled
        if (properties.isDisabled()) {
            logger.info("Valorant Narrator disabled, ignoring message!");
            return;
        }

        // Check if player is ignored
        if (properties.isIgnoredPlayerID(message.getUserId())) {
            logger.info("Ignoring message from {}!", properties.getPlayerIDTable().get(message.getUserId()));
            return;
        }

        // WHISPER check
        if ("WHISPER".equals(message.getMessageType()) && !properties.isPrivateState()) {
            logger.info("Private messages disabled, ignoring message!");
            return;
        }

        // Self messages check - if own message and selfState enabled, skip other filters
        if (message.isOwnMessage() && properties.isSelfState()) {
            // Self messages enabled, skipping filtering checks.
            logger.info("✅ Own message with selfState=true, will narrate");
        } else if ("PARTY".equals(message.getMessageType()) && !properties.isPartyState()) {
            logger.info("Party messages disabled, ignoring message!");
            return;
        } else if ("TEAM".equals(message.getMessageType()) && !properties.isTeamState()) {
            logger.info("Team messages disabled, ignoring message!");
            return;
        }

        // ALL chat check
        if ("ALL".equals(message.getMessageType())) {
            if (!properties.isAllState()) {
                logger.info("All messages disabled, ignoring message!");
                return;
            } else if (message.isOwnMessage() && !properties.isSelfState()) {
                logger.info("(ALL)Self messages disabled, ignoring message!");
                return;
            }
        }

        // Clean the message content
        final String finalBody = message.getContent().replace("/", "").replace("\\", "");

        // Log message details
        logger.info("🎤 TTS message: type={}, userId={}, content='{}'",
            message.getMessageType(),
            message.getUserId(),
            finalBody.length() > 50 ? finalBody.substring(0, 47) + "..." : finalBody);

        // Narrate the message asynchronously
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                // Expand shortforms and speak
                String expandedText = ChatUtilityHandler.expandShortForms(finalBody);
                com.someone.valvoicegui.ValVoiceController.narrateMessage(message);
                logger.info("✅ TTS triggered for: '{}'",
                    expandedText.length() > 50 ? expandedText.substring(0, 47) + "..." : expandedText);
            } catch (Exception e) {
                logger.error("Failed to narrate message: {}", e.getMessage());
            }
        });

        // Update message stats
        properties.updateMessageStats(message);

        // Update UI stats
        try {
            javafx.application.Platform.runLater(() -> {
                com.someone.valvoicegui.ValVoiceController controller =
                    com.someone.valvoicegui.ValVoiceController.getLatestInstance();
                if (controller != null) {
                    controller.setMessagesSentLabel(properties.getMessagesSent());
                    controller.setCharactersNarratedLabel(properties.getCharactersSent());
                }
            });
        } catch (Exception e) {
            logger.debug("Could not update UI stats: {}", e.getMessage());
        }

        // Add player to mapping if new
        if (!properties.getPlayerIDTable().containsKey(message.getUserId())) {
            final String playerID = message.getUserId();
            final String playerName = ChatUtilityHandler.getPlayerName(playerID).trim();
            properties.putPlayerId(playerName, playerID);
            properties.putPlayerName(playerID, playerName);
            logger.debug("Added new player to mapping: {} -> {}", playerID, playerName);
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

