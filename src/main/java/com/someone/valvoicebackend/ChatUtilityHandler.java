package com.someone.valvoicebackend;

/**
 * Utility class for chat-related operations.
 * Handles player name resolution.
 */
public class ChatUtilityHandler {

    /**
     * Gets the display name for a player ID.
     * Uses cached data when available.
     */
    public static String getPlayerName(final String playerID) {
        if (playerID == null || playerID.isEmpty()) {
            return "Unknown";
        }
        return Chat.getInstance().getDisplayName(playerID).orElse(playerID);
    }
}
