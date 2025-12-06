package com.someone.valvoicebackend;

/**
 * Exception thrown when message quota is exhausted
 */
public class QuotaExhaustedException extends Exception {
    private final long refreshTimeEpochMilli;

    /**
     * Create exception with string message
     * @param message error message
     */
    public QuotaExhaustedException(String message) {
        super(message);
        this.refreshTimeEpochMilli = System.currentTimeMillis();
    }

    /**
     * Create exception with refresh time
     * @param refreshTimeEpochMilli time when quota refreshes (milliseconds since epoch)
     */
    public QuotaExhaustedException(long refreshTimeEpochMilli) {
        super("Message quota exhausted. Refreshes at: " + new java.util.Date(refreshTimeEpochMilli));
        this.refreshTimeEpochMilli = refreshTimeEpochMilli;
    }

    /**
     * Get the refresh time
     * @return refresh timestamp in milliseconds
     */
    public long getRefreshTime() {
        return refreshTimeEpochMilli;
    }

    /**
     * Get seconds until refresh
     * @return seconds remaining until quota refresh
     */
    public long getSecondsUntilRefresh() {
        long now = System.currentTimeMillis();
        return Math.max(0, (refreshTimeEpochMilli - now) / 1000);
    }
}

