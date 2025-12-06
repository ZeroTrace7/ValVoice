package com.someone.valvoicebackend;

public enum MessageType {
    ERROR(0),
    INFO(1),
    WARNING(2);

    private final int value;

    MessageType(int value) {
        this.value = value;
    }

    public static MessageType fromInt(int value) {
        for (MessageType type : MessageType.values()) {
            if (type.value == value) {
                return type;
            }
        }
        return ERROR;
    }
}
