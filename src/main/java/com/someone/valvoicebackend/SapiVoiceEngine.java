package com.someone.valvoicebackend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * SapiVoiceEngine - Windows SAPI Fallback for TTS Generation
 *
 * Phase 6 Step 1: VN-Parity Fallback Engine
 * Phase 6 Step 2: Hardened Pipeline Protection
 *
 * This class generates WAV audio files using Windows Speech API (SAPI)
 * when the primary XTTS backend is unavailable (DEGRADED state).
 *
 * Responsibilities:
 * - Generate WAV files via PowerShell + System.Speech
 * - Cache generated files using MD5 hash
 * - Sanitize text for PowerShell execution
 * - Rate limit PowerShell spawning to prevent storms
 * - Truncate long text to prevent blocking
 *
 * This class does NOT handle:
 * - Audio playback (delegated to playAudio())
 * - PTT injection (handled by existing pipeline)
 * - VB-Cable routing (handled by AudioRouterUtility)
 *
 * @author ValVoice Team
 * @since Phase 6 Step 1
 */
public final class SapiVoiceEngine {

    private static final Logger logger = LoggerFactory.getLogger(SapiVoiceEngine.class);

    /** Prefix for SAPI-generated WAV files */
    private static final String SAPI_FILE_PREFIX = "sapi_";

    /** File extension for generated audio */
    private static final String WAV_EXTENSION = ".wav";

    /** Maximum time to wait for PowerShell generation (seconds) */
    private static final int GENERATION_TIMEOUT_SECONDS = 10;

    /** Maximum text length for SAPI generation (Phase 6 Step 2) */
    private static final int MAX_TEXT_LENGTH = 300;

    // ─────────────────────────────────────────────
    // RATE LIMITER (Phase 6 Step 2)
    // Prevents PowerShell spawning storms during spam events
    // ─────────────────────────────────────────────

    /** Minimum interval between PowerShell executions (ms) */
    private static final long MIN_GENERATION_INTERVAL_MS = 250;

    /** Timestamp of last PowerShell generation */
    private static volatile long lastGenerationTime = 0;

    /** Prevent instantiation - utility class */
    private SapiVoiceEngine() {
        throw new UnsupportedOperationException("Utility class - do not instantiate");
    }

    /**
     * Generate fallback audio using Windows SAPI.
     *
     * Phase 6 Step 2: Hardened pipeline with:
     * - Text validation and truncation (max 300 chars)
     * - Cache fast-path (no rate limit for cached files)
     * - Rate limiting for PowerShell spawning (250ms minimum interval)
     * - File existence verification after generation
     *
     * @param text The text to synthesize
     * @param cacheDir The cache directory (typically %LOCALAPPDATA%\ValVoice\cache)
     * @return Path to the generated WAV file, or null if generation failed
     */
    public static Path generateFallbackAudio(String text, Path cacheDir) {
        // ═══════════════════════════════════════════════════════
        // PHASE 6 STEP 2: Text Validation
        // ═══════════════════════════════════════════════════════
        if (text == null || text.isBlank()) {
            logger.debug("[Fallback] Empty text provided, skipping SAPI generation");
            return null;
        }

        if (cacheDir == null) {
            logger.error("[Fallback] Cache directory is null");
            return null;
        }

        // Phase 6 Step 2: Truncate long text BEFORE hashing
        String processedText = text;
        if (processedText.length() > MAX_TEXT_LENGTH) {
            processedText = processedText.substring(0, MAX_TEXT_LENGTH);
            logger.debug("[Fallback] Text truncated to {} characters", MAX_TEXT_LENGTH);
        }

        try {
            // Ensure cache directory exists
            Files.createDirectories(cacheDir);

            // Step 1: Generate deterministic filename using MD5 (of truncated text)
            String hash = computeMd5Hash(processedText);
            String filename = SAPI_FILE_PREFIX + hash + WAV_EXTENSION;
            Path wavFile = cacheDir.resolve(filename);

            // ═══════════════════════════════════════════════════════
            // PHASE 6 STEP 2: Cache Check (BEFORE rate limiter)
            // Cached files play instantly without restriction
            // ═══════════════════════════════════════════════════════
            if (Files.exists(wavFile)) {
                logger.debug("[Fallback] Cache HIT: {}", wavFile.getFileName());
                return wavFile;
            }

            // ═══════════════════════════════════════════════════════
            // PHASE 6 STEP 2: Rate Limiter (AFTER cache miss)
            // Prevents PowerShell spawning storms during spam
            // ═══════════════════════════════════════════════════════
            long now = System.currentTimeMillis();
            if (now - lastGenerationTime < MIN_GENERATION_INTERVAL_MS) {
                logger.debug("[Fallback] Rate limited PowerShell spawning ({}ms since last)",
                        now - lastGenerationTime);
                return null;
            }
            lastGenerationTime = now;

            // Log fallback activation with warning level for visibility
            logger.warn("[Fallback] XTTS engine degraded — using Windows SAPI fallback");

            // SECURITY: Base64 encode text to prevent PowerShell injection (CWE-78)
            String encodedText = Base64.getEncoder()
                    .encodeToString(processedText.getBytes(StandardCharsets.UTF_8));

            // Step 3: Generate WAV using PowerShell with Base64-encoded text
            boolean success = generateWavWithPowerShell(encodedText, wavFile);

            // ═══════════════════════════════════════════════════════
            // PHASE 6 STEP 2: Verify file exists after generation
            // ═══════════════════════════════════════════════════════
            if (!success) {
                logger.warn("[Fallback] SAPI generation failed");
                return null;
            }

            if (!Files.exists(wavFile)) {
                logger.warn("[Fallback] SAPI generation failed, WAV not created");
                return null;
            }

            logger.debug("[Fallback] Generated SAPI audio: {}", wavFile.getFileName());
            return wavFile;

        } catch (Exception e) {
            logger.error("[Fallback] Error during SAPI generation: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Compute MD5 hash of text for deterministic cache filenames.
     *
     * @param text The text to hash
     * @return Lowercase hex string of MD5 hash
     */
    private static String computeMd5Hash(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hashBytes = md.digest(text.getBytes(StandardCharsets.UTF_8));

            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();

        } catch (NoSuchAlgorithmException e) {
            // MD5 is guaranteed to be available in all JVMs
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }

    /**
     * Generate WAV file using PowerShell and Windows SAPI.
     *
     * SECURITY: Text is received as Base64-encoded string.
     * PowerShell decodes it internally before speaking.
     * This eliminates command injection risk entirely.
     *
     * @param encodedText Base64-encoded text to speak
     * @param wavFile The output WAV file path
     * @return true if generation succeeded, false otherwise
     */
    private static boolean generateWavWithPowerShell(String encodedText, Path wavFile) {
        try {
            // Build PowerShell command with Base64-safe text
            String absolutePath = wavFile.toAbsolutePath().toString().replace("\\", "\\\\");

            String command = String.format(
                "Add-Type -AssemblyName System.Speech; " +
                "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; " +
                "$s.SetOutputToWaveFile('%s'); " +
                "$decoded = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String('%s')); " +
                "$s.Speak($decoded); " +
                "$s.Dispose();",
                absolutePath,
                encodedText
            );

            // Create ProcessBuilder
            ProcessBuilder builder = new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                command
            );

            // Configure process
            builder.redirectErrorStream(true);
            // Do NOT use inheritIO() - keep process hidden

            // Start process
            Process process = builder.start();

            // Consume output to prevent blocking
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.debug("[Fallback] PowerShell: {}", line);
                }
            }

            // Wait with timeout
            boolean completed = process.waitFor(GENERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!completed) {
                // Timeout - destroy process
                process.destroyForcibly();
                logger.warn("[Fallback] PowerShell SAPI generation timed out");
                return false;
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                logger.warn("[Fallback] PowerShell exited with code: {}", exitCode);
                return false;
            }

            return true;

        } catch (Exception e) {
            logger.error("[Fallback] PowerShell execution error: {}", e.getMessage());
            return false;
        }
    }
}

