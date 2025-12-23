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

    private final String content;     // The (unescaped) message content
    private final String id;          // Full JID (user@server/resource) or fallback '@'
    private final String userId;      // Portion before '@' of the JID
    private final boolean ownMessage; // Whether this message was sent by the current user
    private final String messageType; // Using String constants (PARTY, TEAM, ALL, WHISPER)

    private PlayerAccount sender;     // The player account associated with this message

    /**
     * Parse XMPP message stanza into Message object.
     * MATCHES ValorantNarrator's Message constructor exactly.
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
        if (jidMatcher.find()) {
            id = jidMatcher.group(1);
        } else if (fromMatcher.find()) {
            id = fromMatcher.group(1);
        } else {
            id = "@"; // fallback
        }

        // Extract 'type' attribute value (e.g., chat, groupchat)
        String typeAttr = typeMatcher.find() ? typeMatcher.group(1) : null;

        // Re-match from for message classification (ValorantNarrator resets and re-matches)
        fromMatcher = FROM_PATTERN.matcher(xml);
        String fromAttr = fromMatcher.find() ? fromMatcher.group(1) : null;

        // Classify message type using from attribute
        messageType = getMessageType(Objects.requireNonNull(fromAttr, "from attribute is required"), typeAttr);

        // Extract and unescape body content
        content = bodyMatcher.find() ? HtmlEscape.unescapeHtml(bodyMatcher.group(1)) : null;

        // Extract userId (portion before '@')
        userId = id.split("@")[0];

        // Determine if this is our own message
        String selfId = ChatDataHandler.getInstance().getSelfID();
        ownMessage = selfId != null && selfId.equalsIgnoreCase(userId);

        // Log parsed message for debugging
        logger.info("📝 Parsed Message: type={} userId={} own={} from='{}' body='{}'",
            messageType, userId, ownMessage, fromAttr,
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
    }

    /**
     * Classify message type based on 'from' JID and 'type' attribute.
     * MATCHES ValorantNarrator's getMessageType() exactly.
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
        String serverType = serverPart.split("\\.")[0]; // e.g., "ares-coregame"

        logger.debug("🔍 Classifying: serverType='{}', idPart='{}', type='{}'", serverType, idPart, type);

        switch (serverType) {
            case "ares-parties":
                logger.debug("✅ Classified as PARTY");
                return Chat.TYPE_PARTY;

            case "ares-pregame":
                logger.debug("✅ Classified as TEAM (pregame)");
                return Chat.TYPE_TEAM;

            case "ares-coregame":
                // Distinguish ALL chat if room id ends with 'all'
                if (idPart.endsWith("all")) {
                    logger.debug("✅ Classified as ALL");
                    return Chat.TYPE_ALL;
                }
                logger.debug("✅ Classified as TEAM (coregame)");
                return Chat.TYPE_TEAM;

            default:
                // Direct messages (whispers) have type="chat"
                if ("chat".equalsIgnoreCase(type)) {
                    logger.debug("✅ Classified as WHISPER");
                    return Chat.TYPE_WHISPER;
                }
                logger.warn("⚠️ Unknown server type '{}' from '{}' (type='{}')", serverType, fromTag, type);
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

    @Override
    public String toString() {
        return String.format("(%s)%s: %s", messageType, userId, content);
    }
}