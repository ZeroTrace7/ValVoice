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
 */
public class XmppStreamParser {
    private static final Logger logger = LoggerFactory.getLogger(XmppStreamParser.class);

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
     * @param xml Raw XML string containing a &lt;message&gt; stanza
     * @return ParsedMessage with extracted fields, or null if parsing fails
     */
    public static ParsedMessage parseMessage(String xml) {
        if (xml == null || xml.isEmpty()) {
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
                            bodyBuilder.append(reader.getText());
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
            logger.debug("[StAX] XML parsing failed: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            // Catch-all for unexpected errors
            logger.debug("[StAX] Unexpected error parsing XML: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parse multiple message stanzas from a single XML string.
     * Handles cases where MITM sends multiple messages concatenated.
     *
     * @param xml Raw XML string potentially containing multiple &lt;message&gt; stanzas
     * @return List of ParsedMessage objects (may be empty, never null)
     */
    public static List<ParsedMessage> parseMessages(String xml) {
        List<ParsedMessage> messages = new ArrayList<>();

        if (xml == null || xml.isEmpty()) {
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
     * @param xml Raw XML string containing a presence stanza
     * @return The Base64-encoded payload string, or null if not found
     */
    public static String extractPresencePayload(String xml) {
        if (xml == null || xml.isEmpty()) {
            return null;
        }

        try {
            XMLStreamReader reader = XML_INPUT_FACTORY.createXMLStreamReader(new StringReader(xml));
            String currentElement = null;
            StringBuilder payloadBuilder = null;
            boolean inP = false;

            while (reader.hasNext()) {
                int event = reader.next();

                switch (event) {
                    case XMLStreamConstants.START_ELEMENT:
                        currentElement = reader.getLocalName();
                        if ("p".equalsIgnoreCase(currentElement)) {
                            inP = true;
                            payloadBuilder = new StringBuilder();
                        }
                        break;

                    case XMLStreamConstants.CHARACTERS:
                    case XMLStreamConstants.CDATA:
                        if (inP && payloadBuilder != null) {
                            payloadBuilder.append(reader.getText());
                        }
                        break;

                    case XMLStreamConstants.END_ELEMENT:
                        String endElement = reader.getLocalName();
                        if ("p".equalsIgnoreCase(endElement)) {
                            inP = false;
                            if (payloadBuilder != null) {
                                String payload = payloadBuilder.toString().trim();
                                reader.close();
                                return payload.isEmpty() ? null : payload;
                            }
                        }
                        break;
                }
            }

            reader.close();
            return null;

        } catch (XMLStreamException e) {
            logger.debug("[StAX] Failed to extract presence payload: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            logger.debug("[StAX] Unexpected error extracting presence payload: {}", e.getMessage());
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
