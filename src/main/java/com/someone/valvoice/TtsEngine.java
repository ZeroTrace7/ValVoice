package com.someone.valvoice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;

/**
 * Simple queued TTS engine invoking Windows SAPI via PowerShell.
 * Each utterance launches a short-lived PowerShell process. This is
 * intentionally simple; for higher volume you could persist a COM instance.
 *
 * ✅ AUTOMATIC TTS WORKFLOW:
 * - Messages are automatically enqueued when chat is detected
 * - Worker thread processes queue and speaks each message
 * - No manual triggers required - fully automatic!
 * - Audio output is automatically routed to VB-Cable by AudioRouter
 * - Valorant Open Mic picks up audio from CABLE Output
 * - Teammates hear TTS automatically!
 *
 * Usage Flow:
 * 1. Player types message in Valorant and presses ENTER
 * 2. XMPP bridge detects message
 * 3. ChatDataHandler calls ValVoiceController.narrateMessage()
 * 4. ValVoiceController calls this.speak() to enqueue the text
 * 5. Worker thread speaks it via Windows SAPI
 * 6. Audio → VB-Cable → Valorant → Teammates! 🎉
 */
public class TtsEngine {
    private static final Logger logger = LoggerFactory.getLogger(TtsEngine.class);

    private final BlockingQueue<SpeakRequest> queue = new LinkedBlockingQueue<>(100); // Limit queue size
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread worker;

    // Performance: Track active process for proper cleanup
    private volatile Process activeProcess = null;

    public TtsEngine() {
        worker = new Thread(this::runLoop, "TTS-Worker");
        worker.setDaemon(true);
        worker.start();
    }

    public void shutdown() {
        running.set(false);
        worker.interrupt();

        // Cleanup any active process immediately
        Process proc = activeProcess;
        if (proc != null && proc.isAlive()) {
            proc.destroyForcibly();
        }

        // Wait for worker to finish
        try {
            worker.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Enqueue text for narration. Voice may be null (default voice). Rate is SAPI range -10..10.
     *
     * This method is called AUTOMATICALLY by ValVoiceController when a chat message
     * passes filtering. No manual user action required!
     */
    public void speak(String text, String voice, int sapiRate) {
        if (text == null || text.isBlank()) return;
        if (!running.get()) {
            logger.warn("TTS engine not running; dropping text");
            return;
        }
        sapiRate = Math.max(-10, Math.min(10, sapiRate));
        // Automatically enqueue - speaks via worker thread
        boolean offered = queue.offer(new SpeakRequest(text, voice, sapiRate));
        if (!offered) {
            logger.warn("TTS queue full, dropping message: {}", abbreviate(text));
        }
    }

    private void runLoop() {
        while (running.get()) {
            try {
                SpeakRequest req = queue.poll(500, TimeUnit.MILLISECONDS);
                if (req != null) {
                    execSpeak(req);
                }
            } catch (InterruptedException e) {
                if (!running.get()) break;
            } catch (Exception ex) {
                logger.error("Unexpected TTS error", ex);
            }
        }
        logger.debug("TTS worker thread stopped");
    }

    private void execSpeak(SpeakRequest req) {
        // Check if we're shutting down before starting
        if (!running.get()) {
            return;
        }

        long start = System.currentTimeMillis();
        String escapedText = escapeForSingleQuotedPs(req.text);
        String escapedVoice = req.voice == null ? "" : req.voice.replace("'", "''");

        // Build PowerShell inline script - optimized command
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
        ps.append("$null=$sp.Speak('").append(escapedText).append("',1);"); // Flag 1 = synchronous
        // Release COM object to prevent memory leaks
        ps.append("[System.Runtime.InteropServices.Marshal]::ReleaseComObject($sp) | Out-Null;");

        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add("powershell.exe");
        cmd.add("-NoLogo");
        cmd.add("-NoProfile");
        cmd.add("-NonInteractive");
        cmd.add("-ExecutionPolicy");
        cmd.add("Bypass");
        cmd.add("-Command");
        cmd.add(ps.toString());

        boolean finished = false;
        int exitCode = -1;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            activeProcess = pb.start();

            // Wait for process with timeout (45 seconds to avoid false timeouts on first-run COM init)
            finished = activeProcess.waitFor(45, TimeUnit.SECONDS);

            if (!finished) {
                logger.warn("TTS process timeout for text: {}", abbreviate(req.text));
                activeProcess.destroyForcibly();
            } else {
                exitCode = activeProcess.exitValue();
                if (exitCode != 0) {
                    logger.debug("TTS process exit code {} for text='{}'", exitCode, abbreviate(req.text));
                }
            }
        } catch (InterruptedException e) {
            // Don't log as warning if we're shutting down
            if (running.get()) {
                logger.debug("TTS interrupted for text: {}", abbreviate(req.text));
            }
            if (activeProcess != null && activeProcess.isAlive()) {
                activeProcess.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            return;
        } catch (Exception e) {
            logger.warn("Failed to speak text: {}", abbreviate(req.text), e);
        } finally {
            // Ensure process cleanup
            Process proc = activeProcess;
            if (proc != null && proc.isAlive()) {
                proc.destroy();
                try {
                    if (!proc.waitFor(500, TimeUnit.MILLISECONDS)) {
                        proc.destroyForcibly();
                    }
                } catch (InterruptedException ie) {
                    proc.destroyForcibly();
                    Thread.currentThread().interrupt();
                }
            }
            activeProcess = null;
        }

        long ms = System.currentTimeMillis() - start;
        // Only log 'Spoke' if the process actually finished successfully
        if (finished && exitCode == 0 && ms > 10000) { // Log only if taking more than 10 seconds
            logger.debug("Spoke ({} ms): {}", ms, abbreviate(req.text));
        }
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
