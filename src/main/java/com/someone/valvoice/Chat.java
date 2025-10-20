package com.someone.valvoice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Central chat configuration + runtime statistics container.
 * Responsibilities:
 *  - Track which chat message types (PARTY / TEAM / ALL / WHISPER) are enabled for narration
 *  - Track whether the user's own messages ("SELF") are included
 *  - Maintain ignore list of user IDs and optional userId -> display name mapping
 *  - Collect statistics (total, narrated counts, characters narrated per channel)
 *  - Provide a single decision point whether an incoming Message should be narrated
 *
 * Thread-safety: public mutation methods are synchronized where compound operations occur;
 *                underlying counters are atomic.
 */
public class Chat {
    private static final Logger logger = LoggerFactory.getLogger(Chat.class);
    private static final Chat INSTANCE = new Chat();

    // Enabled channels for narration (excludes SELF concept which is separate)
    private final EnumSet<ChatMessageType> enabledChannels = EnumSet.noneOf(ChatMessageType.class);
    private boolean includeOwnMessages = true; // corresponds to "SELF" token in source selection
    private volatile boolean whispersEnabled = true; // toggle for private messages

    // Ignored user IDs (case-insensitive stored in lower case)
    private final Set<String> ignoredUsers = new ConcurrentSkipListSet<>();

    // Optional mapping from userId -> friendly display (e.g., RiotId#Tag)
    private final Map<String, String> userDisplayNames = new ConcurrentHashMap<>();

    // === Stats ===
    private final AtomicLong totalMessages = new AtomicLong();
    private final AtomicLong narratedMessages = new AtomicLong();
    private final LongAdder narratedCharacters = new LongAdder();
    private final EnumMap<ChatMessageType, LongAdder> messagesPerType = new EnumMap<>(ChatMessageType.class);
    private final EnumMap<ChatMessageType, LongAdder> narratedPerType = new EnumMap<>(ChatMessageType.class);
    private volatile Instant startedAt = Instant.now();

    // ===== Legacy compatibility fields (from older Chat design) =====
    private volatile int quotaLimit = -1; // -1 => unlimited / unset
    private volatile boolean quotaExhausted = false;
    private final java.util.concurrent.atomic.AtomicLong legacySelfMessagesSent = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong legacyMessagesSent = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong legacyCharactersSent = new java.util.concurrent.atomic.AtomicLong();

    // Legacy channel state flags (kept in sync with modern enums)
    private volatile boolean selfState = true;    // includeOwnMessages mirror
    private volatile boolean privateState = true; // whispersEnabled mirror
    private volatile boolean partyState = true;   // PARTY
    private volatile boolean teamState = true;    // TEAM
    private volatile boolean allState = false;    // ALL (off by default)
    private volatile boolean disabled = false;    // global disable flag

    private volatile String mucId;                // Legacy MUC ID holder

    // Player ID / name mappings (legacy API). Using ConcurrentHashMap for thread safety.
    private final java.util.concurrent.ConcurrentHashMap<String,String> playerIds = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String,String> playerNames = new java.util.concurrent.ConcurrentHashMap<>();

    private Chat() {
        // Initialize counters
        for (ChatMessageType t : ChatMessageType.values()) {
            messagesPerType.put(t, new LongAdder());
            narratedPerType.put(t, new LongAdder());
        }
        // Default: enable PARTY + TEAM (common use)
        enabledChannels.add(ChatMessageType.PARTY);
        enabledChannels.add(ChatMessageType.TEAM);
    }

    /**
     * Legacy constructor for ValNarrator compatibility (quotaLimit parameter).
     * Note: This doesn't create a new instance due to singleton pattern.
     * Use getInstance() instead. This constructor only sets the quota limit.
     */
    public Chat(int quotaLimit) {
        this(); // Call private constructor
        this.quotaLimit = quotaLimit;
        logger.debug("Chat instance created with quota limit: {}", quotaLimit);
    }

    public static Chat getInstance() { return INSTANCE; }

    // === Configuration ===

    public synchronized void enableChannel(ChatMessageType type) { if (type != null) { enabledChannels.add(type); syncLegacyFlagsFromModern(); } }
    public synchronized void disableChannel(ChatMessageType type) { if (type != null) { enabledChannels.remove(type); syncLegacyFlagsFromModern(); } }
    public synchronized void setIncludeOwnMessages(boolean include) { this.includeOwnMessages = include; syncLegacyFlagsFromModern(); }
    public void setWhispersEnabled(boolean enabled) { this.whispersEnabled = enabled; syncLegacyFlagsFromModern(); }
    public boolean isWhispersEnabled() { return whispersEnabled; }

    /**
     * Unified source selection parser (SELF, PARTY, TEAM, ALL tokens joined by '+').
     * Updates both modern enum set and legacy boolean flags.
     * Note: WHISPER/PRIVATE tokens can be used but whispers are enabled by default.
     */
    public synchronized void applySourceSelection(String selection) {
        if (selection == null || selection.isBlank()) return;
        enabledChannels.clear();
        includeOwnMessages = false;
        // Don't reset whispersEnabled - it stays enabled unless explicitly disabled via UI
        String[] parts = selection.toUpperCase(Locale.ROOT).split("\\+");
        for (String raw : parts) {
            String p = raw.trim();
            switch (p) {
                case "SELF" -> includeOwnMessages = true;
                case "PARTY" -> enabledChannels.add(ChatMessageType.PARTY);
                case "TEAM" -> enabledChannels.add(ChatMessageType.TEAM);
                case "ALL" -> enabledChannels.add(ChatMessageType.ALL);
                case "WHISPER", "PRIVATE" -> whispersEnabled = true; // Explicit enable
                default -> { /* ignore unknown */ }
            }
        }
        logger.info("Updated source selection: channels={} includeOwn={} whispersEnabled={}", enabledChannels, includeOwnMessages, whispersEnabled);
        syncLegacyFlagsFromModern();
    }

    // === User management ===

    public void ignoreUser(String userId) { if (userId != null) ignoredUsers.add(userId.toLowerCase(Locale.ROOT)); }
    public void unignoreUser(String userId) { if (userId != null) ignoredUsers.remove(userId.toLowerCase(Locale.ROOT)); }
    public boolean isIgnored(String userId) { return userId != null && ignoredUsers.contains(userId.toLowerCase(Locale.ROOT)); }

    public void putDisplayName(String userId, String display) {
        if (userId == null || display == null) return;
        userDisplayNames.put(userId, display);
    }
    public Optional<String> getDisplayName(String userId) { return Optional.ofNullable(userDisplayNames.get(userId)); }

    // === Decision Logic ===

    public boolean shouldNarrate(Message msg) {
        if (msg == null || disabled) return false;
        ChatMessageType type = msg.getMessageType();
        if (type == null) return false;
        boolean own = msg.isOwnMessage();
        if (isIgnored(msg.getUserId())) return false;
        if (own && !includeOwnMessages && type != ChatMessageType.WHISPER) return false;
        return switch (type) {
            case PARTY, TEAM, ALL -> enabledChannels.contains(type);
            case WHISPER -> whispersEnabled && (!own || includeOwnMessages);
        };
    }

    // === Statistics ===
    public void recordIncoming(Message msg) {
        if (msg == null) return;
        totalMessages.incrementAndGet();
        ChatMessageType t = msg.getMessageType();
        if (t != null) messagesPerType.get(t).increment();
    }

    public void recordNarrated(Message msg) {
        if (msg == null) return;
        narratedMessages.incrementAndGet();
        ChatMessageType t = msg.getMessageType();
        if (t != null) narratedPerType.get(t).increment();
        String c = msg.getContent();
        if (c != null) narratedCharacters.add(c.length());
    }

    // Resets stats without changing configuration
    public synchronized void resetStats() {
        totalMessages.set(0);
        narratedMessages.set(0);
        narratedCharacters.reset();
        for (LongAdder la : messagesPerType.values()) la.reset();
        for (LongAdder la : narratedPerType.values()) la.reset();
        startedAt = Instant.now();
    }

    // === Getters (snapshots) ===
    public long getTotalMessages() { return totalMessages.get(); }
    public long getNarratedMessages() { return narratedMessages.get(); }
    public long getNarratedCharacters() { return narratedCharacters.sum(); }
    public boolean isIncludeOwnMessages() { return includeOwnMessages; }
    public synchronized Set<ChatMessageType> getEnabledChannels() { return EnumSet.copyOf(enabledChannels); }
    public Instant getStartedAt() { return startedAt; }

    // ===== Legacy compatibility API =====

    public int getQuotaLimit() { return quotaLimit; }
    public void setQuotaLimit(int limit) { this.quotaLimit = limit; }
    public void markQuotaExhausted() { this.quotaExhausted = true; }
    public boolean isQuotaExhausted() { return quotaExhausted; }

    public void incrementSelfMessagesSent() { legacySelfMessagesSent.incrementAndGet(); }
    public long getSelfMessagesSent() { return legacySelfMessagesSent.get(); }

    public long getMessagesSent() { return legacyMessagesSent.get(); }
    public long getCharactersSent() { return legacyCharactersSent.get(); }

    /**
     * Legacy update method: counts only narrated messages (mirrors old semantics where update called post-filter).
     * Uses message content length (null-safe).
     */
    public void updateMessageStats(Message message) {
        if (message == null) return;
        legacyMessagesSent.incrementAndGet();
        String c = message.getContent();
        if (c != null) legacyCharactersSent.addAndGet(c.length());
    }

    /**
     * Get self player ID (for ValNarrator compatibility).
     * Delegates to ChatProperties via ChatDataHandler.
     */
    public String getSelfID() {
        return ChatDataHandler.getInstance().getProperties().getSelfID();
    }

    /**
     * Set self player ID (for ValNarrator compatibility).
     * Delegates to ChatProperties via ChatDataHandler.
     */
    public void setSelfID(String selfID) {
        ChatDataHandler.getInstance().getProperties().setSelfID(selfID);
    }


    public String getMucID() { return mucId; }
    public void setMucID(String mucId) { this.mucId = mucId; }

    public boolean toggleState() { disabled = !disabled; return disabled; }
    public boolean isDisabled() { return disabled; }

    public boolean isSelfState() { return selfState; }
    public boolean isPrivateState() { return privateState; }
    public boolean isPartyState() { return partyState; }
    public boolean isTeamState() { return teamState; }
    public boolean isAllState() { return allState; }

    public void setSelfEnabled() { setIncludeOwnMessages(true); }
    public void setSelfDisabled() { setIncludeOwnMessages(false); }
    public void setPrivateEnabled() { setWhispersEnabled(true); }
    public void setPrivateDisabled() { setWhispersEnabled(false); }
    public void setPartyEnabled() { enableChannel(ChatMessageType.PARTY); }
    public void setPartyDisabled() { disableChannel(ChatMessageType.PARTY); }
    public void setTeamEnabled() { enableChannel(ChatMessageType.TEAM); }
    public void setTeamDisabled() { disableChannel(ChatMessageType.TEAM); }
    public void setAllEnabled() { enableChannel(ChatMessageType.ALL); }
    public void setAllDisabled() { disableChannel(ChatMessageType.ALL); }

    public java.util.Hashtable<String,String> getPlayerIDTable() { return new java.util.Hashtable<>(playerIds); }
    public java.util.Hashtable<String,String> getPlayerNameTable() { return new java.util.Hashtable<>(playerNames); }

    public void putPlayerId(String name, String id) { if (name!=null && id!=null) playerIds.put(name, id); }
    public void putPlayerName(String id, String name) { if (id!=null && name!=null) playerNames.put(id, name); }

    public java.util.List<String> getIgnoredPlayerIDs() { return new java.util.ArrayList<>(ignoredUsers); }

    public void addIgnoredPlayer(final String player) {
        if (player == null) return;
        // Old logic used playerNames.get(player); attempt lookup first
        String resolved = playerNames.getOrDefault(player, player);
        ignoreUser(resolved);
    }
    public void removeIgnoredPlayer(final String player) {
        if (player == null) return;
        String resolved = playerNames.getOrDefault(player, player);
        unignoreUser(resolved);
    }

    public boolean isIgnoredPlayerID(final String playerID) { return isIgnored(playerID); }

    // Sync legacy booleans from modern configuration
    private void syncLegacyFlagsFromModern() {
        selfState = includeOwnMessages;
        privateState = whispersEnabled;
        partyState = enabledChannels.contains(ChatMessageType.PARTY);
        teamState = enabledChannels.contains(ChatMessageType.TEAM);
        allState = enabledChannels.contains(ChatMessageType.ALL);
    }
}
