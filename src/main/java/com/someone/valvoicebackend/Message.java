package com.someone.valvoicebackend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a single Valorant XMPP chat message parsed from the raw XML stanza.
 * <p>
 * Responsibilities:
 *  - Extract attributes (from / jid, type)
 *  - Extract and HTML-unescape the body text
 *  - Classify the chat channel (PARTY / TEAM / ALL / WHISPER)
 *  - Determine if the message was authored by the local player (isOwnMessage)
 */
public class Message {
    private static final Logger logger = LoggerFactory.getLogger(Message.class);

    // Precompiled patterns (support both single and double quotes)
    private static final Pattern TYPE_PATTERN = Pattern.compile("type=([\"'])(.*?)\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern BODY_PATTERN = Pattern.compile("<body>(.*?)</body>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern JID_PATTERN = Pattern.compile("jid=([\"'])(.*?)\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern FROM_PATTERN = Pattern.compile("from=([\"'])(.*?)\\1", Pattern.CASE_INSENSITIVE);

    private final String content;     // The (unescaped) message content
    private final String id;          // Full JID (user@server/resource) or fallback '@'
    private final String userId;      // Portion before '@' of the JID
    private final boolean ownMessage; // Whether this message was sent by the current user
    private final String messageType; // Now using String instead of ChatMessageType enum

    private static final Pattern ENCODED_XML = Pattern.compile("&[a-zA-Z]+;");
    private static final String ENCODED_AMP = "&amp;";
    private static final String ENCODED_LT = "&lt;";
    private static final String ENCODED_GT = "&gt;";

    private PlayerAccount sender;     // The player account associated with this message

    public Message(String xml) {
        if (xml == null) {
            throw new IllegalArgumentException("xml cannot be null");
        }

        Matcher typeMatcher = TYPE_PATTERN.matcher(xml);
        Matcher bodyMatcher = BODY_PATTERN.matcher(xml);
        Matcher jidMatcher = JID_PATTERN.matcher(xml);
        Matcher fromMatcher = FROM_PATTERN.matcher(xml);

        // Extract 'from' attribute first (needed for message classification)
        String fromAttr = null;
        if (fromMatcher.find()) {
            fromAttr = fromMatcher.group(2);
        }

        // Extract ID (prefer jid, fallback to from)
        // Reset fromMatcher if we need to use it again
        String rawId = null;
        if (jidMatcher.find()) {
            rawId = jidMatcher.group(2);
        } else if (fromAttr != null) {
            // Use already extracted fromAttr instead of re-matching
            rawId = fromAttr;
        }
        id = rawId != null ? rawId : "@"; // fallback

        // Extract 'type' attribute value (e.g., chat, groupchat)
        String typeAttr = null;
        if (typeMatcher.find()) {
            typeAttr = typeMatcher.group(2);
        }

        // Message body (HTML unescaped)
        String rawContent = null;
        if (bodyMatcher.find()) {
            rawContent = bodyMatcher.group(1);
        }
        content = rawContent == null ? null : HtmlEscape.unescapeHtml(rawContent.trim());

        // Determine userId (segment before '@')
        if (id.contains("@")) {
            userId = id.substring(0, id.indexOf('@'));
        } else {
            userId = id; // improbable but safe
        }

        // Determine if this is our own message - now using direct selfID access
        String selfId = ChatDataHandler.getInstance().getSelfID();
        ownMessage = selfId != null && selfId.equalsIgnoreCase(userId);

        // Classify channel - use fromAttr (already extracted) or fallback to id
        String derivedType = null;
        try {
            derivedType = classifyMessage(Objects.requireNonNullElse(fromAttr, id), typeAttr);
        } catch (Exception e) {
            logger.debug("Could not classify message. from='{}' type='{}'", fromAttr, typeAttr, e);
        }
        messageType = derivedType;

        // Log parsed message at INFO level for debugging
        logger.info("📝 Parsed Message: type={} userId={} own={} from='{}' body='{}'",
            messageType, userId, ownMessage, fromAttr,
            content != null ? (content.length() > 50 ? content.substring(0, 47) + "..." : content) : "(null)");

        if (logger.isTraceEnabled()) {
            logger.trace("Parsed Message: type={} userId={} own={} body='{}'", messageType, userId, ownMessage, content);
        }
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
    }

    private static String classifyMessage(String fromTag, String typeAttr) {
        if (fromTag == null) {
            logger.debug("❌ Cannot classify: fromTag is null");
            return null;
        }
        String[] splitTag = fromTag.split("@");
        if (splitTag.length < 2) {
            logger.debug("❌ Cannot classify: malformed JID '{}'", fromTag);
            return null;
        }
        String idPart = splitTag[0];
        String serverPartRaw = splitTag[1];
        String serverType = serverPartRaw.split("\\.")[0]; // e.g. ares-coregame

        logger.debug("🔍 Classifying message: serverType='{}', idPart='{}', typeAttr='{}'", serverType, idPart, typeAttr);

        switch (serverType) {
            case "ares-parties":
                logger.debug("✅ Classified as PARTY (from {})", fromTag);
                return Chat.TYPE_PARTY;
            case "ares-pregame":
                logger.debug("✅ Classified as TEAM/PREGAME (from {})", fromTag);
                return Chat.TYPE_TEAM;
            case "ares-coregame":
                // Distinguish ALL chat if id ends with 'all'
                if (idPart.endsWith("all")) {
                    logger.debug("✅ Classified as ALL (from {})", fromTag);
                    return Chat.TYPE_ALL;
                }
                logger.debug("✅ Classified as TEAM/COREGAME (from {})", fromTag);
                return Chat.TYPE_TEAM;
            default:
                if ("chat".equalsIgnoreCase(typeAttr)) {
                    logger.debug("✅ Classified as WHISPER (from {}, type={})", fromTag, typeAttr);
                    return Chat.TYPE_WHISPER;
                }
                logger.warn("⚠️ Unknown server type '{}' for message from '{}' (typeAttr='{}')", serverType, fromTag, typeAttr);
                return null;
        }
    }

    // Helper method to extract attribute value from XML string
    private static String extractAttribute(String xml, String attr) {
        int start = xml.indexOf(attr + "='");
        if (start == -1) return null;
        start += attr.length() + 2;
        int end = xml.indexOf("'", start);
        return end == -1 ? null : xml.substring(start, end);
    }

    // Helper method to extract body content from XML string
    private static String extractBody(String xml) {
        int start = xml.indexOf("<body>");
        if (start == -1) return null;
        start += 6;
        int end = xml.indexOf("</body>", start);
        return end == -1 ? null : xml.substring(start, end);
    }

    // Simple XML content decoder for encoded entities
    private static String decodeXml(String encoded) {
        if (encoded == null) return null;
        if (!ENCODED_XML.matcher(encoded).find()) return encoded;

        return encoded.replace(ENCODED_AMP, "&")
                     .replace(ENCODED_LT, "<")
                     .replace(ENCODED_GT, ">");
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
        return String.format("Message[type=%s from=%s content=%s]",
            messageType, userId, content == null ? null :
            (content.length() > 50 ? content.substring(0, 47) + "..." : content));
    }
}
