package com.someone.valvoice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.*;

/**
 * VoiceGenerator - Coordinates TTS playback and Push-to-Talk automation
 *
 * MATCHES ValorantNarrator EXACTLY:
 * ✅ Key held down continuously from startup
 * ✅ Release→Press to "refresh" key during speech
 * ✅ Speaking queue (prevents overlapping)
 * ✅ Thread-safe coordination
 */
public class VoiceGenerator {
    private static final Logger logger = LoggerFactory.getLogger(VoiceGenerator.class);
    private static VoiceGenerator instance;

    // Configuration
    private static final String CONFIG_DIR = Paths.get(System.getenv("APPDATA"), "ValVoice").toString();
    private static final String CONFIG_FILE = "voice-config.json";
    private static final int DEFAULT_KEY_EVENT = KeyEvent.VK_V;

    // State
    private final Robot robot;
    private final InbuiltVoiceSynthesizer synthesizer;
    private final AtomicBoolean isSpeaking = new AtomicBoolean(false);
    private int keyEvent = DEFAULT_KEY_EVENT;
    private boolean isPushToTalkEnabled = true;
    private String currentVoice = "Microsoft Zira Desktop";
    private short currentVoiceRate = 50; // 0-100 UI scale

    private VoiceGenerator(InbuiltVoiceSynthesizer synthesizer) throws AWTException {
        this.synthesizer = synthesizer;
        this.robot = new Robot();
        loadConfig();

        // CRITICAL: Press and HOLD the keybind from startup (ValorantNarrator behavior)
        // The key stays pressed until app closes or PTT is disabled
        if (isPushToTalkEnabled) {
            robot.keyPress(keyEvent);
            logger.info("✓ VoiceGenerator initialized - keybind {} HELD DOWN", KeyEvent.getKeyText(keyEvent));
        } else {
            logger.info("✓ VoiceGenerator initialized - Push-to-Talk disabled");
        }

        // Register shutdown hook to release key when app closes
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (isPushToTalkEnabled) {
                robot.keyRelease(keyEvent);
                logger.info("Released keybind {} on shutdown", KeyEvent.getKeyText(keyEvent));
            }
        }, "VoiceGenerator-shutdown"));
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
     * 1. Queue system (wait if already speaking)
     * 2. Release→Press key to "refresh" state
     * 3. Speak via InbuiltVoiceSynthesizer
     * 4. Mark as finished
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

        // Wait for previous speech to finish (queue system)
        while (!isSpeaking.compareAndSet(false, true)) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        try {
            logger.debug("Using inbuilt voice: {}", voice);

            // CRITICAL: ValorantNarrator's exact logic for inbuilt voices
            // Release→Press to "refresh" the key state before speaking
            if (isPushToTalkEnabled) {
                robot.keyRelease(keyEvent);
                robot.keyPress(keyEvent);
                logger.debug("Refreshed keybind: {} (release→press)", KeyEvent.getKeyText(keyEvent));
            }

            // Speak the text
            synthesizer.speakInbuiltVoice(voice, text, rate);

        } catch (Exception e) {
            logger.error("Error during speech", e);
        } finally {
            // Mark speaking as finished
            isSpeaking.set(false);
        }
    }

    public void speak(String text) {
        speakVoice(text, currentVoice, currentVoiceRate);
    }

    public synchronized void setKeybind(int keyCode) {
        int oldKeyEvent = this.keyEvent;

        // Release old key if PTT is enabled
        if (isPushToTalkEnabled && oldKeyEvent != keyCode) {
            robot.keyRelease(oldKeyEvent);
            logger.debug("Released old keybind: {}", KeyEvent.getKeyText(oldKeyEvent));
        }

        this.keyEvent = keyCode;

        // Press new key if PTT is enabled
        if (isPushToTalkEnabled) {
            robot.keyPress(keyEvent);
            logger.info("✓ Keybind changed to: {} (now held down)", KeyEvent.getKeyText(keyEvent));
        } else {
            logger.info("✓ Keybind changed to: {}", KeyEvent.getKeyText(keyEvent));
        }

        saveConfig();
    }

    public String getCurrentKeybind() {
        return KeyEvent.getKeyText(keyEvent);
    }

    public void setPushToTalkEnabled(boolean enabled) {
        boolean wasEnabled = this.isPushToTalkEnabled;
        this.isPushToTalkEnabled = enabled;

        // Handle key state change
        if (enabled && !wasEnabled) {
            // Enabling: Press and hold key
            robot.keyPress(keyEvent);
            logger.info("✓ Push-to-Talk enabled - keybind {} pressed", KeyEvent.getKeyText(keyEvent));
        } else if (!enabled && wasEnabled) {
            // Disabling: Release key
            robot.keyRelease(keyEvent);
            logger.info("✓ Push-to-Talk disabled - keybind {} released", KeyEvent.getKeyText(keyEvent));
        }

        saveConfig();
    }

    public boolean isPushToTalkEnabled() {
        return isPushToTalkEnabled;
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
        return isSpeaking.get();
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
                if (config.has("pushToTalkEnabled")) {
                    isPushToTalkEnabled = config.get("pushToTalkEnabled").getAsBoolean();
                }
                if (config.has("currentVoice")) {
                    currentVoice = config.get("currentVoice").getAsString();
                }
                if (config.has("currentVoiceRate")) {
                    currentVoiceRate = config.get("currentVoiceRate").getAsShort();
                }

                logger.info("Loaded config: keybind={}, PTT={}, voice={}",
                    KeyEvent.getKeyText(keyEvent), isPushToTalkEnabled, currentVoice);
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
            config.addProperty("pushToTalkEnabled", isPushToTalkEnabled);
            config.addProperty("currentVoice", currentVoice);
            config.addProperty("currentVoiceRate", currentVoiceRate);

            Path configPath = Paths.get(CONFIG_DIR, CONFIG_FILE);
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Files.writeString(configPath, gson.toJson(config));

            logger.debug("Saved config to: {}", configPath);
        } catch (Exception e) {
            logger.error("Failed to save config", e);
        }
    }
}

