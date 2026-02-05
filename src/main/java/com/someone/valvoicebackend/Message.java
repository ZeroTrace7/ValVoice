package com.someone.valvoicebackend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a single Valorant XMPP chat message parsed from the raw XML stanza.
 * <p>
 * MATCHES ValorantNarrator's Message.java implementation exactly.
 * <p>
 * Responsibilities:
 *  - Extract attributes (from / jid, type)
 *  - Extract and HTML-unescape the body text
 *  - Classify the chat channel (PARTY / TEAM / ALL / WHISPER)
 *  - Determine if the message was authored by the local player (isOwnMessage)
 */
public class Message {
    private static final Logger logger = LoggerFactory.getLogger(Message.class);

    // Precompiled patterns - support BOTH single and double quotes (Riot uses both)
    private static final Pattern TYPE_PATTERN = Pattern.compile("type=['\"]([^'\"]*)['\"]", Pattern.CASE_INSENSITIVE);
    private static final Pattern BODY_PATTERN = Pattern.compile("<body>(.*?)</body>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern JID_PATTERN = Pattern.compile("jid=['\"]([^'\"]*)['\"]", Pattern.CASE_INSENSITIVE);
    private static final Pattern FROM_PATTERN = Pattern.compile("from=['\"]([^'\"]*)['\"]", Pattern.CASE_INSENSITIVE);

    // Carbon copy detection patterns (for extracting inner message 'to' attribute)
    private static final Pattern INNER_MESSAGE_PATTERN = Pattern.compile("<forwarded[^>]*>\\s*<message([^>]*)>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TO_PATTERN = Pattern.compile("to=['\"]([^'\"]*)['\"]", Pattern.CASE_INSENSITIVE);

    private final String content;     // The (unescaped) message content
    private final String id;          // Full JID (user@server/resource) or fallback '@'
    private final String userId;      // Portion before '@' of the JID
    private final boolean ownMessage; // Whether this message was sent by the current user
    private final String messageType; // Using String constants (PARTY, TEAM, ALL, WHISPER)
    private final String fromJid;     // Raw 'from' attribute - AUTHORITATIVE sender identity (ValorantNarrator reference)

    private PlayerAccount sender;     // The player account associated with this message

    /**
     * Parse XMPP message stanza into Message object.
     * MATCHES ValorantNarrator's Message constructor exactly.
     *
     * CARBON COPY HANDLING:
     * When you send a message, Valorant echoes it back as a carbon copy with:
     *   - Outer message: from = your JID
     *   - Inner message (inside <forwarded>): to = actual destination (party/team room)
     * For carbon copies, we use the inner 'to' attribute for classification.
     */
    public Message(String xml) {
        if (xml == null) {
            throw new IllegalArgumentException("xml cannot be null");
        }

        Matcher typeMatcher = TYPE_PATTERN.matcher(xml);
        Matcher bodyMatcher = BODY_PATTERN.matcher(xml);
        Matcher jidMatcher = JID_PATTERN.matcher(xml);
        Matcher fromMatcher = FROM_PATTERN.matcher(xml);

        // Extract ID: prefer jid attribute, fallback to from attribute
        // ValorantNarrator: id = jidMatcher.find() ? jidMatcher.group(1) : fromMatcher.find() ? fromMatcher.group(1) : "@";
        id = jidMatcher.find() ? jidMatcher.group(1) : (fromMatcher.find() ? fromMatcher.group(1) : "@");

        // Extract 'type' attribute value (e.g., chat, groupchat)
        String typeAttr = typeMatcher.find() ? typeMatcher.group(1) : null;

        // Re-match from for message classification (ValorantNarrator resets and re-matches)
        fromMatcher = FROM_PATTERN.matcher(xml);
        String fromAttr = fromMatcher.find() ? fromMatcher.group(1) : null;

        // Carbon copy handling: Valorant sends carbon copies for messages YOU send.
        // The outer 'from' is your JID, but the inner <message to="..."> has the actual destination.
        // If that destination is a MUC room (ares-parties, ares-pregame, ares-coregame),
        // we override 'from' with 'to' so getMessageType() classifies correctly.
        if (xml.contains("urn:xmpp:carbons:2")) {
            Matcher innerMsgMatcher = INNER_MESSAGE_PATTERN.matcher(xml);
            if (innerMsgMatcher.find()) {
                String innerMsgAttrs = innerMsgMatcher.group(1);
                Matcher toMatcher = TO_PATTERN.matcher(innerMsgAttrs);
                if (toMatcher.find()) {
                    String innerTo = toMatcher.group(1);
                    // Only override if 'to' points to a MUC room
                    if (innerTo.contains("ares-parties") ||
                        innerTo.contains("ares-pregame") ||
                        innerTo.contains("ares-coregame")) {
                        logger.debug("🔄 Carbon copy detected - overriding 'from' with inner 'to': '{}'", innerTo);
                        fromAttr = innerTo;
                    }
                }
            }
        }

        // STORE RAW 'from' ATTRIBUTE - This is AUTHORITATIVE for sender identity (ValorantNarrator reference)
        // For carbon copies, fromAttr may have been overridden with inner 'to' for classification,
        // but we need the ORIGINAL 'from' for sender identity. Re-extract it.
        Matcher originalFromMatcher = FROM_PATTERN.matcher(xml);
        this.fromJid = originalFromMatcher.find() ? originalFromMatcher.group(1) : null;

        // Classify message type using from attribute - DOMAIN FIRST, TYPE SECOND (ValorantNarrator way)
        // Safety: if fromAttr is null, we cannot classify the message
        if (fromAttr == null) {
            logger.warn("⚠️ Message has no 'from' attribute - cannot classify");
            messageType = null;
        } else {
            messageType = getMessageType(fromAttr, typeAttr);
        }

        // Extract and unescape body content
        content = bodyMatcher.find() ? HtmlEscape.unescapeHtml(bodyMatcher.group(1)) : null;

        // Extract userId (portion before '@')
        userId = id.split("@")[0];

        // Determine if this is our own message (legacy - ChatDataHandler uses extractPuuid now)
        String selfId = ChatDataHandler.getInstance().getSelfID();
        ownMessage = selfId != null && selfId.equalsIgnoreCase(userId);


        // Log parsed message for debugging
        logger.info("📝 Parsed Message: type={} userId={} own={} from='{}' body='{}'",
            messageType, userId, ownMessage, this.fromJid,
            content != null ? (content.length() > 50 ? content.substring(0, 47) + "..." : content) : "(null)");
    }

    /**
     * Copy constructor with modified content (for text expansion)
     */
    public Message(Message original, String newContent) {
        this.id = original.id;
        this.userId = original.userId;
        this.ownMessage = original.ownMessage;
        this.messageType = original.messageType;
        this.content = newContent;
        this.sender = original.sender;
        this.fromJid = original.fromJid;
    }

    /**
     * Classify message type based on 'from' JID and 'type' attribute.
     * EXACTLY MATCHES ValorantNarrator's getMessageType() logic.
     *
     * Key principle: CHECK SERVER DOMAIN FIRST (ares-parties, ares-pregame, ares-coregame)
     * Only fall back to type attribute if domain doesn't match known MUC servers.
     *
     * @param fromTag The 'from' attribute value (e.g., "roomid@ares-coregame.ap.pvp.net/nickname")
     * @param type The 'type' attribute value (e.g., "chat", "groupchat")
     * @return Message type string (PARTY, TEAM, ALL, WHISPER) or null
     */
    private static String getMessageType(String fromTag, String type) {
        if (fromTag == null || !fromTag.contains("@")) {
            logger.debug("❌ Cannot classify: invalid fromTag '{}'", fromTag);
            return null;
        }

        String[] splitTag = fromTag.split("@");
        if (splitTag.length < 2) {
            logger.debug("❌ Cannot classify: malformed JID '{}'", fromTag);
            return null;
        }

        String idPart = splitTag[0];
        String serverPart = splitTag[1];

        // Remove resource part (everything after /) before extracting server type
        // Example: "ares-parties.jp1.pvp.net/user123" -> "ares-parties.jp1.pvp.net"
        if (serverPart.contains("/")) {
            serverPart = serverPart.substring(0, serverPart.indexOf("/"));
        }

        // Extract server type (first part before first dot)
        // Example: "ares-parties.jp1.pvp.net" -> "ares-parties"
        String serverType = serverPart.split("\\.")[0];

        logger.debug("🔍 Classifying message:");
        logger.debug("   - Full 'from': '{}'", fromTag);
        logger.debug("   - ID part: '{}'", idPart);
        logger.debug("   - Server part (after @): '{}'", serverPart);
        logger.debug("   - Server type (before first .): '{}'", serverType);
        logger.debug("   - 'type' attribute: '{}'", type);

        // PRIORITY 1: Check server domain (MUC rooms)
        switch (serverType) {
            case "ares-parties":
                logger.debug("✅ Classified as PARTY (domain: ares-parties)");
                return Chat.TYPE_PARTY;

            case "ares-pregame":
                logger.debug("✅ Classified as TEAM (domain: ares-pregame)");
                return Chat.TYPE_TEAM;

            case "ares-coregame":
                // Distinguish ALL chat if room id ends with 'all'
                if (idPart.endsWith("all")) {
                    logger.debug("✅ Classified as ALL (domain: ares-coregame, id ends with 'all')");
                    return Chat.TYPE_ALL;
                }
                logger.debug("✅ Classified as TEAM (domain: ares-coregame)");
                return Chat.TYPE_TEAM;

            default:
                // PRIORITY 2: Fall back to type attribute for direct messages
                if ("chat".equalsIgnoreCase(type)) {
                    logger.debug("✅ Classified as WHISPER (type=chat, unknown domain '{}'))", serverType);
                    return Chat.TYPE_WHISPER;
                }
                logger.warn("⚠️ Unknown classification: serverType='{}', from='{}', type='{}'",
                           serverType, fromTag, type);
                return null;
        }
    }



   /**
     * Sets the player account associated with this message
     * @param account The player account
     */
    public void setPlayerAccount(PlayerAccount account) {
        this.sender = account;
    }

    /**
     * Gets the player account associated with this message
     * @return The player account, or null if not set
     */
    public PlayerAccount getPlayerAccount() {
        return this.sender;
    }

    /**
     * Gets the display name for this message
     * @return The display name from the player account if available, otherwise the userId
     */
    public String getDisplayName() {
        return sender != null ? sender.DisplayName() : userId;
    }

    // === Getters ===

    public String getMessageType() { return messageType; }
    public String getContent() { return content; }
    public String getId() { return id; }
    public boolean isOwnMessage() { return ownMessage; }
    public String getUserId() { return userId; }

    /**
     * Gets the raw 'from' attribute from the message stanza.
     * This is the AUTHORITATIVE source for sender identity (ValorantNarrator reference).
     *
     * For MUC messages (party/team): room@server/SENDER_PUUID
     * For direct messages (whisper): SENDER_PUUID@server
     *
     * @return The raw 'from' attribute value, or null if not present
     */
    public String getFrom() { return fromJid; }

    @Override
    public String toString() {
        return String.format("(%s)%s: %s", messageType, userId, content);
    }
}