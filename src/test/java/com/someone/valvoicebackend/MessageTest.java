package com.someone.valvoicebackend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Message.java constructor security checks.
 *
 * Validates that the constructor properly rejects invalid XML input:
 * 1. Null input throws IllegalArgumentException
 * 2. Oversized XML payloads are safely handled (content set to null)
 * 3. Valid XML input is accepted without error
 *
 * These tests ensure the constructor's validation logic cannot be
 * accidentally removed in future updates.
 */
class MessageTest {

    /** Mirrors Message.MAX_XML_LENGTH (private static final int = 32 * 1024) */
    private static final int MAX_XML_LENGTH = 32 * 1024;

    // ═══════════════════════════════════════════════════════════════
    // TEST 1: Null Input
    // ═══════════════════════════════════════════════════════════════

    @Test
    void constructorShouldThrowWhenXmlIsNull() {
        IllegalArgumentException exception =
            assertThrows(IllegalArgumentException.class, () -> {
                new Message(null);
            });

        assertTrue(exception.getMessage().contains("xml cannot be null"));
    }

    // ═══════════════════════════════════════════════════════════════
    // TEST 2: Oversized Input
    // ═══════════════════════════════════════════════════════════════

    @Test
    void constructorShouldRejectOversizedXml() {
        // Build XML payload that exceeds MAX_XML_LENGTH
        String oversizedXml = "x".repeat(MAX_XML_LENGTH + 1);

        // Constructor does NOT throw — it sets safe defaults and returns
        Message msg = assertDoesNotThrow(() -> new Message(oversizedXml));

        // Content must be null for oversized payloads (security safe default)
        assertNull(msg.getContent(),
            "Oversized XML should result in null content");
    }

    @Test
    void constructorShouldAcceptXmlAtExactLimit() {
        // XML at exactly MAX_XML_LENGTH should be processed, not rejected
        String xmlAtLimit = "<message>" + "a".repeat(MAX_XML_LENGTH - 19) + "</message>";
        assertEquals(MAX_XML_LENGTH, xmlAtLimit.length());

        // Should not throw — input is within the limit
        assertDoesNotThrow(() -> new Message(xmlAtLimit));
    }

    // ═══════════════════════════════════════════════════════════════
    // TEST 3: Valid Input
    // ═══════════════════════════════════════════════════════════════

    @Test
    void constructorShouldAcceptValidXml() {
        String validXml = "<message from='test@riotgames.com'><body>hello</body></message>";

        assertDoesNotThrow(() -> new Message(validXml));
    }

    @Test
    void constructorShouldAcceptEmptyXml() {
        // Empty string is valid (not null) — constructor should not throw
        assertDoesNotThrow(() -> new Message(""));
    }
}

