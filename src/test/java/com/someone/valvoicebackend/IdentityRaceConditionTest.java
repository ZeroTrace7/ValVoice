package com.someone.valvoicebackend;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class IdentityRaceConditionTest {

    @BeforeEach
    public void setup() {
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
            e.printStackTrace();
        }
    }

    @Test
    public void testScenarioA_PollerFirst() {
        ChatDataHandler handler = ChatDataHandler.getInstance();
        AtomicInteger listenerCalls = new AtomicInteger(0);
        handler.registerSelfIdListener(id -> listenerCalls.incrementAndGet(), false);

        // Poller sets it first
        boolean pollerNewlySet = handler.setSelfId("PUUID-1234");
        assertTrue(pollerNewlySet, "Poller should report newly set");
        assertEquals(1, listenerCalls.get(), "Listener should be called once");

        // MITM sets it second
        boolean mitmNewlySet = handler.setSelfId("PUUID-1234");
        assertFalse(mitmNewlySet, "MITM should report NOT newly set");
        assertEquals(1, listenerCalls.get(), "Listener should NOT be called again");
    }

    @Test
    public void testScenarioB_MitmFirst() {
        ChatDataHandler handler = ChatDataHandler.getInstance();
        AtomicInteger listenerCalls = new AtomicInteger(0);
        handler.registerSelfIdListener(id -> listenerCalls.incrementAndGet(), false);

        // MITM sets it first
        boolean mitmNewlySet = handler.setSelfId("PUUID-9999");
        assertTrue(mitmNewlySet, "MITM should report newly set");
        assertEquals(1, listenerCalls.get(), "Listener should be called once");

        // Poller sets it second
        boolean pollerNewlySet = handler.setSelfId("PUUID-9999");
        assertFalse(pollerNewlySet, "Poller should report NOT newly set");
        assertEquals(1, listenerCalls.get(), "Listener should NOT be called again");
    }
}
