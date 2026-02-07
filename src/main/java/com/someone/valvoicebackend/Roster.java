package com.someone.valvoicebackend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Roster - Manages PUUID to Player Name mapping for TTS announcements.
 *
 * Phase 3: Identity & Roster ("Who is Speaking?" Feature)
 *
 * ValorantNarrator Reference Architecture:
 * - Maintains a roster map (Map<String, String> puuidToName)
 * - Parses <iq type="result"> packets containing jabber:iq:riotgames:roster
 * - Enables TTS to announce "Jett says: Hello" instead of just "Hello"
 *
 * Roster IQ Packet Format (Valorant XMPP):
 * <iq type="result" id="...">
 *   <query xmlns="jabber:iq:riotgames:roster">
 *     <item jid="PUUID@domain" name="PlayerName" .../>
 *     <item jid="PUUID@domain" name="PlayerName" .../>
 *   </query>
 * </iq>
 *
 * Usage:
 * - Call parseRosterIq(xml) when receiving roster IQ packets
 * - Call getPlayerName(puuid) to look up display name for TTS
 */
public class Roster {
    private static final Logger logger = LoggerFactory.getLogger(Roster.class);

    // Singleton instance
    private static final Roster INSTANCE = new Roster();

    // PUUID → Player Name mapping (thread-safe)
    private final Map<String, String> puuidToName = new ConcurrentHashMap<>();

    // Roster namespace used by Riot XMPP
    public static final String ROSTER_NAMESPACE = "jabber:iq:riotgames:roster";

    // Pattern to detect roster IQ result packets
    private static final Pattern ROSTER_IQ_PATTERN = Pattern.compile(
        "<iq[^>]*type=['\"]result['\"][^>]*>.*?<query[^>]*xmlns=['\"]" +
        Pattern.quote(ROSTER_NAMESPACE) + "['\"][^>]*>.*?</query>.*?</iq>",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    // Pattern to extract roster items: <item jid="PUUID@domain" name="PlayerName" .../>
    // Handles both self-closing and regular tags
    private static final Pattern ROSTER_ITEM_PATTERN = Pattern.compile(
        "<item\\s+[^>]*jid=['\"]([^'\"@]+)@[^'\"]*['\"][^>]*name=['\"]([^'\"]+)['\"][^>]*/?>",
        Pattern.CASE_INSENSITIVE
    );

    // Alternative pattern where name comes before jid
    private static final Pattern ROSTER_ITEM_PATTERN_ALT = Pattern.compile(
        "<item\\s+[^>]*name=['\"]([^'\"]+)['\"][^>]*jid=['\"]([^'\"@]+)@[^'\"]*['\"][^>]*/?>",
        Pattern.CASE_INSENSITIVE
    );

    // Pattern to extract game_name and game_tag from roster item
    // <item ... game_name="Jett" game_tag="NA1" .../>
    private static final Pattern GAME_NAME_PATTERN = Pattern.compile(
        "game_name=['\"]([^'\"]+)['\"]",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern GAME_TAG_PATTERN = Pattern.compile(
        "game_tag=['\"]([^'\"]+)['\"]",
        Pattern.CASE_INSENSITIVE
    );

    private Roster() {
        logger.info("[Roster] Initialized - ready to track player names");
    }

    public static Roster getInstance() {
        return INSTANCE;
    }

    /**
     * Check if the given XML contains a roster IQ packet.
     *
     * @param xml Raw XMPP stanza XML
     * @return true if this is a roster IQ result packet
     */
    public boolean isRosterIq(String xml) {
        if (xml == null || xml.isEmpty()) return false;
        String lower = xml.toLowerCase();
        // Check for IQ stanza with type=result (handle both single and double quotes)
        boolean isIqResult = lower.contains("<iq") &&
               (lower.contains("type=\"result\"") || lower.contains("type='result'"));
        boolean hasRosterNs = lower.contains(ROSTER_NAMESPACE.toLowerCase());

        if (isIqResult && hasRosterNs) {
            logger.debug("[Roster] Detected roster IQ packet");
            return true;
        }
        return false;
    }

    /**
     * Parse a roster IQ packet and update the PUUID→Name mapping.
     *
     * @param xml The roster IQ result XML
     * @return Number of roster entries parsed and added/updated
     */
    public int parseRosterIq(String xml) {
        if (xml == null || xml.isEmpty()) {
            return 0;
        }

        if (!isRosterIq(xml)) {
            logger.debug("[Roster] Not a roster IQ packet, skipping");
            return 0;
        }

        logger.info("╔══════════════════════════════════════════════════════════════╗");
        logger.info("║ ROSTER IQ RECEIVED (Phase 3)                                 ║");
        logger.info("╚══════════════════════════════════════════════════════════════╝");

        // Log a preview of the XML for debugging
        String preview = xml.length() > 500 ? xml.substring(0, 500) + "..." : xml;
        logger.debug("[Roster] Raw XML preview: {}", preview);

        int count = 0;

        // Try primary pattern: jid before name
        Matcher matcher = ROSTER_ITEM_PATTERN.matcher(xml);
        while (matcher.find()) {
            String puuid = matcher.group(1);
            String name = matcher.group(2);

            // Try to get game_name for more accurate display name
            String itemXml = matcher.group(0);
            String displayName = extractBestDisplayName(itemXml, name);

            if (puuid != null && !puuid.isEmpty() && displayName != null && !displayName.isEmpty()) {
                String oldName = puuidToName.put(puuid, displayName);
                if (oldName == null) {
                    logger.info("│ [+] NEW: {} → '{}'", abbreviatePuuid(puuid), displayName);
                } else if (!oldName.equals(displayName)) {
                    logger.info("│ [~] UPD: {} → '{}' (was '{}')", abbreviatePuuid(puuid), displayName, oldName);
                }
                count++;
            }
        }

        // Try alternative pattern: name before jid
        matcher = ROSTER_ITEM_PATTERN_ALT.matcher(xml);
        while (matcher.find()) {
            String name = matcher.group(1);
            String puuid = matcher.group(2);

            // Try to get game_name for more accurate display name
            String itemXml = matcher.group(0);
            String displayName = extractBestDisplayName(itemXml, name);

            if (puuid != null && !puuid.isEmpty() && displayName != null && !displayName.isEmpty()) {
                // Only add if not already present (primary pattern takes precedence)
                if (!puuidToName.containsKey(puuid)) {
                    puuidToName.put(puuid, displayName);
                    logger.info("│ [+] NEW (alt): {} → '{}'", abbreviatePuuid(puuid), displayName);
                    count++;
                }
            }
        }

        if (count > 0) {
            logger.info("├──────────────────────────────────────────────────────────────");
            logger.info("│ Roster now contains {} player(s)", puuidToName.size());
            logger.info("└──────────────────────────────────────────────────────────────");
        } else {
            // If no items found with primary patterns, try fallback extraction
            logger.debug("[Roster] Primary patterns found 0 items, trying fallback extraction");
            count = parseRosterItemsFallback(xml);
            if (count > 0) {
                logger.info("├──────────────────────────────────────────────────────────────");
                logger.info("│ Roster now contains {} player(s) (via fallback)", puuidToName.size());
                logger.info("└──────────────────────────────────────────────────────────────");
            } else {
                logger.warn("[Roster] No roster items found in IQ packet - check XML format");
                // Log first <item> if present for debugging
                int itemStart = xml.indexOf("<item");
                if (itemStart >= 0) {
                    int itemEnd = xml.indexOf(">", itemStart);
                    if (itemEnd > itemStart) {
                        String sampleItem = xml.substring(itemStart, Math.min(itemEnd + 1, itemStart + 200));
                        logger.warn("[Roster] Sample <item>: {}", sampleItem);
                    }
                }
            }
        }

        return count;
    }

    /**
     * Fallback parser for roster items using individual attribute extraction.
     * Used when primary regex patterns fail due to unexpected attribute ordering.
     */
    private int parseRosterItemsFallback(String xml) {
        int count = 0;

        // Simple pattern to find <item ...> or <item ... /> tags
        Pattern itemPattern = Pattern.compile("<item\\s+([^>]*?)/?>");
        Matcher itemMatcher = itemPattern.matcher(xml);

        while (itemMatcher.find()) {
            String attrs = itemMatcher.group(1);

            // Extract jid (PUUID@domain)
            Pattern jidPattern = Pattern.compile("jid=['\"]([^'\"@]+)@[^'\"]*['\"]");
            Matcher jidMatcher = jidPattern.matcher(attrs);
            String puuid = jidMatcher.find() ? jidMatcher.group(1) : null;

            if (puuid == null || puuid.isEmpty()) continue;

            // Extract game_name first (preferred)
            String displayName = null;
            Matcher gameNameMatcher = GAME_NAME_PATTERN.matcher(attrs);
            if (gameNameMatcher.find()) {
                displayName = gameNameMatcher.group(1);
            }

            // Fallback to name attribute
            if (displayName == null || displayName.isEmpty()) {
                Pattern namePattern = Pattern.compile("(?<!game_)name=['\"]([^'\"]+)['\"]");
                Matcher nameMatcher = namePattern.matcher(attrs);
                if (nameMatcher.find()) {
                    displayName = nameMatcher.group(1);
                }
            }

            if (displayName != null && !displayName.isEmpty() && !puuidToName.containsKey(puuid)) {
                puuidToName.put(puuid, displayName);
                logger.info("│ [+] NEW (fallback): {} → '{}'", abbreviatePuuid(puuid), displayName);
                count++;
            }
        }

        return count;
    }

    /**
     * Extract the best display name from a roster item.
     * Prefers game_name over the standard name attribute.
     *
     * @param itemXml The <item .../> XML fragment
     * @param fallbackName The name attribute value as fallback
     * @return The best display name to use
     */
    private String extractBestDisplayName(String itemXml, String fallbackName) {
        // Try to extract game_name (preferred - this is the in-game display name)
        Matcher gameNameMatcher = GAME_NAME_PATTERN.matcher(itemXml);
        if (gameNameMatcher.find()) {
            String gameName = gameNameMatcher.group(1);
            if (gameName != null && !gameName.isEmpty()) {
                // Optionally append game_tag if present
                Matcher gameTagMatcher = GAME_TAG_PATTERN.matcher(itemXml);
                if (gameTagMatcher.find()) {
                    String gameTag = gameTagMatcher.group(1);
                    if (gameTag != null && !gameTag.isEmpty()) {
                        // Return "Name#TAG" format for full identification
                        // But for TTS, just the name is cleaner
                        logger.debug("[Roster] Found game_name='{}' game_tag='{}'", gameName, gameTag);
                    }
                }
                return gameName;
            }
        }

        // Fallback to standard name attribute
        return fallbackName;
    }

    /**
     * Get the player name for a given PUUID.
     *
     * @param puuid The player's PUUID
     * @return The player's display name, or "Unknown" if not in roster
     */
    public String getPlayerName(String puuid) {
        if (puuid == null || puuid.isEmpty()) {
            return "Unknown";
        }
        return puuidToName.getOrDefault(puuid, "Unknown");
    }

    /**
     * Get the player name for a given PUUID, with a custom default.
     *
     * @param puuid The player's PUUID
     * @param defaultName The default name to return if not found
     * @return The player's display name, or defaultName if not in roster
     */
    public String getPlayerName(String puuid, String defaultName) {
        if (puuid == null || puuid.isEmpty()) {
            return defaultName;
        }
        return puuidToName.getOrDefault(puuid, defaultName);
    }

    /**
     * Check if a PUUID is in the roster.
     *
     * @param puuid The player's PUUID
     * @return true if the player is in the roster
     */
    public boolean hasPlayer(String puuid) {
        return puuid != null && puuidToName.containsKey(puuid);
    }

    /**
     * Manually add or update a player in the roster.
     *
     * @param puuid The player's PUUID
     * @param name The player's display name
     */
    public void putPlayer(String puuid, String name) {
        if (puuid != null && !puuid.isEmpty() && name != null && !name.isEmpty()) {
            puuidToName.put(puuid, name);
            logger.debug("[Roster] Manual add: {} → '{}'", abbreviatePuuid(puuid), name);
        }
    }

    /**
     * Get an unmodifiable view of the current roster.
     *
     * @return Read-only map of PUUID → Player Name
     */
    public Map<String, String> getRoster() {
        return Collections.unmodifiableMap(puuidToName);
    }

    /**
     * Get the number of players in the roster.
     *
     * @return Roster size
     */
    public int size() {
        return puuidToName.size();
    }

    /**
     * Clear the roster (e.g., on logout or game end).
     */
    public void clear() {
        int oldSize = puuidToName.size();
        puuidToName.clear();
        logger.info("[Roster] Cleared {} entries", oldSize);
    }

    /**
     * Abbreviate a PUUID for logging (show first 8 chars).
     */
    private String abbreviatePuuid(String puuid) {
        if (puuid == null) return "(null)";
        return puuid.length() > 8 ? puuid.substring(0, 8) + "..." : puuid;
    }

    /**
     * Format a TTS message with the sender's name.
     * This is the main method for Phase 3 "Who is Speaking?" feature.
     *
     * @param senderPuuid The sender's PUUID
     * @param messageBody The message content
     * @return Formatted TTS text like "Jett says: Hello"
     */
    public String formatTtsMessage(String senderPuuid, String messageBody) {
        String playerName = getPlayerName(senderPuuid);

        if ("Unknown".equals(playerName)) {
            // If player not in roster, just return the message body
            // This avoids awkward "Unknown says: Hello"
            logger.debug("[Roster] No name found for {}, using plain message", abbreviatePuuid(senderPuuid));
            return messageBody;
        }

        // Format: "Name says: message"
        String formatted = playerName + " says: " + messageBody;
        logger.debug("[Roster] TTS formatted: '{}' → '{}'", abbreviatePuuid(senderPuuid), formatted);
        return formatted;
    }
}
