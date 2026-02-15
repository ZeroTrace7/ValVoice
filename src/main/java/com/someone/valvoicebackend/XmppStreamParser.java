package com.someone.valvoicebackend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.*;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * XmppStreamParser - StAX-based XML parser for XMPP message stanzas.
 *
 * Phase 2B: Production Cutover - This is now the ONLY parser for XMPP messages.
 * All regex-based parsing has been removed from ValVoiceBackend.
 *
 * ValorantNarrator Reference Architecture:
 * - Uses streaming XML parsing (StAX) for robustness
 * - Extracts: from, to, id, type, stamp, body, jid
 * - Handles malformed XML gracefully (never crashes MITM)
 * - Returns structured ParsedMessage DTOs
 *
 * StAX Benefits over Regex:
 * - Proper handling of XML escaping/entities
 * - Correct parsing of nested elements
 * - Handles attribute order variations
 * - More resilient to XML formatting changes
 *
 * PHASE 1 SECURITY HARDENING (VN-Parity):
 * - Input length caps to prevent memory exhaustion
 * - Fail-safe parsing with silent drop on malformed payloads
 * - No schema validation (VN-style loose parsing)
 */
public class XmppStreamParser {
    private static final Logger logger = LoggerFactory.getLogger(XmppStreamParser.class);

    // ═══════════════════════════════════════════════════════════════════════════════
    // PHASE 1 SECURITY: INPUT LENGTH CAPS (VN-Parity)
    // ═══════════════════════════════════════════════════════════════════════════════
    // Valorant chat messages are typically short (< 1KB).
    // These caps prevent malformed/malicious payloads from exhausting memory.
    // If exceeded → silently drop stanza (log WARN, no crash, no propagation).
    // ═══════════════════════════════════════════════════════════════════════════════

    /** Max length for entire XMPP stanza XML (32KB should cover any legitimate message) */
    private static final int MAX_STANZA_LENGTH = 32 * 1024;

    /** Max length for chat body content (8KB is very generous for chat messages) */
    private static final int MAX_BODY_LENGTH = 8 * 1024;

    /** Max length for Base64 presence payload (16KB handles richest presence data) */
    private static final int MAX_PRESENCE_PAYLOAD_LENGTH = 16 * 1024;

    // StAX factory - thread-safe after initialization
    private static final XMLInputFactory XML_INPUT_FACTORY;

    static {
        XML_INPUT_FACTORY = XMLInputFactory.newInstance();
        // Security: Disable external entities to prevent XXE attacks
        XML_INPUT_FACTORY.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        XML_INPUT_FACTORY.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        // Performance: Coalesce adjacent text nodes
        XML_INPUT_FACTORY.setProperty(XMLInputFactory.IS_COALESCING, true);
    }

    /**
     * Parse a single XMPP message stanza and extract all relevant fields.
     *
     * PHASE 1 SECURITY: Fail-safe parsing with input validation.
     * - Checks stanza length against MAX_STANZA_LENGTH
     * - Checks body length against MAX_BODY_LENGTH
     * - Silently drops oversized or malformed payloads (log WARN, return null)
     *
     * @param xml Raw XML string containing a &lt;message&gt; stanza
     * @return ParsedMessage with extracted fields, or null if parsing fails or payload exceeds limits
     */
    public static ParsedMessage parseMessage(String xml) {
        // === PHASE 1 SECURITY: Null/Empty check ===
        if (xml == null || xml.isEmpty()) {
            return null;
        }

        // === PHASE 1 SECURITY: Stanza length cap ===
        if (xml.length() > MAX_STANZA_LENGTH) {
            logger.warn("[SECURITY] Dropping oversized stanza: length={} exceeds max={}",
                       xml.length(), MAX_STANZA_LENGTH);
            return null;
        }

        try {
            XMLStreamReader reader = XML_INPUT_FACTORY.createXMLStreamReader(new StringReader(xml));
            ParsedMessage.Builder builder = ParsedMessage.builder().rawXml(xml);

            String currentElement = null;
            StringBuilder bodyBuilder = null;
            boolean inBody = false;
            boolean foundMessage = false;

            while (reader.hasNext()) {
                int event = reader.next();

                switch (event) {
                    case XMLStreamConstants.START_ELEMENT:
                        currentElement = reader.getLocalName();

                        if ("message".equalsIgnoreCase(currentElement)) {
                            foundMessage = true;
                            // Extract message attributes
                            for (int i = 0; i < reader.getAttributeCount(); i++) {
                                String attrName = reader.getAttributeLocalName(i).toLowerCase();
                                String attrValue = reader.getAttributeValue(i);

                                switch (attrName) {
                                    case "from" -> builder.from(attrValue);
                                    case "to" -> builder.to(attrValue);
                                    case "id" -> builder.id(attrValue);
                                    case "type" -> builder.type(attrValue);
                                }
                            }
                        } else if ("body".equalsIgnoreCase(currentElement)) {
                            inBody = true;
                            bodyBuilder = new StringBuilder();
                        } else if ("delay".equalsIgnoreCase(currentElement)) {
                            // XEP-0203 Delayed Delivery - extract stamp
                            for (int i = 0; i < reader.getAttributeCount(); i++) {
                                String attrName = reader.getAttributeLocalName(i).toLowerCase();
                                if ("stamp".equals(attrName)) {
                                    builder.stamp(reader.getAttributeValue(i));
                                    break;
                                }
                            }
                        } else if ("archived".equalsIgnoreCase(currentElement) ||
                                   "result".equalsIgnoreCase(currentElement)) {
                            // MAM archived message - check for stamp
                            for (int i = 0; i < reader.getAttributeCount(); i++) {
                                String attrName = reader.getAttributeLocalName(i).toLowerCase();
                                if ("stamp".equals(attrName)) {
                                    builder.stamp(reader.getAttributeValue(i));
                                }
                            }
                        }
                        // Also check for stamp attribute on message element itself
                        if (foundMessage && "message".equalsIgnoreCase(currentElement)) {
                            for (int i = 0; i < reader.getAttributeCount(); i++) {
                                String attrName = reader.getAttributeLocalName(i).toLowerCase();
                                if ("stamp".equals(attrName)) {
                                    builder.stamp(reader.getAttributeValue(i));
                                }
                            }
                        }
                        break;

                    case XMLStreamConstants.CHARACTERS:
                    case XMLStreamConstants.CDATA:
                        if (inBody && bodyBuilder != null) {
                            // === PHASE 1 SECURITY: Body length cap (streaming check) ===
                            String text = reader.getText();
                            if (bodyBuilder.length() + text.length() > MAX_BODY_LENGTH) {
                                logger.warn("[SECURITY] Dropping message with oversized body: length would exceed max={}",
                                           MAX_BODY_LENGTH);
                                reader.close();
                                return null;
                            }
                            bodyBuilder.append(text);
                        }
                        break;

                    case XMLStreamConstants.END_ELEMENT:
                        String endElement = reader.getLocalName();
                        if ("body".equalsIgnoreCase(endElement)) {
                            inBody = false;
                            if (bodyBuilder != null) {
                                builder.body(bodyBuilder.toString());
                            }
                        }
                        break;
                }
            }

            reader.close();

            if (!foundMessage) {
                logger.debug("[StAX] No <message> element found in XML");
                return null;
            }

            ParsedMessage result = builder.build();
            logger.debug("[StAX] Parsed: {}", result);
            return result;

        } catch (XMLStreamException e) {
            // XML parsing failed - log and return null (never crash)
            logger.debug("[StAX] XML parsing failed (malformed payload dropped): {}", e.getMessage());
            return null;
        } catch (Exception e) {
            // Catch-all for unexpected errors - VN-parity: fail-safe, never crash
            logger.debug("[StAX] Unexpected error parsing XML (payload dropped): {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parse multiple message stanzas from a single XML string.
     * Handles cases where MITM sends multiple messages concatenated.
     *
     * PHASE 1 SECURITY: Input validation with length caps.
     *
     * @param xml Raw XML string potentially containing multiple &lt;message&gt; stanzas
     * @return List of ParsedMessage objects (may be empty, never null)
     */
    public static List<ParsedMessage> parseMessages(String xml) {
        List<ParsedMessage> messages = new ArrayList<>();

        // === PHASE 1 SECURITY: Null/Empty check ===
        if (xml == null || xml.isEmpty()) {
            return messages;
        }

        // === PHASE 1 SECURITY: Stanza length cap ===
        if (xml.length() > MAX_STANZA_LENGTH) {
            logger.warn("[SECURITY] Dropping oversized multi-message stanza: length={} exceeds max={}",
                       xml.length(), MAX_STANZA_LENGTH);
            return messages;
        }

        // Quick check for message presence
        if (!xml.toLowerCase().contains("<message")) {
            return messages;
        }

        // Try to parse the whole XML first (single message case)
        ParsedMessage single = parseMessage(xml);
        if (single != null && single.hasBody()) {
            messages.add(single);
            return messages;
        }

        // If single parse failed or no body, try extracting individual messages
        // This handles concatenated messages from MITM
        int startIdx = 0;
        String xmlLower = xml.toLowerCase();

        while (true) {
            int msgStart = xmlLower.indexOf("<message", startIdx);
            if (msgStart == -1) break;

            int msgEnd = xmlLower.indexOf("</message>", msgStart);
            if (msgEnd == -1) break;

            msgEnd += "</message>".length();
            String singleXml = xml.substring(msgStart, msgEnd);

            ParsedMessage parsed = parseMessage(singleXml);
            if (parsed != null && parsed.hasBody()) {
                messages.add(parsed);
            }

            startIdx = msgEnd;
        }

        logger.debug("[StAX] Parsed {} message(s) from XML", messages.size());
        return messages;
    }

    /**
     * Check if the XML appears to be an IQ stanza (not a message).
     *
     * @param xml Raw XML string
     * @return true if this is an IQ stanza
     */
    public static boolean isIqStanza(String xml) {
        if (xml == null) return false;
        String lower = xml.toLowerCase().trim();
        return lower.startsWith("<iq") || lower.contains("<iq ");
    }

    /**
     * Check if the XML contains archive namespace (historical messages).
     *
     * @param xml Raw XML string
     * @return true if this contains jabber:iq:riotgames:archive namespace
     */
    public static boolean isArchiveStanza(String xml) {
        if (xml == null) return false;
        return xml.toLowerCase().contains("jabber:iq:riotgames:archive");
    }

    /**
     * Quick check if XML contains a message with body.
     *
     * @param xml Raw XML string
     * @return true if this appears to be a chat message with content
     */
    public static boolean containsMessageWithBody(String xml) {
        if (xml == null) return false;
        String lower = xml.toLowerCase();
        return lower.contains("<message") && lower.contains("<body>");
    }


    // ═══════════════════════════════════════════════════════════════════════════════
    // PHASE 4: PRESENCE STANZA HELPERS (Game State Awareness / Smart Mute)
    // ═══════════════════════════════════════════════════════════════════════════════
    //
    // INTENTIONAL DEVIATION FROM VALORANTNARRATOR:
    // ValorantNarrator does NOT implement presence-based game state awareness.
    // These helpers are a ValVoice enhancement for "Smart Mute" (clutch mode).
    //
    // Presence stanzas contain a Base64-encoded JSON payload in the <p> tag.
    // The payload includes sessionLoopState which indicates: MENUS, PREGAME, INGAME.
    //
    // These helpers are STRICTLY ADDITIVE - they do not modify any existing
    // parsing logic for messages, IQs, or any other stanza type.
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Check if the XML is a presence stanza.
     *
     * @param xml Raw XML string
     * @return true if this is a &lt;presence&gt; stanza
     */
    public static boolean isPresenceStanza(String xml) {
        if (xml == null) return false;
        String lower = xml.toLowerCase().trim();
        return lower.startsWith("<presence") || lower.contains("<presence ");
    }

    /**
     * Extract the &lt;p&gt; (presence payload) element content from a presence stanza.
     * The &lt;p&gt; element contains Base64-encoded JSON with game state info.
     *
     * PHASE 1 SECURITY: Input validation with length caps.
     *
     * This method uses a two-phase approach:
     * 1. First attempts StAX parsing for proper XML handling
     * 2. Falls back to simple string extraction if StAX fails (handles malformed XML)
     *
     * @param xml Raw XML string containing a presence stanza
     * @return The Base64-encoded payload string, or null if not found or exceeds limits
     */
    public static String extractPresencePayload(String xml) {
        // === PHASE 1 SECURITY: Null/Empty check ===
        if (xml == null || xml.isEmpty()) {
            return null;
        }

        // === PHASE 1 SECURITY: Stanza length cap ===
        if (xml.length() > MAX_STANZA_LENGTH) {
            logger.warn("[SECURITY] Dropping oversized presence stanza: length={} exceeds max={}",
                       xml.length(), MAX_STANZA_LENGTH);
            return null;
        }

        // Clean the XML: remove BOM and trim whitespace
        String cleanedXml = cleanXmlForParsing(xml);

        // Phase 1: Try StAX parsing (proper XML handling)
        String staxResult = extractPresencePayloadViaStax(cleanedXml);
        if (staxResult != null) {
            // === PHASE 1 SECURITY: Presence payload length cap ===
            if (staxResult.length() > MAX_PRESENCE_PAYLOAD_LENGTH) {
                logger.warn("[SECURITY] Dropping oversized presence payload: length={} exceeds max={}",
                           staxResult.length(), MAX_PRESENCE_PAYLOAD_LENGTH);
                return null;
            }
            return staxResult;
        }

        // Phase 2: Fallback to simple string extraction (handles malformed XML)
        String regexResult = extractPresencePayloadViaRegex(xml);
        if (regexResult != null) {
            // === PHASE 1 SECURITY: Presence payload length cap ===
            if (regexResult.length() > MAX_PRESENCE_PAYLOAD_LENGTH) {
                logger.warn("[SECURITY] Dropping oversized presence payload (regex): length={} exceeds max={}",
                           regexResult.length(), MAX_PRESENCE_PAYLOAD_LENGTH);
                return null;
            }
        }
        return regexResult;
    }

    /**
     * Clean XML string for parsing: remove BOM, trim whitespace, normalize.
     */
    private static String cleanXmlForParsing(String xml) {
        if (xml == null) return null;

        // Remove UTF-8 BOM if present (EF BB BF or \uFEFF)
        String cleaned = xml;
        if (cleaned.startsWith("\uFEFF")) {
            cleaned = cleaned.substring(1);
        }
        // Also handle potential BOM in byte form that got converted
        if (cleaned.startsWith("\u00EF\u00BB\u00BF")) {
            cleaned = cleaned.substring(3);
        }

        // Trim leading/trailing whitespace
        cleaned = cleaned.trim();

        // Ensure it starts with < (skip any garbage before XML)
        int xmlStart = cleaned.indexOf('<');
        if (xmlStart > 0) {
            cleaned = cleaned.substring(xmlStart);
        }

        return cleaned;
    }

    /**
     * Extract presence payload using StAX (proper XML parsing).
     */
    private static String extractPresencePayloadViaStax(String xml) {
        try {
            XMLStreamReader reader = XML_INPUT_FACTORY.createXMLStreamReader(new StringReader(xml));

            while (reader.hasNext()) {
                int event = reader.next();

                if (event == XMLStreamConstants.START_ELEMENT) {
                    String elementName = reader.getLocalName();
                    if ("p".equalsIgnoreCase(elementName)) {
                        // Use getElementText() which properly handles the content
                        // This reads all text content up to the matching end tag
                        String payload = reader.getElementText().trim();
                        reader.close();
                        return payload.isEmpty() ? null : payload;
                    }
                }
            }

            reader.close();
            return null;

        } catch (XMLStreamException e) {
            logger.debug("[StAX] Presence payload extraction failed (will try fallback): {}", e.getMessage());
            return null;
        } catch (Exception e) {
            logger.debug("[StAX] Unexpected error in presence extraction: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extract presence payload using simple regex (fallback for malformed XML).
     * This handles cases where StAX cannot parse the XML.
     */
    private static String extractPresencePayloadViaRegex(String xml) {
        if (xml == null) return null;

        try {
            // Simple pattern to extract content between <p> and </p>
            int startTag = xml.indexOf("<p>");
            if (startTag == -1) {
                // Try case-insensitive
                String lower = xml.toLowerCase();
                startTag = lower.indexOf("<p>");
            }

            if (startTag == -1) return null;

            int contentStart = startTag + 3; // length of "<p>"

            int endTag = xml.indexOf("</p>", contentStart);
            if (endTag == -1) {
                // Try case-insensitive
                String lower = xml.toLowerCase();
                endTag = lower.indexOf("</p>", contentStart);
            }

            if (endTag == -1) return null;

            String payload = xml.substring(contentStart, endTag).trim();

            if (!payload.isEmpty()) {
                logger.debug("[Regex Fallback] Extracted presence payload ({} chars)", payload.length());
                return payload;
            }

            return null;
        } catch (Exception e) {
            logger.debug("[Regex Fallback] Failed to extract presence payload: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Check if presence stanza contains a &lt;p&gt; payload element.
     * Quick check without full parsing.
     *
     * @param xml Raw XML string
     * @return true if presence contains a &lt;p&gt; element
     */
    public static boolean presenceHasPayload(String xml) {
        if (xml == null) return false;
        String lower = xml.toLowerCase();
        return isPresenceStanza(xml) && lower.contains("<p>") && lower.contains("</p>");
    }
}
