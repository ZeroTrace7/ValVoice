package com.someone.valvoicebackend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;

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
    private int keyEvent = DEFAULT_KEY;
    private boolean pttEnabled = true;
    private String currentVoice = "Microsoft Zira Desktop";
    private short currentVoiceRate = 50;
    private volatile boolean isSpeaking = false;

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
     */
    public void speakVoice(String text, String voice, short rate) {
        if (text == null || text.isEmpty() || !synthesizer.isReady()) return;

        logger.debug("Narrating: '{}' (voice={})",
            text.length() > 50 ? text.substring(0, 47) + "..." : text, voice);

        CompletableFuture.runAsync(() -> {
            isSpeaking = true;
            try {
                if (pttEnabled) {
                    robot.keyPress(keyEvent);
                    Thread.sleep(150); // Let Valorant detect key press
                }

                synthesizer.speakInbuiltVoice(voice, text, rate);

                if (pttEnabled) {
                    Thread.sleep(200); // Ensure audio transmits before release
                    robot.keyRelease(keyEvent);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                logger.error("TTS error: {}", e.getMessage());
            } finally {
                isSpeaking = false;
            }
        });
    }

    public void speak(String text) {
        speakVoice(text, currentVoice, currentVoiceRate);
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


