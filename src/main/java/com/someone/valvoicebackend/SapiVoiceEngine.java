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
import java.util.concurrent.TimeUnit;

/**
 * SapiVoiceEngine - Windows SAPI Fallback for TTS Generation
 *
 * Phase 6 Step 1: VN-Parity Fallback Engine
 *
 * This class generates WAV audio files using Windows Speech API (SAPI)
 * when the primary XTTS backend is unavailable (DEGRADED state).
 *
 * Responsibilities:
 * - Generate WAV files via PowerShell + System.Speech
 * - Cache generated files using MD5 hash
 * - Sanitize text for PowerShell execution
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

    /** Prevent instantiation - utility class */
    private SapiVoiceEngine() {
        throw new UnsupportedOperationException("Utility class - do not instantiate");
    }

    /**
     * Generate fallback audio using Windows SAPI.
     *
     * This method:
     * 1. Sanitizes the input text
     * 2. Computes MD5 hash for cache filename
     * 3. Checks if cached WAV exists (returns immediately if so)
     * 4. Generates WAV via PowerShell if not cached
     * 5. Returns path to WAV file (or null on failure)
     *
     * @param text The text to synthesize
     * @param cacheDir The cache directory (typically %LOCALAPPDATA%\ValVoice\cache)
     * @return Path to the generated WAV file, or null if generation failed
     */
    public static Path generateFallbackAudio(String text, Path cacheDir) {
        if (text == null || text.isBlank()) {
            logger.warn("[Fallback] Empty text provided, skipping SAPI generation");
            return null;
        }

        if (cacheDir == null) {
            logger.error("[Fallback] Cache directory is null");
            return null;
        }

        try {
            // Ensure cache directory exists
            Files.createDirectories(cacheDir);

            // Step 1: Sanitize text for PowerShell
            String sanitizedText = sanitizeForPowerShell(text);

            // Step 2: Generate deterministic filename using MD5
            String hash = computeMd5Hash(text);
            String filename = SAPI_FILE_PREFIX + hash + WAV_EXTENSION;
            Path wavFile = cacheDir.resolve(filename);

            // Step 3: Check cache first
            if (Files.exists(wavFile)) {
                logger.debug("[Fallback] Cache HIT: {}", wavFile.getFileName());
                return wavFile;
            }

            logger.info("[Fallback] XTTS engine degraded — generating SAPI voice");

            // Step 4: Generate WAV using PowerShell
            boolean success = generateWavWithPowerShell(sanitizedText, wavFile);

            // Step 5: Return result
            if (success && Files.exists(wavFile)) {
                logger.debug("[Fallback] Generated SAPI audio: {}", wavFile.getFileName());
                return wavFile;
            } else {
                logger.error("[Fallback] Failed to generate SAPI audio");
                return null;
            }

        } catch (Exception e) {
            logger.error("[Fallback] Error during SAPI generation: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Sanitize text for safe PowerShell string injection.
     *
     * Escapes characters that could break PowerShell single-quoted strings.
     *
     * @param text The raw input text
     * @return Sanitized text safe for PowerShell
     */
    private static String sanitizeForPowerShell(String text) {
        if (text == null) {
            return "";
        }

        // Escape single quotes (PowerShell uses '' to escape ' in single-quoted strings)
        String sanitized = text.replace("'", "''");

        // Remove or escape other potentially dangerous characters
        sanitized = sanitized.replace("`", "``");  // Backtick escape
        sanitized = sanitized.replace("$", "`$");  // Dollar sign (variable expansion)

        // Limit length to prevent extremely long speech
        if (sanitized.length() > 500) {
            sanitized = sanitized.substring(0, 500);
            logger.debug("[Fallback] Text truncated to 500 characters");
        }

        return sanitized;
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
     * Executes:
     * Add-Type -AssemblyName System.Speech;
     * $s = New-Object System.Speech.Synthesis.SpeechSynthesizer;
     * $s.SetOutputToWaveFile('PATH');
     * $s.Speak('TEXT');
     * $s.Dispose();
     *
     * @param sanitizedText The sanitized text to speak
     * @param wavFile The output WAV file path
     * @return true if generation succeeded, false otherwise
     */
    private static boolean generateWavWithPowerShell(String sanitizedText, Path wavFile) {
        try {
            // Build PowerShell command
            String absolutePath = wavFile.toAbsolutePath().toString().replace("\\", "\\\\");

            String command = String.format(
                "Add-Type -AssemblyName System.Speech; " +
                "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; " +
                "$s.SetOutputToWaveFile('%s'); " +
                "$s.Speak('%s'); " +
                "$s.Dispose();",
                absolutePath,
                sanitizedText
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

