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
     * @return EnumSet containing parsed sources, default (SELF+PARTY+TEAM) if null/blank
     */
    public static EnumSet<Source> fromString(String value) {
        // VN-parity: Return default for null/blank (fresh install behavior)
        if (value == null || value.isBlank()) {
            return getDefault();
        }

        EnumSet<Source> result = EnumSet.noneOf(Source.class);

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

        // VN-parity: If parsing resulted in empty set, return default
        if (result.isEmpty()) {
            return getDefault();
        }

        // Phase 0 (OCR migration): Strip SELF from any persisted config.
        // SELF is kept as a parseable enum value for backward compatibility, but OCR mode
        // cannot determine who sent a message — SELF filtering is meaningless.
        // Old configs like "SELF+PARTY+TEAM" become "PARTY+TEAM" transparently.
        if (result.contains(SELF)) {
            result.remove(SELF);
            // If stripping SELF left the set empty (e.g. config was "SELF" only), apply default
            if (result.isEmpty()) {
                result = getDefault();
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
     *
     * Phase 0 (OCR migration): Default changed from SELF+PARTY+TEAM to PARTY+TEAM.
     * OCR reads all visible players — SELF has no meaning without player identity context.
     * Existing users with persisted SELF+PARTY+TEAM config are automatically migrated
     * to PARTY+TEAM by the fromString() SELF-stripping logic.
     *
     * @return Default EnumSet (PARTY, TEAM enabled; SELF and ALL disabled)
     */
    public static EnumSet<Source> getDefault() {
        return EnumSet.of(PARTY, TEAM);
    }
}
