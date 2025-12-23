package com.someone.valvoicebackend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.google.gson.*;

/**
 * VoiceGenerator - Coordinates TTS playback and Push-to-Talk automation
 *
 * MATCHES ValorantNarrator EXACTLY:
 * ✅ Key held down continuously from startup (when PTT enabled)
 * ✅ Release→Press to "refresh" key during speech
 * ✅ Uses ResponseProcess (isVoiceActive) to track voice state
 * ✅ Thread-safe coordination
 */
public class VoiceGenerator {
    private static final Logger logger = LoggerFactory.getLogger(VoiceGenerator.class);
    private static VoiceGenerator instance;

    // Configuration
    private static final String CONFIG_DIR = Paths.get(System.getenv("APPDATA"), "ValVoice").toString();
    private static final String CONFIG_FILE = "voice-config.json";
    private static final int DEFAULT_KEY_EVENT = KeyEvent.VK_V;

    // State - MATCHES ValorantNarrator exactly
    private final Robot robot;
    private final InbuiltVoiceSynthesizer synthesizer;
    private final ResponseProcess isVoiceActive = new ResponseProcess();
    private int keyEvent = DEFAULT_KEY_EVENT;
    private boolean isTeamKeyEnabled = true;
    private String currentVoice = "Microsoft Zira Desktop";
    private short currentVoiceRate = 50; // 0-100 UI scale

    private VoiceGenerator(InbuiltVoiceSynthesizer synthesizer) throws AWTException {
        this.synthesizer = synthesizer;
        this.robot = new Robot();
        loadConfig();

        logger.info("✓ VoiceGenerator initialized - keybind={}, PTT={}",
            KeyEvent.getKeyText(keyEvent), isTeamKeyEnabled ? "enabled" : "disabled");
    }

    public static synchronized void initialize(InbuiltVoiceSynthesizer synthesizer) throws AWTException {
        if (instance == null) {
            instance = new VoiceGenerator(synthesizer);
        }
    }

    public static VoiceGenerator getInstance() {
        if (instance == null) {
            throw new IllegalStateException("VoiceGenerator not initialized! Call initialize() first.");
        }
        return instance;
    }

    public static boolean isInitialized() {
        return instance != null;
    }

    /**
     * Speak text - MATCHES ValorantNarrator logic exactly:
     * 1. handleAudioLifecycle pattern
     * 2. Press key before speaking
     * 3. Speak via InbuiltVoiceSynthesizer (BLOCKING until done)
     * 4. Release key when speech completes
     */
    public void speakVoice(String text, String voice, short rate) {
        if (text == null || text.isEmpty()) {
            logger.warn("Cannot speak empty text");
            return;
        }

        if (!synthesizer.isReady()) {
            logger.warn("⚠ InbuiltVoiceSynthesizer not ready - cannot speak");
            return;
        }

        boolean isTextOverflowed = Math.min(text.length(), 71) != text.length();
        logger.info(String.format("(%s)Narrating message: '%s' with voice %s",
            (isTextOverflowed) ? "-" : "+", text, voice));

        // Run TTS in background thread to not block UI, but the TTS itself is blocking
        CompletableFuture.runAsync(() -> handleAudioLifecycle(() -> synthesizer.speakInbuiltVoice(voice, text, rate)));
    }

    /**
     * MATCHES ValorantNarrator's handleAudioLifecycle:
     * 1. Press key (if enabled)
     * 2. Run the TTS task (BLOCKING - waits for speech to complete)
     * 3. Release key when done
     *
     * The InbuiltVoiceSynthesizer.speakInbuiltVoice() is now BLOCKING,
     * so the key will be held for the entire duration of the speech.
     */
    private void handleAudioLifecycle(Runnable ttsTask) {
        // Reset voice state
        isVoiceActive.reset();

        try {
            if (isTeamKeyEnabled) {
                pressKey();
                // Delay to ensure key press is registered before audio starts
                // 150ms is sufficient for Valorant to detect the key press
                Thread.sleep(150);
            }

            if (ttsTask != null) {
                // This is now BLOCKING - it waits until speech completes
                ttsTask.run();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("TTS interrupted");
        } catch (Exception e) {
            logger.error("TTS error: {}", e.getMessage());
        } finally {
            // Always release key and mark as finished
            if (isTeamKeyEnabled) {
                // Delay to ensure last audio is transmitted before key release
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                releaseKey();
            }
            try {
                isVoiceActive.setFinished();
            } catch (Exception e) {
                // Already finished - ignore
            }
        }
    }

    private void pressKey() {
        logger.info("Pressing key: {}", KeyEvent.getKeyText(keyEvent));
        robot.keyPress(keyEvent);
    }

    private void releaseKey() {
        logger.info("Releasing key: {}", KeyEvent.getKeyText(keyEvent));
        robot.keyRelease(keyEvent);
    }

    public void speak(String text) {
        speakVoice(text, currentVoice, currentVoiceRate);
    }

    public synchronized void setKeybind(int keyCode) {
        this.keyEvent = keyCode;
        logger.info("✓ Keybind changed to: {}", KeyEvent.getKeyText(keyEvent));
        saveConfig();
    }

    public String getCurrentKeybind() {
        return KeyEvent.getKeyText(keyEvent);
    }

    public void setPushToTalkEnabled(boolean enabled) {
        this.isTeamKeyEnabled = enabled;
        logger.info("✓ Push-to-Talk {} - keybind: {}",
            enabled ? "enabled" : "disabled", KeyEvent.getKeyText(keyEvent));
        saveConfig();
    }

    public boolean isPushToTalkEnabled() {
        return isTeamKeyEnabled;
    }

    public void setCurrentVoice(String voice) {
        this.currentVoice = voice;
        saveConfig();
    }

    public String getCurrentVoice() {
        return currentVoice;
    }

    public void setCurrentVoiceRate(short rate) {
        this.currentVoiceRate = rate;
    }

    public List<String> getAvailableVoices() {
        return synthesizer.getAvailableVoices();
    }

    public boolean isBusy() {
        return isVoiceActive.isRunning();
    }

    private void loadConfig() {
        try {
            Path configPath = Paths.get(CONFIG_DIR, CONFIG_FILE);
            if (Files.exists(configPath)) {
                String json = Files.readString(configPath);
                JsonObject config = JsonParser.parseString(json).getAsJsonObject();

                if (config.has("keyEvent")) {
                    keyEvent = config.get("keyEvent").getAsInt();
                }
                // ValorantNarrator uses "isTeamKeyDisabled" but stores the enabled state
                if (config.has("isTeamKeyDisabled")) {
                    isTeamKeyEnabled = config.get("isTeamKeyDisabled").getAsBoolean();
                }
                if (config.has("currentVoice")) {
                    currentVoice = config.get("currentVoice").getAsString();
                }
                if (config.has("currentVoiceRate")) {
                    currentVoiceRate = config.get("currentVoiceRate").getAsShort();
                }

                logger.info("Loaded config: keybind={}, PTT={}, voice={}",
                    KeyEvent.getKeyText(keyEvent), isTeamKeyEnabled, currentVoice);
            } else {
                logger.info("No config found, using defaults");
                saveConfig();
            }
        } catch (Exception e) {
            logger.error("Failed to load config", e);
        }
    }

    private void saveConfig() {
        try {
            Files.createDirectories(Paths.get(CONFIG_DIR));

            JsonObject config = new JsonObject();
            config.addProperty("keyEvent", keyEvent);
            // ValorantNarrator uses "isTeamKeyDisabled" naming
            config.addProperty("isTeamKeyDisabled", isTeamKeyEnabled);
            config.addProperty("currentVoice", currentVoice);
            config.addProperty("currentVoiceRate", currentVoiceRate);

            Path configPath = Paths.get(CONFIG_DIR, CONFIG_FILE);
            Files.writeString(configPath, config.toString());
            logger.debug("Config saved to {}", configPath);
        } catch (Exception e) {
            logger.error("Failed to save config", e);
        }
    }
}


