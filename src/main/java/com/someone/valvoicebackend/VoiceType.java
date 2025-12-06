package com.someone.valvoicebackend;

public enum VoiceType {
    PREMIUM, STANDARD, INBUILT;

    public static VoiceType fromString(String voiceType) {
        if (voiceType.endsWith("VALORANT")) {
            return PREMIUM;
        } else if (voiceType.endsWith("INBUILT")) {
            return INBUILT;
        } else {
            return STANDARD;
        }
    }
}