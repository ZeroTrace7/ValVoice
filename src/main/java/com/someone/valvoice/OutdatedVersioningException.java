package com.someone.valvoice;

/**
 * Exception thrown when the application version is outdated
 * and needs to be updated to continue functioning.
 */
public class OutdatedVersioningException extends Exception {
    public OutdatedVersioningException(String message) {
        super(message);
    }

    public OutdatedVersioningException(String message, Throwable cause) {
        super(message, cause);
    }
}
