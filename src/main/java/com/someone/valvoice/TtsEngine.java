package com.someone.valvoice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Simple queued TTS engine invoking Windows SAPI via PowerShell.
 * Each utterance launches a short-lived PowerShell process. This is
 * intentionally simple; for higher volume you could persist a COM instance.
 */
public class TtsEngine {
    private static final Logger logger = LoggerFactory.getLogger(TtsEngine.class);

    private final BlockingQueue<SpeakRequest> queue = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread worker;

    public TtsEngine() {
        worker = new Thread(this::runLoop, "TTS-Worker");
        worker.setDaemon(true);
        worker.start();
    }

    public void shutdown() {
        running.set(false);
        worker.interrupt();
    }

    /**
     * Enqueue text for narration. Voice may be null (default voice). Rate is SAPI range -10..10.
     */
    public void speak(String text, String voice, int sapiRate) {
        if (text == null || text.isBlank()) return;
        if (!running.get()) {
            logger.warn("TTS engine not running; dropping text");
            return;
        }
        sapiRate = Math.max(-10, Math.min(10, sapiRate));
        queue.offer(new SpeakRequest(text, voice, sapiRate));
    }

    private void runLoop() {
        while (running.get()) {
            try {
                SpeakRequest req = queue.take();
                execSpeak(req);
            } catch (InterruptedException e) {
                if (!running.get()) break;
            } catch (Exception ex) {
                logger.error("Unexpected TTS error", ex);
            }
        }
    }

    private void execSpeak(SpeakRequest req) {
        long start = System.currentTimeMillis();
        String escapedText = escapeForSingleQuotedPs(req.text);
        String escapedVoice = req.voice == null ? "" : req.voice.replace("'", "''");

        // Build PowerShell inline script
        StringBuilder ps = new StringBuilder();
        ps.append("$ErrorActionPreference='SilentlyContinue'; ");
        // Using COM SAPI.SpVoice (faster to instantiate than System.Speech for quick lines)
        ps.append("$sp=New-Object -ComObject SAPI.SpVoice; ");
        ps.append("$sp.Rate=").append(req.sapiRate).append("; ");
        if (!escapedVoice.isBlank()) {
            // Attempt exact description match
            ps.append("$v=$sp.GetVoices() | Where-Object { $_.GetDescription() -eq '").append(escapedVoice).append("' }; ");
            ps.append("if($v.Count -gt 0){ $sp.Voice=$v.Item(0) }; ");
        }
        ps.append("$null=$sp.Speak('").append(escapedText).append("');");

        String command = "powershell.exe -NoLogo -NoProfile -NonInteractive -Command \"" + ps.toString().replace("\"", "\\\"") + "\"";
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(command);
            int code = process.waitFor();
            if (code != 0) {
                logger.debug("TTS process exit code {} for text='{}'", code, abbreviate(req.text));
            }
        } catch (Exception e) {
            logger.warn("Failed to speak text: {}", abbreviate(req.text), e);
        } finally {
            if (process != null) process.destroyForcibly();
        }
        long ms = System.currentTimeMillis() - start;
        logger.trace("Spoke ({} ms): {}", ms, abbreviate(req.text));
    }

    private String abbreviate(String s) {
        if (s == null) return null;
        return s.length() <= 40 ? s : s.substring(0, 37) + "...";
    }

    private String escapeForSingleQuotedPs(String s) {
        // In single-quoted PowerShell strings only single quote needs doubling.
        // Also flatten newlines to spaces to avoid awkward breaks.
        return s.replace("'", "''").replace('\n',' ').replace('\r',' ');
    }

    private record SpeakRequest(String text, String voice, int sapiRate) {
        private SpeakRequest {
            Objects.requireNonNull(text, "text");
        }
    }
}

