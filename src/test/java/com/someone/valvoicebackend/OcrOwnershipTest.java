package com.someone.valvoicebackend;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2.2: Tests for OCR self-message ownership evaluation and display name storage.
 */
public class OcrOwnershipTest {

    @BeforeEach
    public void resetSingleton() {
        // Reset internal state on the existing singleton (Java 23 cannot replace static final)
        ChatDataHandler handler = ChatDataHandler.getInstance();
        try {
            java.lang.reflect.Field selfIdField = ChatDataHandler.class.getDeclaredField("selfId");
            selfIdField.setAccessible(true);
            selfIdField.set(handler, null);

            java.lang.reflect.Field selfDisplayNameField = ChatDataHandler.class.getDeclaredField("selfDisplayName");
            selfDisplayNameField.setAccessible(true);
            selfDisplayNameField.set(handler, null);

            handler.clearListeners();
        } catch (Exception e) {
            fail("Failed to reset ChatDataHandler state: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 1. ChatDataHandler.selfDisplayName storage
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    public void testSetSelfDisplayName_newlySet() {
        ChatDataHandler handler = ChatDataHandler.getInstance();
        assertNull(handler.getSelfDisplayName());

        boolean result = handler.setSelfDisplayName("Jett");
        assertTrue(result, "Should return true when newly set");
        assertEquals("Jett", handler.getSelfDisplayName());
    }

    @Test
    public void testSetSelfDisplayName_idempotent() {
        ChatDataHandler handler = ChatDataHandler.getInstance();
        handler.setSelfDisplayName("Jett");

        boolean result = handler.setSelfDisplayName("Jett");
        assertFalse(result, "Should return false when same value");
        assertEquals("Jett", handler.getSelfDisplayName());
    }

    @Test
    public void testSetSelfDisplayName_changesValue() {
        ChatDataHandler handler = ChatDataHandler.getInstance();
        handler.setSelfDisplayName("Jett");

        boolean result = handler.setSelfDisplayName("Phoenix");
        assertTrue(result, "Should return true when value changes");
        assertEquals("Phoenix", handler.getSelfDisplayName());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. OcrMessage.ownMessage field
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    public void testOcrMessage_ownMessageTrue() {
        OcrMessage msg = new OcrMessage("TEAM", "Jett", "hello", 12345L, true);
        assertTrue(msg.ownMessage());
        assertEquals("TEAM", msg.channel());
        assertEquals("Jett", msg.name());
        assertEquals("hello", msg.body());
        assertEquals(12345L, msg.timestamp());
    }

    @Test
    public void testOcrMessage_ownMessageFalse() {
        OcrMessage msg = new OcrMessage("PARTY", "Phoenix", "rush B", 99999L, false);
        assertFalse(msg.ownMessage());
        assertEquals("PARTY", msg.channel());
        assertEquals("Phoenix", msg.name());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. Ownership evaluation logic (simulates OcrChatClient pattern)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Replicates the exact ownership evaluation from OcrChatClient.handleLine()
     * to verify correctness without launching the sidecar process.
     */
    private boolean evaluateOwnership(String selfName, String senderName, String direction) {
        boolean own = selfName != null && selfName.equalsIgnoreCase(senderName);
        if ("TO".equalsIgnoreCase(direction)) {
            own = true;
        } else if ("FROM".equalsIgnoreCase(direction)) {
            own = false;
        }
        return own;
    }

    @Test
    public void testOwnership_exactMatch() {
        assertTrue(evaluateOwnership("Jett", "Jett", null));
    }

    @Test
    public void testOwnership_caseInsensitiveMatch() {
        assertTrue(evaluateOwnership("Jett", "jett", null));
        assertTrue(evaluateOwnership("JETT", "jett", null));
    }

    @Test
    public void testOwnership_noMatch() {
        assertFalse(evaluateOwnership("Jett", "Phoenix", null));
    }

    @Test
    public void testOwnership_selfNameNull() {
        assertFalse(evaluateOwnership(null, "Jett", null));
    }

    @Test
    public void testOwnership_directionTO_overridesNoMatch() {
        // Direction "TO" means the player sent a whisper TO someone — own = true
        assertTrue(evaluateOwnership("Jett", "Phoenix", "TO"));
    }

    @Test
    public void testOwnership_directionFROM_overridesMatch() {
        // Direction "FROM" means someone sent a whisper FROM them — own = false
        assertFalse(evaluateOwnership("Jett", "Jett", "FROM"));
    }

    @Test
    public void testOwnership_directionNull_fallsBackToNameMatch() {
        assertTrue(evaluateOwnership("Jett", "Jett", null));
        assertFalse(evaluateOwnership("Jett", "Phoenix", null));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. MITM identity still works (Phase 2.1 regression)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    public void testMitmIdentityStillWorks_pollerFirst() {
        ChatDataHandler handler = ChatDataHandler.getInstance();
        AtomicInteger listenerCalls = new AtomicInteger(0);
        handler.registerSelfIdListener(id -> listenerCalls.incrementAndGet(), false);

        // Poller sets PUUID
        boolean pollerSet = handler.setSelfId("PUUID-1234");
        assertTrue(pollerSet);
        assertEquals(1, listenerCalls.get());

        // MITM sets same PUUID — no-op
        boolean mitmSet = handler.setSelfId("PUUID-1234");
        assertFalse(mitmSet);
        assertEquals(1, listenerCalls.get());
    }

    @Test
    public void testMitmIdentityStillWorks_mitmFirst() {
        ChatDataHandler handler = ChatDataHandler.getInstance();
        AtomicInteger listenerCalls = new AtomicInteger(0);
        handler.registerSelfIdListener(id -> listenerCalls.incrementAndGet(), false);

        // MITM sets PUUID
        boolean mitmSet = handler.setSelfId("PUUID-5678");
        assertTrue(mitmSet);
        assertEquals(1, listenerCalls.get());

        // Poller sets same PUUID — no-op
        boolean pollerSet = handler.setSelfId("PUUID-5678");
        assertFalse(pollerSet);
        assertEquals(1, listenerCalls.get());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 5. Display name is independent of PUUID
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    public void testDisplayNameAndPuuidAreIndependent() {
        ChatDataHandler handler = ChatDataHandler.getInstance();

        handler.setSelfId("PUUID-ABC");
        handler.setSelfDisplayName("Jett");

        assertEquals("PUUID-ABC", handler.getSelfId());
        assertEquals("Jett", handler.getSelfDisplayName());

        // Changing one doesn't affect the other
        handler.setSelfDisplayName("Phoenix");
        assertEquals("PUUID-ABC", handler.getSelfId());
        assertEquals("Phoenix", handler.getSelfDisplayName());
    }
}
