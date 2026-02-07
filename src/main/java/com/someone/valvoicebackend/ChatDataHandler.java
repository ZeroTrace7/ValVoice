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

    // === PHASE 5: EVENT-DRIVEN UI ===
    // Stats update callback - allows decoupling from direct UI controller references.
    // Set by ValVoiceController during initialization.
    private volatile StatsUpdateCallback statsCallback = null;

    /**
     * Functional interface for stats updates (Phase 5: Event-Driven UI).
     * Replaces direct ValVoiceController.getLatestInstance() calls.
     */
    @FunctionalInterface
    public interface StatsUpdateCallback {
        void onStatsUpdated(long messagesSent, long charactersSent);
    }

    private ChatDataHandler() {}

    public static ChatDataHandler getInstance() {
        return INSTANCE;
    }

    /**
     * Set the stats update callback (Phase 5: Event-Driven UI).
     * Called by ValVoiceController during initialization to receive stats updates.
     *
     * @param callback The callback to invoke when stats change, or null to disable
     */
    public void setStatsCallback(StatsUpdateCallback callback) {
        this.statsCallback = callback;
        logger.debug("[ChatDataHandler] Stats callback {}", callback != null ? "registered" : "cleared");
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
        // VOICE INJECTOR MODE: SELF-ONLY NARRATION GUARD
        // ═══════════════════════════════════════════════════════════════════════
        // ValVoice is a Voice Injector - we ONLY narrate messages from the LOCAL USER.
        // This is the INVERTED policy compared to ValorantNarrator (which reads others' messages).
        //
        // Reference Architecture (ValorantNarrator-aligned):
        // 1. Extract sender PUUID from message.getFrom() - the AUTHORITATIVE 'from' attribute
        // 2. Do NOT rely on message.getId(), jid attribute, or isOwnMessage() booleans
        // 3. Support both JID formats:
        //    - MUC (Party/Team): room@server/PUUID → PUUID is after /
        //    - Direct/Whisper:   PUUID@server      → PUUID is before @
        // 4. Compare with localUserPuuid (captured from RSO-PAS JWT at MITM startup)
        // 5. If sender != local user → DROP immediately (do NOT narrate)
        // 6. If sender == local user AND channel is PARTY or TEAM → allow TTS
        // ═══════════════════════════════════════════════════════════════════════

        // Extract sender PUUID from authoritative XMPP 'from' attribute
        String senderPuuid = extractPuuid(message.getFrom());

        // Get local user's PUUID (captured from RSO-PAS auth at MITM startup)
        String localUserPuuid = selfId;

        // ═══════════════════════════════════════════════════════════════════════
        // DEBUG LOGGING: Log senderPuuid vs localUserPuuid for each message
        // ═══════════════════════════════════════════════════════════════════════
        logger.debug("│ [SELF-ONLY DEBUG] senderPuuid={} localUserPuuid={}",
                    senderPuuid.isEmpty() ? "(empty)" : senderPuuid,
                    localUserPuuid != null ? localUserPuuid : "(null)");
        logger.info("│ Sender PUUID (from 'from' attribute): {}", senderPuuid.isEmpty() ? "(not extractable)" : senderPuuid);

        // ═══════════════════════════════════════════════════════════════════════
        // VOICE INJECTOR GUARD CLAUSE (per spec)
        // ═══════════════════════════════════════════════════════════════════════

        // Safety: if identity not captured yet, do nothing
        if (localUserPuuid == null || localUserPuuid.isEmpty()) {
            logger.warn("│ ⚠️ WARNING: localUserPuuid not captured yet - cannot verify sender identity");
            logger.warn("└─ ❌ FILTERED (SELF-ONLY): Identity not yet established - dropping message for safety");
            return;
        }

        // Safety: if sender PUUID could not be extracted, drop for safety
        if (senderPuuid.isEmpty()) {
            logger.warn("│ ⚠️ WARNING: Could not extract sender PUUID from 'from' attribute");
            logger.warn("└─ ❌ FILTERED (SELF-ONLY): Cannot extract sender PUUID - dropping for safety");
            return;
        }

        // Voice Injector Policy: Drop everything not sent by me
        if (!localUserPuuid.equalsIgnoreCase(senderPuuid)) {
            // CRITICAL: Sender is NOT the local user - DROP this message
            // Teammates' messages are logged but NEVER narrated (Voice Injector behavior)
            logger.info("└─ ❌ FILTERED (SELF-ONLY): Sender '{}' != Self '{}' - not narrating teammate's message",
                       senderPuuid.substring(0, Math.min(8, senderPuuid.length())) + "...",
                       localUserPuuid.substring(0, Math.min(8, localUserPuuid.length())) + "...");
            logger.debug("│ [SELF-ONLY DEBUG] Full comparison: sender='{}' self='{}' match=false",
                        senderPuuid, localUserPuuid);
            return;
        }

        // ✅ Sender IS the local user - proceed with channel filter
        logger.info("│ ✅ SELF-ONLY: Sender matches local user PUUID");
        logger.debug("│ [SELF-ONLY DEBUG] Full comparison: sender='{}' self='{}' match=true",
                    senderPuuid, localUserPuuid);

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

        // ═══════════════════════════════════════════════════════════════════════════
        // PHASE 4: GAME STATE GATE (Smart Mute / Clutch Mode)
        // ═══════════════════════════════════════════════════════════════════════════
        // INTENTIONAL DEVIATION FROM VALORANTNARRATOR:
        // ValorantNarrator does NOT implement presence-based game state awareness.
        // This gate is a ValVoice enhancement - strictly ADDITIVE and ISOLATED.
        //
        // FILTER ORDER PRESERVED (per spec):
        // Archive → Timestamp → Duplicate → Self-only → Channel → (NEW) Game State → TTS
        //
        // NON-NEGOTIABLE INVARIANTS (verified preserved):
        // - UTC + grace window timestamp gate: UNCHANGED (in ValVoiceBackend)
        // - Archive IQ blocking: UNCHANGED (in ValVoiceBackend)
        // - Self-only narration: UNCHANGED (above in this method)
        // - Teammates never narrated: UNCHANGED (self-only filter above)
        //
        // Gate Logic:
        // If clutchMode is ENABLED AND currentState is INGAME → silently drop narration
        // ═══════════════════════════════════════════════════════════════════════════
        if (GameStateManager.getInstance().shouldSuppressNarration()) {
            GameStateManager.GameState currentState = GameStateManager.getInstance().getCurrentState();
            logger.info("└─ ❌ FILTERED (SMART MUTE): clutchMode=ON, state={} - suppressing narration", currentState);
            logger.debug("│ [SMART MUTE DEBUG] Message would have been narrated but clutch mode suppressed it");
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

        // === PHASE 3: WHO IS SPEAKING? ===
        // Use Roster to look up sender's display name for TTS announcement
        // Format: "PlayerName says: message" (if name is known)
        String ttsContent = Roster.getInstance().formatTtsMessage(senderPuuid, cleanContent);

        logger.info("🔊 TTS DISPATCH: type={} content='{}'", msgType,
            ttsContent.length() > 40 ? ttsContent.substring(0, 37) + "..." : ttsContent);

        // Create a copy of the message with the formatted TTS content
        final Message ttsMessage = new Message(message, ttsContent);

        // === PHASE A: PRODUCTION CLEANUP ===
        // Call VoiceGenerator directly instead of going through ValVoiceController.
        // This maintains backend UI-agnostic design (Phase 5 Event-Driven UI).
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                if (VoiceGenerator.isInitialized()) {
                    VoiceGenerator.getInstance().queueNarration(ttsMessage);
                } else {
                    logger.warn("VoiceGenerator not initialized - cannot narrate message");
                }
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
        // === PHASE 5: EVENT-DRIVEN UI ===
        // Use callback instead of direct ValVoiceController reference.
        // The callback handles Platform.runLater() wrapping internally.
        StatsUpdateCallback callback = this.statsCallback;
        if (callback != null) {
            try {
                callback.onStatsUpdated(chat.getMessagesSent(), chat.getCharactersSent());
            } catch (Exception e) {
                // Ignore UI update failures
                logger.debug("[ChatDataHandler] Stats callback error: {}", e.getMessage());
            }
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

