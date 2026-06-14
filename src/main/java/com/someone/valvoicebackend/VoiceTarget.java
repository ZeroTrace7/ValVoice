package com.someone.valvoicebackend;

/**
 * Voice channel target for PTT routing.
 *
 * Separate from {@link Source} (message filtering) by design.
 * Source controls WHICH messages get narrated.
 * VoiceTarget controls WHERE the TTS audio goes (which voice channel).
 * These are independent axes — never overloaded.
 */
public enum VoiceTarget {
    /** Party voice channel — used in menus, pregame, custom games, and unknown states */
    PARTY,
    /** Team voice channel — used during active matches (INGAME) */
    TEAM
}
