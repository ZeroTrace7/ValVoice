package com.someone.valvoicebackend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hardware-level playback spy for the VB-Cable capture line.
 * Detects when native SAPI output is physically present on "CABLE Output".
 */
public final class PlaybackDetector implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(PlaybackDetector.class);

    private static final AudioFormat MONITOR_FORMAT = new AudioFormat(48_000.0f, 16, 1, true, false);
    private static final int BUFFER_SIZE_BYTES = 1_920; // 20ms @ 48kHz, 16-bit mono
    private static final int ROLLING_WINDOW_SIZE = 5;
    private static final float DETECT_THRESHOLD_DB = -50.0f;
    private static final long DEBOUNCE_NS = TimeUnit.MILLISECONDS.toNanos(20);
    private static final long REOPEN_INTERVAL_NS = TimeUnit.MILLISECONDS.toNanos(500);
    private static final float SILENCE_DB = -100.0f;
    private static final String TARGET_MIXER_NAME = "cable output";

    public enum State {
        IDLE,
        PLAYING
    }

    public interface Listener {
        void onAudioStart();

        void onAudioStop();
    }

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Object stateLock = new Object();
    private final byte[] pcmBuffer = new byte[BUFFER_SIZE_BYTES];
    private final float[] sampleBuffer = new float[BUFFER_SIZE_BYTES / 2];
    private final float[] rollingDbWindow = new float[ROLLING_WINDOW_SIZE];

    private volatile State state = State.IDLE;
    private volatile boolean available;
    private volatile boolean playing;

    private TargetDataLine captureLine;
    private Thread backgroundThread;
    private String activeMixerLabel = "CABLE Output";
    private float rollingDbSum;
    private int rollingDbCount;
    private int rollingDbIndex;
    private long aboveThresholdSinceNs = -1L;
    private long belowThresholdSinceNs = -1L;
    private long nextOpenAttemptNs;

    public PlaybackDetector() {
        startBackgroundThread();
    }

    public void addListener(Listener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public boolean isPlaying() {
        return playing;
    }

    public boolean isAvailable() {
        return available;
    }

    public State getState() {
        return state;
    }

    public void reset() {
        boolean notifyStop = false;
        synchronized (stateLock) {
            clearRollingWindow();
            aboveThresholdSinceNs = -1L;
            belowThresholdSinceNs = -1L;
            if (state == State.PLAYING) {
                state = State.IDLE;
                playing = false;
                notifyStop = true;
            } else {
                playing = false;
            }
        }

        if (notifyStop) {
            fireAudioStop();
        }
    }

    private void startBackgroundThread() {
        backgroundThread = new Thread(() -> {
            while (true) {
                if (!running.get()) {
                    break;
                }
                updateStateMachine();
                Thread.onSpinWait();
            }
        }, "playback-detector");
        backgroundThread.setDaemon(true);
        backgroundThread.start();
    }

    private void updateStateMachine() {
        if (!ensureCaptureLine()) {
            return;
        }

        int bytesRead;
        try {
            bytesRead = captureLine.read(pcmBuffer, 0, pcmBuffer.length);
        } catch (Exception e) {
            if (running.get()) {
                logger.debug("[PlaybackDetector] Capture read failed on {}: {}", activeMixerLabel, e.getMessage());
            }
            markLineUnavailable();
            return;
        }

        if (bytesRead <= 0) {
            processDecibelReading(SILENCE_DB);
            return;
        }

        int sampleCount = bytesRead / 2;
        if (sampleCount <= 0) {
            processDecibelReading(SILENCE_DB);
            return;
        }

        convertPcmToFloats(pcmBuffer, sampleBuffer, sampleCount);
        float rms = computeRms(sampleBuffer, sampleCount);
        float db = rms > 0.0f ? (float) (20.0 * Math.log10(rms)) : SILENCE_DB;
        processDecibelReading(db);
    }

    private boolean ensureCaptureLine() {
        TargetDataLine currentLine = captureLine;
        if (currentLine != null && currentLine.isOpen()) {
            available = true;
            return true;
        }

        long now = System.nanoTime();
        if (now < nextOpenAttemptNs) {
            available = false;
            return false;
        }
        nextOpenAttemptNs = now + REOPEN_INTERVAL_NS;

        try {
            captureLine = locateAndOpenCableOutputLine();
            available = true;
            logger.info("[PlaybackDetector] Monitoring mixer: {}", activeMixerLabel);
            return true;
        } catch (LineUnavailableException e) {
            available = false;
            logger.warn("[PlaybackDetector] Unable to open {}: {}", activeMixerLabel, e.getMessage());
            return false;
        }
    }

    private TargetDataLine locateAndOpenCableOutputLine() throws LineUnavailableException {
        DataLine.Info lineInfo = new DataLine.Info(TargetDataLine.class, MONITOR_FORMAT);

        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            if (!isCableOutputMixer(mixerInfo)) {
                continue;
            }

            Mixer mixer = AudioSystem.getMixer(mixerInfo);
            if (!mixer.isLineSupported(lineInfo)) {
                continue;
            }

            TargetDataLine line = (TargetDataLine) mixer.getLine(lineInfo);
            line.open(MONITOR_FORMAT);
            line.start();
            activeMixerLabel = mixerInfo.getName();
            return line;
        }

        throw new LineUnavailableException("Mixer named \"CABLE Output\" was not found");
    }

    private boolean isCableOutputMixer(Mixer.Info mixerInfo) {
        String name = normalize(mixerInfo.getName());
        String description = normalize(mixerInfo.getDescription());
        return name.contains(TARGET_MIXER_NAME) || description.contains(TARGET_MIXER_NAME);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private void convertPcmToFloats(byte[] source, float[] destination, int sampleCount) {
        for (int i = 0; i < sampleCount; i++) {
            int offset = i * 2;
            int low = source[offset] & 0xff;
            int high = source[offset + 1];
            short sample = (short) ((high << 8) | low);
            destination[i] = sample / 32768.0f;
        }
    }

    private float computeRms(float[] samples, int sampleCount) {
        double sumSquares = 0.0;
        for (int i = 0; i < sampleCount; i++) {
            float sample = samples[i];
            sumSquares += sample * sample;
        }
        return (float) Math.sqrt(sumSquares / sampleCount);
    }

    private void processDecibelReading(float dbReading) {
        boolean notifyStart = false;
        boolean notifyStop = false;

        synchronized (stateLock) {
            float rollingDb = updateRollingAverage(dbReading);
            long now = System.nanoTime();

            if (state == State.IDLE) {
                belowThresholdSinceNs = -1L;
                if (rollingDb > DETECT_THRESHOLD_DB) {
                    if (aboveThresholdSinceNs < 0L) {
                        aboveThresholdSinceNs = now;
                    } else if (now - aboveThresholdSinceNs >= DEBOUNCE_NS) {
                        state = State.PLAYING;
                        playing = true;
                        aboveThresholdSinceNs = -1L;
                        notifyStart = true;
                    }
                } else {
                    aboveThresholdSinceNs = -1L;
                }
            } else {
                aboveThresholdSinceNs = -1L;
                if (rollingDb < DETECT_THRESHOLD_DB) {
                    if (belowThresholdSinceNs < 0L) {
                        belowThresholdSinceNs = now;
                    } else if (now - belowThresholdSinceNs >= DEBOUNCE_NS) {
                        state = State.IDLE;
                        playing = false;
                        belowThresholdSinceNs = -1L;
                        notifyStop = true;
                    }
                } else {
                    belowThresholdSinceNs = -1L;
                }
            }
        }

        if (notifyStart) {
            fireAudioStart();
        }
        if (notifyStop) {
            fireAudioStop();
        }
    }

    private float updateRollingAverage(float dbReading) {
        if (rollingDbCount < ROLLING_WINDOW_SIZE) {
            rollingDbWindow[rollingDbCount] = dbReading;
            rollingDbSum += dbReading;
            rollingDbCount++;
        } else {
            rollingDbSum -= rollingDbWindow[rollingDbIndex];
            rollingDbWindow[rollingDbIndex] = dbReading;
            rollingDbSum += dbReading;
            rollingDbIndex = (rollingDbIndex + 1) % ROLLING_WINDOW_SIZE;
        }

        return rollingDbSum / rollingDbCount;
    }

    private void clearRollingWindow() {
        rollingDbSum = 0.0f;
        rollingDbCount = 0;
        rollingDbIndex = 0;
        for (int i = 0; i < rollingDbWindow.length; i++) {
            rollingDbWindow[i] = 0.0f;
        }
    }

    private void fireAudioStart() {
        for (Listener listener : listeners) {
            try {
                listener.onAudioStart();
            } catch (Exception e) {
                logger.debug("[PlaybackDetector] Listener onAudioStart failed: {}", e.getMessage());
            }
        }
    }

    private void fireAudioStop() {
        for (Listener listener : listeners) {
            try {
                listener.onAudioStop();
            } catch (Exception e) {
                logger.debug("[PlaybackDetector] Listener onAudioStop failed: {}", e.getMessage());
            }
        }
    }

    private void markLineUnavailable() {
        available = false;
        closeCaptureLine();

        boolean notifyStop = false;
        synchronized (stateLock) {
            clearRollingWindow();
            aboveThresholdSinceNs = -1L;
            belowThresholdSinceNs = -1L;
            if (state == State.PLAYING) {
                state = State.IDLE;
                playing = false;
                notifyStop = true;
            } else {
                playing = false;
            }
        }

        if (notifyStop) {
            fireAudioStop();
        }
    }

    private void closeCaptureLine() {
        TargetDataLine line = captureLine;
        captureLine = null;
        if (line == null) {
            return;
        }

        try {
            line.stop();
        } catch (Exception ignored) {
        }
        try {
            line.close();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void close() {
        running.set(false);
        markLineUnavailable();

        if (backgroundThread != null) {
            backgroundThread.interrupt();
        }
    }
}
