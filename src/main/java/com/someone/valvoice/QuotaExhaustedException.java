package com.someone.valvoice;

/**
 * Exception thrown when API quota has been exhausted.
 * This indicates that the application has reached its usage limit
 * and must wait for quota refresh.
 */
public class QuotaExhaustedException extends Exception {
    public QuotaExhaustedException(String message) {
        super(message);
    }

    public QuotaExhaustedException(String message, Throwable cause) {
        super(message, cause);
    }
}

