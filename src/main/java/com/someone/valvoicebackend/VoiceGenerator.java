package com.someone.valvoicebackend;

// DESIGN NOTE:
// ValVoice intentionally uses a single global Push-To-Talk key.
// Party vs Team voice routing is delegated to the Valorant client
// based on current context (lobby vs in-game), matching
// valorantnarrator's proven working behavior.

import com.google.gson.Gson;
import com.someone.valvoicebackend.config.ConfigManager;
import com.someone.valvoicebackend.config.ValVoiceConfig;
import com.someone.valvoicegui.Main;
import com.someone.valvoicegui.ValVoiceBackend;
import dev.mccue.jlayer.decoder.JavaLayerException;
import dev.mccue.jlayer.player.FactoryRegistry;
import dev.mccue.jlayer.player.advanced.AdvancedPlayer;
import dev.mccue.jlayer.player.advanced.PlaybackEvent;
import dev.mccue.jlayer.player.advanced.PlaybackListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.AWTException;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * Coordinates TTS playback and Push-to-Talk automation.
 * Handles key press/release timing for Valorant voice chat.
 *
 * VN-parity: Uses Main.getProperties() for Java Properties-based persistence.
 */
public class VoiceGenerator {
    private static final Logger logger = LoggerFactory.getLogger(VoiceGenerator.class);
    private static final Gson GSON = new Gson();
    private static VoiceGenerator instance;

    private static final int DEFAULT_KEY = KeyEvent.VK_V;
    private static final String XTTS_API_URL = "http://127.0.0.1:5005/speak";
    private static final Duration XTTS_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration XTTS_REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final String DEFAULT_LANGUAGE = "en";

    private final Robot robot;
    private final InbuiltVoiceSynthesizer synthesizer;
    private final HttpClient xttsHttpClient;
    private final AtomicBoolean pttPressed = new AtomicBoolean(false);
    private final AtomicInteger activePttKeyCode = new AtomicInteger(DEFAULT_KEY);
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

    private VoiceGenerator(InbuiltVoiceSynthesizer synthesizer) {
        this.synthesizer = synthesizer;
        this.robot = createRobot();
        this.xttsHttpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(XTTS_CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        loadConfig();
        // VoiceGenerator now owns the full PTT lifecycle for XTTS and fallback playback.
        this.synthesizer.setPttEnabled(false);
        logger.info("VoiceGenerator initialized - keybind={}, PTT={}",
            KeyEvent.getKeyText(keyEvent), pttEnabled);
    }

    public static synchronized void initialize(InbuiltVoiceSynthesizer synthesizer) {
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
     * XTTS is streamed directly from the HTTP response into JLayer with event-driven
     * PTT synchronized to playbackStarted()/playbackFinished().
     */
    public void speakVoice(String voice, String text, short rate) {
        if (text == null || text.isBlank()) return;

        String narrationText = text.trim();

        logger.debug("Narrating: '{}' (voice={})",
            narrationText.length() > 50 ? narrationText.substring(0, 47) + "..." : narrationText, voice);

        // Submit to single-threaded executor for strict FIFO ordering
        ttsExecutor.submit(() -> {
            isSpeaking = true;
            try {
                ValVoiceConfig config = ConfigManager.get();
                boolean xttsEnabled = config != null && config.xttsEnabled;
                boolean engineReady = ValVoiceBackend.getInstance().isEngineReady();
                boolean sapiFallbackEnabled = config == null || config.sapiFallbackEnabled;
                boolean useXtts = xttsEnabled && engineReady;

                logger.debug("[VoiceGenerator] Routing Check xttsEnabled={} engineReady={} sapiFallbackEnabled={} useXtts={}",
                    xttsEnabled, engineReady, sapiFallbackEnabled, useXtts);

                if (useXtts) {
                    try {
                        streamXttsVoice(voice, narrationText, resolveLanguage(config));
                        return;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        logger.warn("[VoiceGenerator] XTTS streaming interrupted");
                        return;
                    } catch (IOException e) {
                        logger.warn("[VoiceGenerator] XTTS request failed, falling back to inbuilt synthesizer: {}",
                            e.getMessage());
                    }
                }

                if (!sapiFallbackEnabled) {
                    logger.warn("[VoiceGenerator] XTTS unavailable and SAPI fallback disabled - dropping narration");
                    return;
                }

                if (!synthesizer.isReady()) {
                    logger.warn("InbuiltVoiceSynthesizer not ready - cannot fall back");
                    return;
                }

                logger.debug("Starting fallback audio playback");
                playFallbackVoice(voice, narrationText, rate);
                logger.debug("Fallback audio playback finished");

            } catch (Exception e) {
                logger.error("TTS error", e);
            } finally {
                releasePtt();
                isSpeaking = false;
            }
        });
    }

    public void speak(String text) {
        speakVoice(currentVoice, text, currentVoiceRate);
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

    /**
     * Queue plain text for TTS narration from the OCR pipeline.
     *
     * Phase 0 (OCR migration): The existing queueNarration(Message) requires an XMPP Message
     * object which does not exist in the OCR path. This overload accepts raw text directly
     * and delegates to speak(String). All PTT, XTTS, SAPI, and keybind logic is unchanged.
     *
     * @param text The text to narrate (must not be null or blank)
     */
    public void queueNarration(String text) {
        if (text == null || text.isBlank()) {
            logger.debug("queueNarration(String): null/blank - skipped");
            return;
        }
        logger.info("TTS [OCR] QUEUED: \"{}\": voice={}, rate={}, PTT={}",
            text.length() > 50 ? text.substring(0, 47) + "..." : text,
            currentVoice, currentVoiceRate, pttEnabled);
        speak(text);
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
        if (!enabled) {
            releasePtt();
        }
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
                } catch (NumberFormatException e) {
                    logger.warn("Invalid voice rate in config: {}", speedStr);
                }
            }

            String keyStr = props.getProperty(Main.PROP_PTT_KEY);
            if (keyStr != null) {
                try {
                    keyEvent = Integer.parseInt(keyStr);
                } catch (NumberFormatException e) {
                    logger.warn("Invalid PTT key in config: {}", keyStr);
                }
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

    private Robot createRobot() {
        try {
            Robot createdRobot = new Robot();
            createdRobot.setAutoDelay(0);
            return createdRobot;
        } catch (AWTException e) {
            throw new IllegalStateException("Unable to initialize java.awt.Robot for Push-to-Talk", e);
        }
    }

    private void streamXttsVoice(String voice, String text, String language) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(XTTS_API_URL))
            .timeout(XTTS_REQUEST_TIMEOUT)
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept", "audio/mpeg")
            .POST(HttpRequest.BodyPublishers.ofString(buildJsonPayload(voice, text, language)))
            .build();

        HttpResponse<InputStream> response;
        try {
            response = xttsHttpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (ConnectException e) {
            logger.error("[VoiceGenerator] XTTS endpoint unreachable - marking engine as DEGRADED");
            ValVoiceBackend.getInstance().markDegraded();
            throw e;
        }

        if (response.statusCode() != 200) {
            try (InputStream ignored = response.body()) {
                // Close the response stream before surfacing the failure.
            }
            throw new IOException("XTTS request failed with status: " + response.statusCode());
        }

        AdvancedPlayer player = null;
        try (InputStream speechStream = response.body()) {
            player = new AdvancedPlayer(
                speechStream,
                FactoryRegistry.systemRegistry().createAudioDevice()
            );
            player.setPlayBackListener(new CustomPlaybackListener(keyEvent));
            player.play();
        } catch (JavaLayerException e) {
            throw new IOException("XTTS streaming playback failed", e);
        } finally {
            if (player != null) {
                player.close();
            }
            releasePtt();
        }
    }

    private void playFallbackVoice(String voice, String text, short rate) {
        pressPtt(keyEvent);
        try {
            synthesizer.speakInbuiltVoice(voice, text, rate);
        } finally {
            releasePtt();
        }
    }

    private String resolveLanguage(ValVoiceConfig config) {
        if (config == null || config.language == null || config.language.isBlank()) {
            return DEFAULT_LANGUAGE;
        }
        return config.language;
    }

    private String buildJsonPayload(String voice, String text, String language) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("agent", voice);
        payload.put("text", text);
        payload.put("language", language);
        return GSON.toJson(payload);
    }

    private void pressPtt(int keyCode) {
        if (!pttEnabled) {
            return;
        }

        if (!pttPressed.compareAndSet(false, true)) {
            return;
        }

        activePttKeyCode.set(keyCode);
        synchronized (robot) {
            robot.keyPress(keyCode);
        }
        logger.debug("[VoiceGenerator] PTT pressed: {}", KeyEvent.getKeyText(keyCode));
    }

    private void releasePtt() {
        if (!pttPressed.compareAndSet(true, false)) {
            return;
        }

        int keyCode = activePttKeyCode.get();
        synchronized (robot) {
            robot.keyRelease(keyCode);
        }
        logger.debug("[VoiceGenerator] PTT released: {}", KeyEvent.getKeyText(keyCode));
    }

    private final class CustomPlaybackListener extends PlaybackListener {
        private final int playbackKeyCode;

        private CustomPlaybackListener(int playbackKeyCode) {
            this.playbackKeyCode = playbackKeyCode;
        }

        @Override
        public void playbackStarted(PlaybackEvent evt) {
            pressPtt(playbackKeyCode);
        }

        @Override
        public void playbackFinished(PlaybackEvent evt) {
            releasePtt();
        }
    }
}

