package com.someone.valvoicebackend;

import java.time.Instant;

/**
 * Manages message quotas for users (basic and premium tiers).
 * Basic users: time-limited message quota
 * Premium users: potentially unlimited or larger quota with expiry
 */
public class MessageQuota {
    private final int limit;
    private int remaining;
    private final long refreshTimeEpochMilli;
    private final boolean isPremium;

    private MessageQuota(int limit, int remaining, long refreshTimeEpochMilli, boolean isPremium) {
        this.limit = limit;
        this.remaining = remaining;
        this.refreshTimeEpochMilli = refreshTimeEpochMilli;
        this.isPremium = isPremium;
    }

    /**
     * Create a basic user quota with specified limit
     * @param limit maximum number of messages
     * @return MessageQuota instance for basic user
     */
    public static MessageQuota createBasic(int limit) {
        long refreshTime = Instant.now().plusSeconds(3600).toEpochMilli(); // 1 hour from now
        return new MessageQuota(limit, limit, refreshTime, false);
    }

    /**
     * Create a premium user quota (unlimited or very high limit)
     * @param expiryTimeEpochMilli expiry timestamp in milliseconds
     * @return MessageQuota instance for premium user
     */
    public static MessageQuota createPremium(long expiryTimeEpochMilli) {
        return new MessageQuota(Integer.MAX_VALUE, Integer.MAX_VALUE, expiryTimeEpochMilli, true);
    }

    /**
     * Check if there are messages remaining in the quota
     * @return true if messages remain, false otherwise
     */
    public boolean hasMessagesRemaining() {
        if (isPremium) return true; // Premium always has messages
        if (System.currentTimeMillis() >= refreshTimeEpochMilli) {
            // Quota has refreshed
            remaining = limit;
            return true;
        }
        return remaining > 0;
    }

    /**
     * Decrement the quota by one message
     */
    public synchronized void decrementQuota() {
        if (!isPremium && remaining > 0) {
            remaining--;
        }
    }

    /**
     * Get the refresh time in milliseconds since epoch
     * @return refresh timestamp
     */
    public long getRefreshTime() {
        return refreshTimeEpochMilli;
    }

    /**
     * Get remaining messages count
     * @return number of messages remaining
     */
    public int getRemaining() {
        return remaining;
    }

    /**
     * Get remaining quota (alias for getRemaining)
     * @return number of messages remaining
     */
    public int remainingQuota() {
        return getRemaining();
    }

    /**
     * Get the quota limit
     * @return quota limit
     */
    public int getLimit() {
        return limit;
    }

    /**
     * Check if this is a premium quota
     * @return true if premium, false otherwise
     */
    public boolean isPremium() {
        return isPremium;
    }
}

