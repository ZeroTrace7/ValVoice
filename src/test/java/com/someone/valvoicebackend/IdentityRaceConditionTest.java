package com.someone.valvoicebackend;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class IdentityRaceConditionTest {

    @BeforeEach
    public void setup() {
        // Reset singleton for testing (hack using reflection since there is no reset method)
        try {
            java.lang.reflect.Constructor<ChatDataHandler> constructor = ChatDataHandler.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            ChatDataHandler newInstance = constructor.newInstance();
            java.lang.reflect.Field instance = ChatDataHandler.class.getDeclaredField("INSTANCE");
            instance.setAccessible(true);
            instance.set(null, newInstance);
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
