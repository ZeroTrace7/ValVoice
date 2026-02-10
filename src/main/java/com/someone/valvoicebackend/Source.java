package com.someone.valvoicebackend;

import java.util.EnumSet;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * Source - Enum representing chat channels for narration filtering.
 *
 * VN-parity: Mirrors ValorantNarrator's Source.java exactly.
 *
 * Responsibilities:
 * - Parse config string → EnumSet<Source>
 * - Serialize EnumSet<Source> → config string
 * - Format: "SELF+PARTY+TEAM" (values joined by '+')
 *
 * Usage:
 * - Main.java loads "source" property and applies to Chat
 * - ValVoiceController persists on toggle changes
 */
public enum Source {
    SELF,
    PARTY,
    TEAM,
    ALL;

    /**
     * Parse a config string into EnumSet<Source>.
     * VN-parity: Handles "SELF+PARTY+TEAM" format.
     *
     * @param value Config string (e.g., "PARTY+TEAM" or "SELF+PARTY+TEAM+ALL")
     * @return EnumSet containing parsed sources, empty set if null/blank
     */
    public static EnumSet<Source> fromString(String value) {
        EnumSet<Source> result = EnumSet.noneOf(Source.class);

        if (value == null || value.isBlank()) {
            return result;
        }

        String[] parts = value.toUpperCase(Locale.ROOT).split("\\+");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;

            try {
                Source source = Source.valueOf(trimmed);
                result.add(source);
            } catch (IllegalArgumentException ignored) {
                // Unknown source token - skip (VN behavior: ignore unknown)
            }
        }

        return result;
    }

    /**
     * Serialize EnumSet<Source> to config string.
     * VN-parity: Produces "SELF+PARTY+TEAM" format.
     *
     * @param sources EnumSet of enabled sources
     * @return Config string (e.g., "PARTY+TEAM"), empty string if empty set
     */
    public static String toString(EnumSet<Source> sources) {
        if (sources == null || sources.isEmpty()) {
            return "";
        }

        StringJoiner joiner = new StringJoiner("+");
        // EnumSet iterates in enum declaration order (SELF, PARTY, TEAM, ALL)
        for (Source source : sources) {
            joiner.add(source.name());
        }

        return joiner.toString();
    }

    /**
     * Get default source configuration.
     * VN-parity: Default is SELF+PARTY+TEAM+ALL (fail-open behavior).
     *
     * This ensures if config is missing or invalid, all channels are enabled
     * rather than silently filtering messages. User can always restrict later.
     *
     * @return Default EnumSet (all channels enabled)
     */
    public static EnumSet<Source> getDefault() {
        return EnumSet.of(SELF, PARTY, TEAM, ALL);
    }
}
