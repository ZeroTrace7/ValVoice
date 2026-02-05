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
     * Set the self player ID and notify listeners.
     *
     * PHASE 3: RECONNECT STABILITY
     * This method is called when identity is captured from RSO-PAS JWT.
     * The identity is stored in a volatile field and persists across ECONNRESET reconnects.
     * The MITM never calls this method on reconnect - identity is captured ONCE at login.
     *
     * @param id new self ID (PUUID)
     */
    public synchronized void setSelfId(String id) {
        String oldId = this.selfId;
        this.selfId = id;

        if (id != null && !id.equals(oldId)) {
            logger.info("╔══════════════════════════════════════════════════════════════╗");
            logger.info("║ IDENTITY CAPTURED (PHASE 1)                                  ║");
            logger.info("║ PUUID: {}                                    ║", id);
            logger.info("║ This identity persists across ECONNRESET reconnects.        ║");
            logger.info("╚══════════════════════════════════════════════════════════════╝");
            notifySelfIdListeners(id);
        } else if (id != null && id.equals(oldId)) {
            logger.debug("[IDENTITY] setSelfId called with same PUUID - no change (reconnect safe)");
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
     * PHASE 2: Extract sender PUUID from raw JID.
     *
     * ValorantNarrator Reference Architecture - Dual-Format Extractor:
     *
     * Handles TWO JID formats used by Valorant XMPP:
     *
     * FORMAT 1 - MUC (Party/Team) 'from' attribute:
     *   room@server/SENDER_PUUID
     *   Example: "abc123@ares-coregame.ap.pvp.net/550e8400-e29b-41d4-a716-446655440000"
     *   → PUUID is after / → returns "550e8400-e29b-41d4-a716-446655440000"
     *
     * FORMAT 2 - Direct/Whisper 'from' attribute:
     *   SENDER_PUUID@server
     *   Example: "550e8400-e29b-41d4-a716-446655440000@ap1.pvp.net"
     *   → PUUID is before @ → returns "550e8400-e29b-41d4-a716-446655440000"
     *
     * IMPORTANT: This method is intentionally lenient (no UUID format validation).
     * This matches ValorantNarrator's simple extraction logic.
     *
     * @param rawJid The raw JID from the 'from' attribute of the message stanza
     * @return The sender PUUID, or empty string if not extractable
     */
    private String extractPuuid(String rawJid) {
        if (rawJid == null || rawJid.isEmpty()) {
            logger.debug("[PUUID EXTRACT] Input is null or empty");
            return "";
        }

        // MUC format: room@server/PUUID
        // PUUID is in the resource part (after /)
        if (rawJid.contains("/")) {
            String puuid = rawJid.substring(rawJid.indexOf("/") + 1);
            logger.debug("[PUUID EXTRACT] MUC format: '{}' -> '{}'", rawJid, puuid);
            return puuid;
        }

        // Direct/Whisper format: PUUID@server
        // PUUID is the local part (before @)
        if (rawJid.contains("@")) {
            String puuid = rawJid.substring(0, rawJid.indexOf("@"));
            logger.debug("[PUUID EXTRACT] Direct format: '{}' -> '{}'", rawJid, puuid);
            return puuid;
        }

        // Fallback: return as-is (might be bare PUUID)
        logger.debug("[PUUID EXTRACT] Fallback (bare): '{}'", rawJid);
        return rawJid;
    }

    /**
     * DEPRECATED: Use extractPuuid() instead.
     *
     * This method is more strict (validates UUID format) but ValorantNarrator
     * uses a simpler, more lenient extraction logic.
     *
     * @param jid The full JID (from 'from' or 'jid' attribute)
     * @return The sender PUUID, or null if not extractable
     */
    @Deprecated
    private static String extractPuuidFromJid(String jid) {
        if (jid == null || jid.isBlank()) {
            return null;
        }

        // FORMAT 1: Check for resource part (room@server/PUUID)
        // This is the MUC format where PUUID is in the resource
        int slashIndex = jid.indexOf('/');
        if (slashIndex >= 0 && slashIndex < jid.length() - 1) {
            String resource = jid.substring(slashIndex + 1);
            if (!resource.isBlank()) {
                logger.debug("[PUUID EXTRACT LEGACY] Format 1 (resource): '{}' -> '{}'", jid, resource);
                return resource;
            }
        }

        // FORMAT 2: No resource, PUUID is the local part (PUUID@server)
        // This is the 'jid' attribute format
        int atIndex = jid.indexOf('@');
        if (atIndex > 0) {
            String localPart = jid.substring(0, atIndex);
            // Validate: PUUID should look like a UUID (contains hyphens, ~36 chars)
            // Be lenient but filter out obvious non-PUUIDs like room IDs
            if (!localPart.isBlank() && localPart.contains("-") && localPart.length() >= 32) {
                logger.debug("[PUUID EXTRACT LEGACY] Format 2 (local part): '{}' -> '{}'", jid, localPart);
                return localPart;
            }
        }

        // FORMAT 3: Bare PUUID (no @ or /)
        // Some edge cases may have just the PUUID
        if (!jid.contains("@") && !jid.contains("/") && jid.contains("-") && jid.length() >= 32) {
            logger.debug("[PUUID EXTRACT LEGACY] Format 3 (bare PUUID): '{}'", jid);
            return jid;
        }

        logger.debug("[PUUID EXTRACT LEGACY] Could not extract PUUID from: '{}'", jid);
        return null;
    }

    /**
     * Process and handle a chat message.
     *
     * PHASE 2: SELF-ONLY NARRATION (Voice Injector Mode)
     * ValVoice is a Voice Injector, NOT a screen reader.
     * We ONLY narrate messages sent BY the local user.
     * Messages from other players are silently dropped.
     *
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
        String fromAttr = message.getFrom();  // AUTHORITATIVE sender identity source

        logger.info("┌─ Processing message ─────────────────");
        logger.info("│ Type: {} | Own: {} | UserId: {} | Content: '{}'",
                   msgType, isOwn, userId,
                   content != null ? (content.length() > 30 ? content.substring(0, 27) + "..." : content) : "(null)");
        logger.info("│ From (authoritative): {}", fromAttr);
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

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 2: SELF-ONLY NARRATION GUARD (Voice Injector Policy)
        // ═══════════════════════════════════════════════════════════════════════
        // ValVoice is a Voice Injector - we ONLY narrate messages from the LOCAL USER.
        // This is the INVERTED policy compared to ValorantNarrator (which reads others' messages).
        //
        // Logic (ValorantNarrator reference architecture):
        // 1. Extract sender PUUID from message.getFrom() - the AUTHORITATIVE 'from' attribute
        // 2. Do NOT rely on message.getId(), jid attribute, or isOwnMessage() booleans
        // 3. Support both JID formats:
        //    - MUC (Party/Team): room@server/PUUID → PUUID is after /
        //    - Direct/Whisper:   PUUID@server      → PUUID is before @
        // 4. Compare with currentUserPuuid (captured in Phase 1 from RSO-PAS JWT)
        // 5. If sender != local user → DROP immediately (do NOT narrate)
        // 6. If sender == local user AND channel is PARTY or TEAM → allow TTS
        // ═══════════════════════════════════════════════════════════════════════

        // Extract sender PUUID from the AUTHORITATIVE 'from' attribute
        String senderPuuid = extractPuuid(fromAttr);

        // Get current user's PUUID (captured from RSO-PAS auth in Phase 1)
        String currentUserPuuid = selfId;

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 3: DEBUG LOGGING FOR VERIFICATION
        // Log senderPuuid vs currentUserPuuid for each message (DEBUG level)
        // ═══════════════════════════════════════════════════════════════════════
        logger.debug("│ [SELF-ONLY DEBUG] senderPuuid={} currentUserPuuid={}",
                    senderPuuid.isEmpty() ? "(empty)" : senderPuuid,
                    currentUserPuuid != null ? currentUserPuuid : "(null)");
        logger.info("│ Sender PUUID (from 'from' attribute): {}", senderPuuid.isEmpty() ? "(not extractable)" : senderPuuid);

        // SELF-ONLY GUARD: If sender is NOT the local user, drop immediately
        if (currentUserPuuid == null || currentUserPuuid.isBlank()) {
            // Identity not yet captured - cannot determine if self
            // PHASE 3: Be STRICT - if we don't know who we are, we cannot verify sender
            // Drop the message to avoid narrating others' messages
            logger.warn("│ ⚠️ WARNING: currentUserPuuid not captured yet - cannot verify sender identity");
            logger.warn("└─ ❌ FILTERED (SELF-ONLY): Identity not yet established - dropping message for safety");
            return;
        } else if (senderPuuid.isEmpty()) {
            // Could not extract sender PUUID from 'from' attribute
            // For MUC messages this shouldn't happen - likely a direct message or malformed JID
            // Be strict: drop the message
            logger.warn("│ ⚠️ WARNING: Could not extract sender PUUID from 'from' attribute - likely malformed");
            logger.warn("└─ ❌ FILTERED (SELF-ONLY): Cannot extract sender PUUID - dropping for safety");
            return;
        } else if (!currentUserPuuid.equalsIgnoreCase(senderPuuid)) {
            // CRITICAL: Sender is NOT the local user - DROP this message
            // This is the core SELF-ONLY guard - teammates' messages are logged but NOT narrated
            logger.info("└─ ❌ FILTERED (SELF-ONLY): Sender '{}' != Self '{}' - not narrating teammate's message",
                       senderPuuid.substring(0, Math.min(8, senderPuuid.length())) + "...",
                       currentUserPuuid.substring(0, Math.min(8, currentUserPuuid.length())) + "...");
            logger.debug("│ [SELF-ONLY DEBUG] Full comparison: sender='{}' self='{}' match=false",
                        senderPuuid, currentUserPuuid);
            return;
        } else {
            // Sender IS the local user - proceed with channel check
            logger.info("│ ✅ SELF-ONLY: Sender matches local user PUUID");
            logger.debug("│ [SELF-ONLY DEBUG] Full comparison: sender='{}' self='{}' match=true",
                        senderPuuid, currentUserPuuid);
        }

        // CHANNEL RESTRICTION: Only allow PARTY or TEAM for self-narration
        // (Whisper and All chat are excluded from voice injection)
        if (!Chat.TYPE_PARTY.equals(msgType) && !Chat.TYPE_TEAM.equals(msgType)) {
            logger.info("└─ ❌ FILTERED (CHANNEL): {} is not PARTY or TEAM - voice injection only for party/team chat",
                       msgType);
            return;
        }

        // Additional channel state checks (respect user preferences)
        if (Chat.TYPE_PARTY.equals(msgType) && !chat.isPartyState()) {
            logger.info("└─ ❌ FILTERED: PARTY but partyState=false");
            return;
        }

        if (Chat.TYPE_TEAM.equals(msgType) && !chat.isTeamState()) {
            logger.info("└─ ❌ FILTERED: TEAM but teamState=false");
            return;
        }

        logger.info("└─ ✅ PASSED ALL FILTERS (SELF-ONLY MODE) - Proceeding to TTS");

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

