package com.someone.valvoice;

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
    private final ChatMessageType messageType; // Classified chat channel

    public Message(String xml) {
        if (xml == null) {
            throw new IllegalArgumentException("xml cannot be null");
        }

        Matcher typeMatcher = TYPE_PATTERN.matcher(xml);
        Matcher bodyMatcher = BODY_PATTERN.matcher(xml);
        Matcher jidMatcher = JID_PATTERN.matcher(xml);
        Matcher fromMatcher = FROM_PATTERN.matcher(xml);

        String rawId = null;
        if (jidMatcher.find()) {
            rawId = jidMatcher.group(2);
        } else if (fromMatcher.find()) { // reuse existing from matcher result
            rawId = fromMatcher.group(2);
        }
        id = rawId != null ? rawId : "@"; // fallback

        // Extract 'type' attribute value (e.g., chat)
        String typeAttr = null;
        if (typeMatcher.find()) {
            typeAttr = typeMatcher.group(2);
        }

        // Reset fromMatcher (since we may have consumed it above while resolving id)
        fromMatcher = FROM_PATTERN.matcher(xml);
        String fromAttr = null;
        if (fromMatcher.find()) {
            fromAttr = fromMatcher.group(2);
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

        // Determine if this is our own message
        String selfId = ChatDataHandler.getInstance().getProperties().getSelfID();
        ownMessage = selfId != null && selfId.equalsIgnoreCase(userId);

        // Classify channel
        ChatMessageType derivedType = null;
        try {
            derivedType = classifyMessage(Objects.requireNonNullElse(fromAttr, id), typeAttr);
        } catch (Exception e) {
            logger.debug("Could not classify message. from='{}' type='{}'", fromAttr, typeAttr, e);
        }
        messageType = derivedType;

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

    private ChatMessageType classifyMessage(String fromTag, String typeAttr) {
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
                return ChatMessageType.PARTY;
            case "ares-pregame":
                logger.debug("✅ Classified as TEAM/PREGAME (from {})", fromTag);
                return ChatMessageType.TEAM;
            case "ares-coregame":
                // Distinguish ALL chat if id ends with 'all'
                if (idPart.endsWith("all")) {
                    logger.debug("✅ Classified as ALL (from {})", fromTag);
                    return ChatMessageType.ALL;
                }
                logger.debug("✅ Classified as TEAM/COREGAME (from {})", fromTag);
                return ChatMessageType.TEAM;
            default:
                if ("chat".equalsIgnoreCase(typeAttr)) {
                    logger.debug("✅ Classified as WHISPER (from {}, type={})", fromTag, typeAttr);
                    return ChatMessageType.WHISPER;
                }
                logger.warn("⚠️ Unknown server type '{}' for message from '{}' (typeAttr='{}')", serverType, fromTag, typeAttr);
                return null;
        }
    }

    // === Getters ===

    public ChatMessageType getMessageType() { return messageType; }
    public String getContent() { return content; }
    public String getId() { return id; }
    public boolean isOwnMessage() { return ownMessage; }
    public String getUserId() { return userId; }

    @Override
    public String toString() {
        return String.format("(%s)%s: %s", messageType, id, content);
    }
}

