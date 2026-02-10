package com.someone.valvoicebackend;

// DESIGN NOTE:
// ValVoice intentionally uses a single global Push-To-Talk key.
// Party vs Team voice routing is delegated to the Valorant client
// based on current context (lobby vs in-game), matching
// valorantnarrator's proven working behavior.

import com.someone.valvoicegui.Main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * Coordinates TTS playback and Push-to-Talk automation.
 * Handles key press/release timing for Valorant voice chat.
 *
 * VN-parity: Uses Main.getProperties() for Java Properties-based persistence.
 */
public class VoiceGenerator {
    private static final Logger logger = LoggerFactory.getLogger(VoiceGenerator.class);
    private static VoiceGenerator instance;

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

    /**
     * VN-parity: Load config from Main.getProperties() (Java Properties).
     * Called once at startup. Errors fall back to defaults.
     */
    private void loadConfig() {
        try {
            Properties props = Main.getProperties();

            String voiceStr = props.getProperty(Main.PROP_VOICE);
            if (voiceStr != null && !voiceStr.isBlank()) {
                currentVoice = voiceStr;
            }

            String speedStr = props.getProperty(Main.PROP_SPEED);
            if (speedStr != null) {
                try {
                    currentVoiceRate = Short.parseShort(speedStr);
                } catch (NumberFormatException ignored) {}
            }

            String keyStr = props.getProperty(Main.PROP_PTT_KEY);
            if (keyStr != null) {
                try {
                    keyEvent = Integer.parseInt(keyStr);
                } catch (NumberFormatException ignored) {}
            }

            String pttStr = props.getProperty(Main.PROP_PTT_ENABLED);
            if (pttStr != null) {
                pttEnabled = Boolean.parseBoolean(pttStr);
            }

            logger.info("[VoiceGenerator] Config loaded: voice={}, rate={}, pttKey={}, pttEnabled={}",
                currentVoice, currentVoiceRate, KeyEvent.getKeyText(keyEvent), pttEnabled);
        } catch (Exception e) {
            logger.warn("[VoiceGenerator] Could not load config (using defaults): {}", e.getMessage());
        }
    }

    /**
     * VN-parity: Save config to %APPDATA%/ValVoice/config.properties.
     * Called immediately on any setting change. Non-blocking (async).
     * Saves: voice, speed, pttKey, pttEnabled, source (channel filters)
     */
    public void saveConfig() {
        // Run async to avoid blocking JavaFX thread
        new Thread(() -> {
            try {
                Properties props = Main.getProperties();
                props.setProperty(Main.PROP_VOICE, currentVoice);
                props.setProperty(Main.PROP_SPEED, String.valueOf(currentVoiceRate));
                props.setProperty(Main.PROP_PTT_KEY, String.valueOf(keyEvent));
                props.setProperty(Main.PROP_PTT_ENABLED, String.valueOf(pttEnabled));

                // VN-parity: Save source (channel filters) from Chat runtime model
                java.util.EnumSet<Source> sources = Chat.getInstance().getSources();
                props.setProperty(Main.PROP_SOURCE, Source.toString(sources));

                Files.createDirectories(Paths.get(Main.CONFIG_DIR));
                try (OutputStream out = new FileOutputStream(Main.getConfigPath())) {
                    props.store(out, "ValVoice User Configuration");
                }
                logger.debug("[VoiceGenerator] Config saved to: {}", Main.getConfigPath());
            } catch (Exception e) {
                logger.warn("[VoiceGenerator] Could not save config: {}", e.getMessage());
            }
        }, "config-saver").start();
    }
}

