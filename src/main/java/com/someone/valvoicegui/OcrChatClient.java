package com.someone.valvoicegui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.someone.valvoicebackend.ChatDataHandler;
import com.someone.valvoicebackend.OcrMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * OcrChatClient — manages the ValVoiceOCR.exe sidecar process.
 *
 * Responsibilities:
 *  - Launch ValVoiceOCR.exe via ProcessBuilder
 *  - Read newline-delimited JSON from the sidecar's stdout
 *  - Dispatch "chat" events to ChatDataHandler.handleOcrMessage()
 *  - Fire "diagnostic" and "error" events onto the ValVoiceBackend event bus
 *  - Restart the sidecar up to MAX_RESTARTS times on unexpected death
 *
 * Phase 0 (OCR migration): Replaces the MITM proxy launch + stdout reader in ValVoiceBackend.
 *
 * Protocol: UTF-8, one JSON object per line. Recognized types: "chat", "diagnostic", "error".
 * See implementation_plan.md Phase 7 for full JSON schema.
 */
public class OcrChatClient {
    private static final Logger logger = LoggerFactory.getLogger(OcrChatClient.class);

    /** Path to OCR sidecar EXE relative to the JVM working directory. */
    private static final String OCR_EXE_RELATIVE = "ocr/ValVoiceOCR.exe";

    /** Maximum automatic restarts after unexpected sidecar death. */
    private static final int MAX_RESTARTS = 5;

    public enum OcrState {
        STOPPED,
        STARTING,
        RUNNING,
        WINDOW_SEARCHING,
        DEGRADED
    }

    private Process ocrProcess;
    private final ExecutorService ioPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "OcrChatClient-IO");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running = false;
    private volatile OcrState state = OcrState.STOPPED;
    private int crashCount = 0;
    private long lastLaunchTimestamp = 0;

    // Phase 2.2: Supplier for the local player's display name (OCR self-message ownership)
    private volatile Supplier<String> selfNameSupplier;

    /**
     * Set the supplier that provides the local player's display name.
     * Used for OCR self-message ownership evaluation (VN Supplier pattern).
     *
     * @param supplier returns current selfDisplayName, or null if not yet captured
     */
    public void setSelfNameSupplier(Supplier<String> supplier) {
        this.selfNameSupplier = supplier;
        logger.info("[OcrChatClient] Self name supplier wired");
    }

    /**
     * Launch the OCR sidecar and start reading its stdout.
     *
     * @throws IOException if the sidecar EXE cannot be found or launched
     */
    public void start() throws IOException {
        Path exe = Paths.get(System.getProperty("user.dir")).resolve(OCR_EXE_RELATIVE);
        if (!Files.isRegularFile(exe)) {
            throw new IOException("ValVoiceOCR.exe not found at: " + exe.toAbsolutePath());
        }

        logger.info("[OcrChatClient] Launching sidecar: {}", exe.toAbsolutePath());
        state = OcrState.STARTING;

        ocrProcess = new ProcessBuilder(exe.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();

        running = true;
        lastLaunchTimestamp = System.currentTimeMillis();
        ioPool.submit(this::readLoop);
        logger.info("[OcrChatClient] Sidecar launched (PID may be unavailable on older JVMs)");
    }

    /**
     * Reads stdout from the sidecar line-by-line until the process exits or stop() is called.
     * All JSON parsing and dispatch happens here on the IO thread.
     */
    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(ocrProcess.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                handleLine(line.trim());
            }
            // Phase 2.5.3: OCR Restart Bug Fix (EOF detection)
            if (running) {
                logger.warn("[OcrChatClient] Sidecar stdout closed unexpectedly (EOF)");
                handleSidecarDeath();
            }
        } catch (IOException e) {
            if (running) {
                logger.warn("[OcrChatClient] Sidecar stdout closed unexpectedly: {}", e.getMessage());
                handleSidecarDeath();
            }
        }
        logger.info("[OcrChatClient] Read loop exited");
    }

    /**
     * Parse and dispatch a single JSON line from the sidecar.
     * Silently drops lines that are empty or fail to parse (non-fatal).
     */
    private void handleLine(String line) {
        if (line.isEmpty()) return;
        try {
            JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
            String type = obj.has("type") ? obj.get("type").getAsString() : "";

            switch (type) {
                case "chat" -> {
                    String name    = obj.has("name")      ? obj.get("name").getAsString()      : "Unknown";
                    String body    = obj.has("body")      ? obj.get("body").getAsString()      : "";
                    String channel = obj.has("channel")   ? obj.get("channel").getAsString()   : "TEAM";
                    long   ts      = obj.has("timestamp") ? obj.get("timestamp").getAsLong()   : System.currentTimeMillis();
                    String direction = obj.has("direction") ? obj.get("direction").getAsString() : null;

                    // Phase 2.2: OCR self-message ownership evaluation (VN pattern)
                    // Primary: case-insensitive display name comparison
                    // Override: OCR "direction" field (TO = own, FROM = not own)
                    Supplier<String> supplier = this.selfNameSupplier;
                    String self = supplier != null ? supplier.get() : null;
                    boolean own = self != null && self.equalsIgnoreCase(name);
                    if ("TO".equalsIgnoreCase(direction)) {
                        own = true;
                    } else if ("FROM".equalsIgnoreCase(direction)) {
                        own = false;
                    }
                    logger.debug("[OcrChatClient] Ownership: self='{}' sender='{}' direction='{}' own={}",
                        self, name, direction, own);

                    OcrMessage msg = new OcrMessage(channel, name, body, ts, own);
                    state = OcrState.RUNNING;
                    ChatDataHandler.getInstance().handleOcrMessage(msg);
                }

                case "diagnostic" -> {
                    String event = obj.has("event") ? obj.get("event").getAsString() : "unknown";
                    boolean ok = !event.contains("lost") && !event.contains("warning")
                                 && !event.contains("stopped");

                    // Update internal state
                    if ("window_searching".equals(event)) {
                        state = OcrState.WINDOW_SEARCHING;
                    } else if ("window_found".equals(event)) {
                        state = OcrState.RUNNING;
                    } else if ("ocr_stopped".equals(event) || "window_lost".equals(event)) {
                        state = OcrState.WINDOW_SEARCHING;
                    }

                    // Fire onto ValVoiceBackend event bus
                    ValVoiceBackend backend = ValVoiceBackend.getInstance();
                    if (backend != null) {
                        backend.fireStatusChanged("ocr", event, ok);
                    }
                    logger.debug("[OcrChatClient] Diagnostic: event={} ok={}", event, ok);
                }

                case "error" -> {
                    int code = obj.has("code") ? obj.get("code").getAsInt() : -1;
                    String reason = obj.has("reason") ? obj.get("reason").getAsString() : "unknown";
                    logger.error("[OcrChatClient] Sidecar error: code={} reason={}", code, reason);
                    if (code == 400 || code == 503) {
                        // Fatal sidecar errors — do not restart
                        state = OcrState.DEGRADED;
                    }
                }

                default -> logger.debug("[OcrChatClient] Unknown message type '{}': {}", type, line);
            }
        } catch (Exception e) {
            logger.debug("[OcrChatClient] Failed to parse sidecar line: {} ({})", e.getMessage(), line);
        }
    }

    /**
     * Called when the sidecar process exits unexpectedly.
     * Attempts up to MAX_RESTARTS automatic restarts before marking DEGRADED.
     *
     * Production hardening (P0):
     *  - Uptime-based crash-count reset: if sidecar ran >=60s, treat as fresh failure
     *  - Exponential backoff: 2s, 4s, 8s, 16s, 32s between restart attempts
     *  - Check-before-increment: preserves exactly MAX_RESTARTS restart attempts
     */
    private void handleSidecarDeath() {
        int exitCode = -1;
        try {
            exitCode = ocrProcess.exitValue();
        } catch (IllegalThreadStateException ignored) {
            // Process still alive — shouldn't happen here
        }

        // Uptime-based crash-count reset:
        // If the sidecar ran for 60+ seconds before dying, it was stable.
        // Treat this as a fresh, isolated failure — not a sequential crash.
        long uptimeMs = System.currentTimeMillis() - lastLaunchTimestamp;
        if (uptimeMs >= 60_000) {
            logger.info("[OcrChatClient] Sidecar ran for {}s before crash — resetting crash counter", uptimeMs / 1000);
            crashCount = 0;
        }

        // Guard: check BEFORE incrementing to preserve exactly MAX_RESTARTS restart attempts.
        // With MAX_RESTARTS=5: allows restarts at crashCount 0,1,2,3,4 (5 restarts total).
        // On the 6th crash (crashCount=5), 5 >= 5 → DEGRADED.
        if (crashCount >= MAX_RESTARTS) {
            logger.warn("[OcrChatClient] Sidecar exited (code={}). Restart limit reached ({}/{}) — entering DEGRADED (uptime={}ms)",
                    exitCode, crashCount, MAX_RESTARTS, uptimeMs);
            degraded();
            return;
        }

        crashCount++;
        logger.warn("[OcrChatClient] Sidecar exited (code={}). Crash count: {}/{} (uptime={}ms)",
                exitCode, crashCount, MAX_RESTARTS, uptimeMs);

        // Exponential backoff: 2s, 4s, 8s, 16s, 32s
        long backoffMs = 2000L * (1L << (crashCount - 1));
        logger.info("[OcrChatClient] Restarting sidecar after {}ms backoff (attempt {}/{})...",
                backoffMs, crashCount, MAX_RESTARTS);
        try {
            Thread.sleep(backoffMs);
            start();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("[OcrChatClient] Restart interrupted during backoff");
            degraded();
        } catch (IOException e) {
            logger.error("[OcrChatClient] Restart failed: {}", e.getMessage());
            degraded();
        }
    }

    private void degraded() {
        state = OcrState.DEGRADED;
        logger.error("[OcrChatClient] Sidecar in DEGRADED state — OCR chat narration unavailable.");
        ValVoiceBackend backend = ValVoiceBackend.getInstance();
        if (backend != null) {
            backend.fireStatusChanged("ocr", "Degraded", false);
        }
    }

    /**
     * Stop the sidecar and shut down the IO thread pool.
     * Safe to call multiple times.
     */
    public void stop() {
        running = false;
        state = OcrState.STOPPED;
        if (ocrProcess != null && ocrProcess.isAlive()) {
            ocrProcess.destroyForcibly();
            logger.info("[OcrChatClient] Sidecar process forcibly terminated.");
        }
        ioPool.shutdownNow();
    }

    /**
     * Return the current operational state of the OCR sidecar.
     */
    public OcrState getState() {
        return state;
    }

    /**
     * Return true if the sidecar process is alive and the client is running.
     */
    public boolean isAlive() {
        return running && ocrProcess != null && ocrProcess.isAlive();
    }
}
