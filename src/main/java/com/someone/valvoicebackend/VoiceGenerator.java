package com.someone.valvoicebackend;

// DESIGN NOTE:
// ValVoice intentionally uses a single global Push-To-Talk key.
// Party vs Team voice routing is delegated to the Valorant client
// based on current context (lobby vs in-game), matching
// valorantnarrator's proven working behavior.

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.*;

/**
 * Coordinates TTS playback and Push-to-Talk automation.
 * Handles key press/release timing for Valorant voice chat.
 */
public class VoiceGenerator {
    private static final Logger logger = LoggerFactory.getLogger(VoiceGenerator.class);
    private static VoiceGenerator instance;

    private static final String CONFIG_DIR = Paths.get(System.getenv("APPDATA"), "ValVoice").toString();
    private static final String CONFIG_FILE = "voice-config.json";
    private static final int DEFAULT_KEY = KeyEvent.VK_V;

    private final Robot robot;
    private final InbuiltVoiceSynthesizer synthesizer;
    // Single-threaded executor ensures strict FIFO ordering - no overlapping speech
    private final ExecutorService ttsExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tts-speaker");
        t.setDaemon(true);
        return t;
    });
    private int keyEvent = DEFAULT_KEY;
    private boolean pttEnabled = true;
    private String currentVoice = "Microsoft Zira Desktop";
    private short currentVoiceRate = 50;
    private volatile boolean isSpeaking = false;
    private int prerollDelayMs = 200;
    private int postrollDelayMs = 100;

    private VoiceGenerator(InbuiltVoiceSynthesizer synthesizer) throws AWTException {
        this.synthesizer = synthesizer;
        this.robot = new Robot();
        loadConfig();
        logger.info("VoiceGenerator initialized - keybind={}, PTT={}",
            KeyEvent.getKeyText(keyEvent), pttEnabled);
    }

    public static synchronized void initialize(InbuiltVoiceSynthesizer synthesizer) throws AWTException {
        if (instance == null) {
            instance = new VoiceGenerator(synthesizer);
        }
    }

    public static VoiceGenerator getInstance() {
        if (instance == null) {
            throw new IllegalStateException("VoiceGenerator not initialized");
        }
        return instance;
    }

    public static boolean isInitialized() {
        return instance != null;
    }

    /**
     * Speak text with PTT automation.
     * PTT key is pressed before TTS starts and released after TTS completes.
     * Since speakInbuiltVoice() is blocking, PTT is held exactly during audio playback.
     */
    public void speakVoice(String text, String voice, short rate) {
        if (text == null || text.isEmpty() || !synthesizer.isReady()) return;

        logger.debug("Narrating: '{}' (voice={})",
            text.length() > 50 ? text.substring(0, 47) + "..." : text, voice);

        // Submit to single-threaded executor for strict FIFO ordering
        ttsExecutor.submit(() -> {
            isSpeaking = true;
            try {
                if (pttEnabled) {
                    robot.keyPress(keyEvent);
                    logger.debug("PTT pressed");

                    // Pre-roll delay: wait for Valorant to activate voice transmission
                    logger.debug("Preroll sleep {} ms", prerollDelayMs);
                    Thread.sleep(prerollDelayMs);
                    logger.debug("Preroll sleep completed");
                }

                logger.debug("Starting audio playback");
                // This call blocks until speech completes - PTT stays held during playback
                synthesizer.speakInbuiltVoice(voice, text, rate);
                logger.debug("Audio playback finished");

                // Post-roll delay: ensure audio transmission completes before releasing PTT
                if (pttEnabled) {
                    Thread.sleep(postrollDelayMs);
                    logger.debug("Postroll delay {} ms completed", postrollDelayMs);
                }

            } catch (Exception e) {
                logger.error("TTS error: {}", e.getMessage());
            } finally {
                if (pttEnabled) {
                    robot.keyRelease(keyEvent);
                    logger.debug("PTT released");
                }
                isSpeaking = false;
            }
        });
    }

    public void speak(String text) {
        speakVoice(text, currentVoice, currentVoiceRate);
    }

    /**
     * Queue a message for TTS narration.
     * Uses stored voice and rate settings.
     *
     * PHASE A: Production Cleanup
     * This method allows backend (ChatDataHandler) to invoke TTS directly
     * without going through ValVoiceController, maintaining UI-agnostic backend.
     *
     * @param msg The message to narrate (uses msg.getContent())
     */
    public void queueNarration(Message msg) {
        if (msg == null || msg.getContent() == null || msg.getContent().isEmpty()) {
            logger.debug("queueNarration: null or empty message, skipping");
            return;
        }

        logger.info("🔊 TTS QUEUED: \"{}\" (voice: {}, rate: {}, PTT: {})",
            msg.getContent().length() > 50 ? msg.getContent().substring(0, 47) + "..." : msg.getContent(),
            currentVoice,
            currentVoiceRate,
            pttEnabled);

        speak(msg.getContent());
    }

    public void setKeybind(int keyCode) {
        this.keyEvent = keyCode;
        saveConfig();
        logger.debug("Keybind set to: {}", KeyEvent.getKeyText(keyEvent));
    }

    public String getCurrentKeybind() {
        return KeyEvent.getKeyText(keyEvent);
    }

    public void setPushToTalkEnabled(boolean enabled) {
        this.pttEnabled = enabled;
        saveConfig();
    }

    public boolean isPushToTalkEnabled() {
        return pttEnabled;
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
        saveConfig();
    }

    /**
     * Get the current voice rate (0-100 UI scale).
     */
    public short getCurrentVoiceRate() {
        return currentVoiceRate;
    }

    /**
     * Get the PTT key code (java.awt.event.KeyEvent constant).
     */
    public int getKeyCode() {
        return keyEvent;
    }

    public List<String> getAvailableVoices() {
        return synthesizer.getAvailableVoices();
    }

    public boolean isBusy() {
        return isSpeaking;
    }

    private void loadConfig() {
        try {
            Path configPath = Paths.get(CONFIG_DIR, CONFIG_FILE);
            if (Files.exists(configPath)) {
                JsonObject config = JsonParser.parseString(Files.readString(configPath)).getAsJsonObject();

                if (config.has("keyEvent")) keyEvent = config.get("keyEvent").getAsInt();
                if (config.has("isTeamKeyDisabled")) pttEnabled = config.get("isTeamKeyDisabled").getAsBoolean();
                if (config.has("currentVoice")) currentVoice = config.get("currentVoice").getAsString();
                if (config.has("currentVoiceRate")) currentVoiceRate = config.get("currentVoiceRate").getAsShort();
            }
        } catch (Exception e) {
            logger.debug("Could not load config: {}", e.getMessage());
        }
    }

    private void saveConfig() {
        try {
            Files.createDirectories(Paths.get(CONFIG_DIR));

            JsonObject config = new JsonObject();
            config.addProperty("keyEvent", keyEvent);
            config.addProperty("isTeamKeyDisabled", pttEnabled);
            config.addProperty("currentVoice", currentVoice);
            config.addProperty("currentVoiceRate", currentVoiceRate);

            Files.writeString(Paths.get(CONFIG_DIR, CONFIG_FILE), config.toString());
        } catch (Exception e) {
            logger.debug("Could not save config: {}", e.getMessage());
        }
    }
}


