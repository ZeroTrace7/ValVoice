package com.someone.valvoicebackend;

/**
 * Record representing a Valorant player account with display name, unique subject ID,
 * game name, and tag line information.
 */
public record PlayerAccount(String DisplayName, String Subject, String GameName, String TagLine) {

    /**
     * Creates a formatted game name with tag line (e.g., "PlayerName#TAG")
     * @return The formatted game name with tag
     */
    public String getFullGameName() {
        return GameName + "#" + TagLine;
    }

    /**
     * Checks if this account matches the given subject ID
     * @param subjectId The subject ID to check
     * @return true if this account's subject matches
     */
    public boolean hasSubject(String subjectId) {
        return Subject != null && Subject.equals(subjectId);
    }

    @Override
    public String toString() {
        return DisplayName + " (" + getFullGameName() + ")";
    }
}