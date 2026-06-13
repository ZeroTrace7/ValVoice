package com.someone.valvoicegui;

import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

public class OcrChatClientTestRunner {
    public static void main(String[] args) throws Exception {
        // Set log level to INFO
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.INFO);

        System.out.println("=== Starting Test 1 (Rapid Crash) ===");
        OcrChatClient client = new OcrChatClient();
        client.start();
        Thread.sleep(2000);

        long[] expectedBackoffs = {2000, 4000, 8000, 16000, 32000};

        for (int i = 0; i < 6; i++) {
            System.out.println("\n>>> Killing ValVoiceOCR.exe (Crash " + (i + 1) + ")");
            Runtime.getRuntime().exec("taskkill /F /IM ValVoiceOCR.exe").waitFor();
            
            if (i < 5) {
                long waitTime = expectedBackoffs[i] + 1000;
                System.out.println("Waiting " + waitTime + "ms for backoff to complete...");
                Thread.sleep(waitTime);
            } else {
                System.out.println("Waiting 2000ms to observe DEGRADED state...");
                Thread.sleep(2000);
            }
        }

        System.out.println("\n=== Starting Test 2 (Uptime Reset) ===");
        client.stop();
        Thread.sleep(1000);
        
        client = new OcrChatClient();
        client.start();
        System.out.println("Waiting 62 seconds for uptime reset threshold...");
        Thread.sleep(62000);
        
        System.out.println("\n>>> Killing ValVoiceOCR.exe after 60s uptime");
        Runtime.getRuntime().exec("taskkill /F /IM ValVoiceOCR.exe").waitFor();
        Thread.sleep(3000);
        
        client.stop();
        System.out.println("\n=== Tests Complete ===");
        System.exit(0);
    }
}
