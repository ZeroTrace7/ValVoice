package com.someone.valvoicebackend;

public class OutdatedVersioningException extends Exception {
    public OutdatedVersioningException(String message) {
        super(message);
    }
}
