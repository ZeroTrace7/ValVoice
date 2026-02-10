package com.someone.valvoicebackend;

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

    // Message type constants to replace ChatMessageType enum
    public static final String TYPE_PARTY = "PARTY";
    public static final String TYPE_TEAM = "TEAM";
    public static final String TYPE_ALL = "ALL";
    public static final String TYPE_WHISPER = "WHISPER";

    // Enabled channels for narration (using Set<String> instead of EnumSet)
    private final Set<String> enabledChannels = Collections.synchronizedSet(new HashSet<>());
    private boolean includeOwnMessages = true;
    private volatile boolean whispersEnabled = false; // VN-parity: Whispers NOT narrated in standard flow

    // Ignored user IDs (case-insensitive stored in lower case)
    private final Set<String> ignoredUsers = new ConcurrentSkipListSet<>();

    // Optional mapping from userId -> friendly display (e.g., RiotId#Tag)
    private final Map<String, String> userDisplayNames = new ConcurrentHashMap<>();

    // === Stats ===
    private final AtomicLong totalMessages = new AtomicLong();
    private final AtomicLong narratedMessages = new AtomicLong();
    private final LongAdder narratedCharacters = new LongAdder();
    private final Map<String, LongAdder> messagesPerType;
    private final Map<String, LongAdder> narratedPerType;
    private volatile Instant startedAt = Instant.now();

    // ===== Legacy compatibility fields (from older Chat design) =====
    private final java.util.concurrent.atomic.AtomicLong legacyMessagesSent = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong legacyCharactersSent = new java.util.concurrent.atomic.AtomicLong();

    // Legacy channel state flags (kept in sync with modern enums)
    private volatile boolean selfState = true;    // includeOwnMessages mirror
    private volatile boolean privateState = true; // whispersEnabled mirror
    private volatile boolean partyState = true;   // PARTY
    private volatile boolean teamState = true;    // TEAM
    private volatile boolean allState = false;    // ALL (off by default)
    private volatile boolean disabled = false;    // global disable flag

    // Player ID / name mappings (legacy API). Using ConcurrentHashMap for thread safety.
    private final java.util.concurrent.ConcurrentHashMap<String,String> playerIds = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String,String> playerNames = new java.util.concurrent.ConcurrentHashMap<>();


    private Chat() {
        // Initialize counters using string types
        messagesPerType = new ConcurrentHashMap<>();
        narratedPerType = new ConcurrentHashMap<>();
        for (String type : Arrays.asList(TYPE_PARTY, TYPE_TEAM, TYPE_ALL, TYPE_WHISPER)) {
            messagesPerType.put(type, new LongAdder());
            narratedPerType.put(type, new LongAdder());
        }
        // ValVoice default: Enable SELF+PARTY+TEAM (exclude ALL chat by default)
        // This provides a cleaner default experience for voice injection
        // includeOwnMessages (SELF) already true above
        enabledChannels.add(TYPE_PARTY);
        enabledChannels.add(TYPE_TEAM);
        // TYPE_ALL intentionally NOT added - excluded by default
        // Whispers remain disabled (whispersEnabled = false) per VN spec

        // Sync legacy flags immediately after initialization
        syncLegacyFlagsFromModern();
        logger.info("Chat initialized with defaults: enabledChannels={} includeOwn={} whispersEnabled={}",
            enabledChannels, includeOwnMessages, whispersEnabled);
    }


    public static Chat getInstance() { return INSTANCE; }

    // === Configuration ===

    public synchronized void enableChannel(String type) {
        if (type != null) {
            enabledChannels.add(type);
            syncLegacyFlagsFromModern();
        }
    }

    public synchronized void disableChannel(String type) {
        if (type != null) {
            enabledChannels.remove(type);
            syncLegacyFlagsFromModern();
        }
    }
    public synchronized void setIncludeOwnMessages(boolean include) { this.includeOwnMessages = include; syncLegacyFlagsFromModern(); }
    public void setWhispersEnabled(boolean enabled) { this.whispersEnabled = enabled; syncLegacyFlagsFromModern(); }
    public boolean isWhispersEnabled() { return whispersEnabled; }

    /**
     * VN-parity: Apply sources from EnumSet<Source>.
     * Used by Main.java on startup to restore persisted channel filters.
     * Used by ValVoiceController on UI toggle changes.
     *
     * @param sources EnumSet of enabled sources (SELF, PARTY, TEAM, ALL)
     */
    public synchronized void setSources(java.util.EnumSet<Source> sources) {
        if (sources == null) {
            sources = Source.getDefault();
        }

        // Clear and rebuild enabled channels
        enabledChannels.clear();
        includeOwnMessages = sources.contains(Source.SELF);

        if (sources.contains(Source.PARTY)) {
            enabledChannels.add(TYPE_PARTY);
        }
        if (sources.contains(Source.TEAM)) {
            enabledChannels.add(TYPE_TEAM);
        }
        if (sources.contains(Source.ALL)) {
            enabledChannels.add(TYPE_ALL);
        }

        // Sync legacy flags
        syncLegacyFlagsFromModern();

        logger.info("[Chat] Sources applied: {} (channels={}, includeOwn={})",
            Source.toString(sources), enabledChannels, includeOwnMessages);
    }

    /**
     * VN-parity: Get current sources as EnumSet<Source>.
     * Used by ValVoiceController to build config string for persistence.
     *
     * @return EnumSet of currently enabled sources
     */
    public synchronized java.util.EnumSet<Source> getSources() {
        java.util.EnumSet<Source> sources = java.util.EnumSet.noneOf(Source.class);

        if (includeOwnMessages) {
            sources.add(Source.SELF);
        }
        if (enabledChannels.contains(TYPE_PARTY)) {
            sources.add(Source.PARTY);
        }
        if (enabledChannels.contains(TYPE_TEAM)) {
            sources.add(Source.TEAM);
        }
        if (enabledChannels.contains(TYPE_ALL)) {
            sources.add(Source.ALL);
        }

        return sources;
    }

    /**
     * Unified source selection parser (SELF, PARTY, TEAM, ALL tokens joined by '+').
     * Updates both modern enum set and legacy boolean flags.
     * Note: WHISPER/PRIVATE tokens can be used but whispers are enabled by default.
     *
     * ValVoice default: If selection is null/blank/invalid, use default (SELF+PARTY+TEAM).
     */
    public synchronized void applySourceSelection(String selection) {
        if (selection == null || selection.isBlank()) {
            // Apply default (SELF+PARTY+TEAM, excludes ALL chat)
            logger.info("[Chat] applySourceSelection: null/blank input, applying default");
            setSources(Source.getDefault());
            return;
        }

        enabledChannels.clear();
        includeOwnMessages = false;
        boolean anyValidToken = false;
        // Don't reset whispersEnabled - it stays enabled unless explicitly disabled via UI
        String[] parts = selection.toUpperCase(Locale.ROOT).split("\\+");
        for (String raw : parts) {
            String p = raw.trim();
            switch (p) {
                case "SELF" -> { includeOwnMessages = true; anyValidToken = true; }
                case "PARTY" -> { enabledChannels.add(TYPE_PARTY); anyValidToken = true; }
                case "TEAM" -> { enabledChannels.add(TYPE_TEAM); anyValidToken = true; }
                case "ALL" -> { enabledChannels.add(TYPE_ALL); anyValidToken = true; }
                case "WHISPER", "PRIVATE" -> { whispersEnabled = true; anyValidToken = true; } // Explicit enable
                default -> { /* ignore unknown */ }
            }
        }

        // If no valid tokens parsed, apply default
        if (!anyValidToken) {
            logger.warn("[Chat] applySourceSelection: no valid tokens in '{}', applying default", selection);
            setSources(Source.getDefault());
            return;
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

    /**
     * Determines if a message should be narrated.
     * MATCHES ValorantNarrator's ChatDataHandler.message() logic exactly.
     */
    public boolean shouldNarrate(Message msg) {
        if (msg == null) {
            logger.debug("❌ shouldNarrate=false: msg is null");
            return false;
        }

        // Check global disable
        if (disabled) {
            logger.info("❌ shouldNarrate=false: Chat is globally disabled");
            return false;
        }

        String type = msg.getMessageType();
        if (type == null) {
            logger.debug("❌ shouldNarrate=false: message type is null");
            return false;
        }

        // Check ignored players
        if (isIgnored(msg.getUserId())) {
            logger.info("❌ shouldNarrate=false: user {} is ignored", msg.getUserId());
            return false;
        }

        boolean own = msg.isOwnMessage();

        // ValorantNarrator logic: Check message type and own message state
        // Reference: ChatDataHandler.message() in ValorantNarrator

        // WHISPER check
        if (TYPE_WHISPER.equals(type)) {
            if (!privateState) {
                logger.info("❌ shouldNarrate=false: WHISPER disabled (privateState=false)");
                return false;
            }
            if (own && !selfState) {
                logger.info("❌ shouldNarrate=false: own WHISPER but selfState=false");
                return false;
            }
            logger.info("✅ shouldNarrate=true: WHISPER message");
            return true;
        }

        // Own message check (for non-whisper)
        if (own && selfState) {
            // Self messages enabled, skipping other filtering checks (ValorantNarrator behavior)
            logger.info("✅ shouldNarrate=true: own message and selfState=true");
            return true;
        }

        // PARTY check
        if (TYPE_PARTY.equals(type)) {
            if (!partyState) {
                logger.info("❌ shouldNarrate=false: PARTY disabled (partyState=false)");
                return false;
            }
            logger.info("✅ shouldNarrate=true: PARTY message");
            return true;
        }

        // TEAM check
        if (TYPE_TEAM.equals(type)) {
            if (!teamState) {
                logger.info("❌ shouldNarrate=false: TEAM disabled (teamState=false)");
                return false;
            }
            logger.info("✅ shouldNarrate=true: TEAM message");
            return true;
        }

        // ALL check
        if (TYPE_ALL.equals(type)) {
            if (!allState) {
                logger.info("❌ shouldNarrate=false: ALL disabled (allState=false)");
                return false;
            }
            if (own && !selfState) {
                logger.info("❌ shouldNarrate=false: own ALL message but selfState=false");
                return false;
            }
            logger.info("✅ shouldNarrate=true: ALL message");
            return true;
        }

        logger.warn("❌ shouldNarrate=false: unknown message type '{}'", type);
        return false;
    }


    // === Statistics ===
    public void recordIncoming(Message msg) {
        if (msg == null) return;
        totalMessages.incrementAndGet();
        String t = msg.getMessageType();
        if (t != null) messagesPerType.get(t).increment();
    }

    public void recordNarrated(Message msg) {
        if (msg == null) return;
        narratedMessages.incrementAndGet();
        String t = msg.getMessageType();
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
    public synchronized Set<String> getEnabledChannels() {
        return new HashSet<>(enabledChannels);
    }
    public Instant getStartedAt() { return startedAt; }

    // ===== Legacy compatibility API =====

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


    public boolean toggleState() { disabled = !disabled; return disabled; }
    public boolean isDisabled() { return disabled; }

    public boolean isSelfState() { return selfState; }
    public boolean isPrivateState() { return privateState; }
    public boolean isPartyState() { return partyState; }
    public boolean isTeamState() { return teamState; }
    public boolean isAllState() { return allState; }

    // VN-parity: Alias methods for runtime gating (VN uses isXxxEnabled() style)
    public boolean isSelfEnabled() { return selfState; }
    public boolean isPartyEnabled() { return partyState; }
    public boolean isTeamEnabled() { return teamState; }
    public boolean isAllEnabled() { return allState; }
    public boolean isWhisperEnabled() { return privateState; }

    public void setSelfEnabled() { setIncludeOwnMessages(true); }
    public void setSelfDisabled() { setIncludeOwnMessages(false); }
    public void setPrivateEnabled() { setWhispersEnabled(true); }
    public void setPrivateDisabled() { setWhispersEnabled(false); }
    public void setPartyEnabled() { enableChannel(TYPE_PARTY); }
    public void setPartyDisabled() { disableChannel(TYPE_PARTY); }
    public void setTeamEnabled() { enableChannel(TYPE_TEAM); }
    public void setTeamDisabled() { disableChannel(TYPE_TEAM); }
    public void setAllEnabled() { enableChannel(TYPE_ALL); }
    public void setAllDisabled() { disableChannel(TYPE_ALL); }

    public java.util.Hashtable<String,String> getPlayerIDTable() { return new java.util.Hashtable<>(playerIds); }

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
        partyState = enabledChannels.contains(TYPE_PARTY);
        teamState = enabledChannels.contains(TYPE_TEAM);
        allState = enabledChannels.contains(TYPE_ALL);
    }
}
