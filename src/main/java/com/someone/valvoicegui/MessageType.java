package com.someone.valvoicegui;

/**
 * Message types for pre-startup Swing dialogs.
 * Maps to JOptionPane message type constants.
 */
public enum MessageType {
    ERROR_MESSAGE(0),
    INFORMATION_MESSAGE(1),
    WARNING_MESSAGE(2),
    QUESTION_MESSAGE(3),
    PLAIN_MESSAGE(-1);

    private final int value;

    MessageType(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
