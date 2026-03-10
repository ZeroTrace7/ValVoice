package com.someone.valvoicebackend;

import com.someone.valvoicegui.ValVoiceBackend;
import com.someone.valvoicebackend.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.Union;

import javafx.application.Platform;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;

/**
 * Persistent PowerShell-based Windows voice synthesizer using System.Speech.
 * Routes TTS audio to VB-CABLE for Valorant voice chat integration.
 *
 * Phase 2: Voice Injection Core
 * - Validates SoundVolumeView.exe presence on startup
 * - Validates VB-Cable device availability
 * - Throws DependencyMissingException if critical dependencies are absent
 *
 * PHASE 2 SECURITY (VN-Parity):
 * - SoundVolumeView.exe executed via ABSOLUTE PATH only (no PATH lookup)
 * - Fixed path: %ProgramFiles%/ValVoice/SoundVolumeView.exe
 * - File existence check before execution (graceful degradation if missing)
 * - PowerShell executed via system binary name (OS resolves from System32)
 */
public class InbuiltVoiceSynthesizer {
    private static final Logger logger = LoggerFactory.getLogger(InbuiltVoiceSynthesizer.class);

    // ─────────────────────────────────────────────
    // JNA USER32 INTERFACE (VN-Parity Phase 5 Step 2)
    // ─────────────────────────────────────────────
    /**
     * Native Windows user32.dll interface for SendInput API.
     * VN-Parity: Direct native calls, no java.awt.Robot overhead.
     */
    private interface User32 extends Library {
        User32 INSTANCE = Native.load("user32", User32.class);

        int INPUT_KEYBOARD = 1;
        int KEYEVENTF_KEYUP = 0x0002;

        @Structure.FieldOrder({"type", "input"})
        class INPUT extends Structure {
            public int type;
            public INPUT_UNION input;

            public static class INPUT_UNION extends Union {
                public KEYBDINPUT ki;
            }
        }

        @Structure.FieldOrder({"wVk", "wScan", "dwFlags", "time", "dwExtraInfo"})
        class KEYBDINPUT extends Structure {
            public short wVk;
            public short wScan;
            public int dwFlags;
            public int time;
            public Pointer dwExtraInfo;
        }

        int SendInput(int nInputs, INPUT[] pInputs, int cbSize);
    }

    // ─────────────────────────────────────────────
    // PTT STATE (VN-Parity Phase 5 Step 2)
    // ─────────────────────────────────────────────
    /** Default PTT key: 'V' (0x56) */
    private static final short DEFAULT_PTT_KEY = 0x56;
    /** Current PTT key state - prevents double press */
    private final AtomicBoolean isPttPressed = new AtomicBoolean(false);
    /** Configured PTT key (virtual key code) */
    private short configuredPttKey = DEFAULT_PTT_KEY;

    /**
     * Phase 7: Resolve PTT virtual key code from config string.
     * For letters and digits, Windows VK codes match uppercase ASCII values.
     * No AWT dependency required.
     *
     * @param key The key string from config (e.g. "V", "U", "B")
     * @return Windows Virtual Key code
     */
    private short resolveVirtualKey(String key) {
        if (key == null || key.isEmpty()) {
            return DEFAULT_PTT_KEY;
        }
        return (short) Character.toUpperCase(key.charAt(0));
    }

    private Process powershellProcess;
    private PrintWriter powershellWriter;
    private BufferedReader powershellReader;
    private final List<String> voices = new ArrayList<>();
    private String soundVolumeViewPath;
    private boolean audioRoutingConfigured = false;

    // ─────────────────────────────────────────────
    // TTS API CLIENT (VN-Parity Phase 4)
    // ─────────────────────────────────────────────
    /** TTS engine endpoint URL */
    private static final String TTS_API_URL = "http://127.0.0.1:5005/speak";
    /** HTTP connect timeout (VN-parity: fail fast) */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** HTTP request timeout (VN-parity: 15s strict) */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    /** Default language for TTS requests */
    private static final String DEFAULT_LANGUAGE = "en";
    /** Temp directory for TTS audio files */
    private static final String TTS_TEMP_DIR = "ValVoice/tmp";

    // === CACHE LAYER START ===
    /** Cache directory name under %LOCALAPPDATA% */
    private static final String CACHE_DIR_NAME = "ValVoice/cache";
    /** Maximum cache size in bytes (100MB) */
    private static final long MAX_CACHE_SIZE_BYTES = 100L * 1024 * 1024;
    // === CACHE LAYER END ===

    // ─────────────────────────────────────────────
    // TTS QUEUE (VN-Parity Phase 4 Step 3)
    // ─────────────────────────────────────────────

    /**
     * Internal request model for the TTS queue.
     */
    private static class TtsRequest {
        final String agent;
        final String text;

        TtsRequest(String agent, String text) {
            this.agent = agent;
            this.text = text;
        }
    }

    /** Sequential processing queue. Capacity = 10, drop newest if full. */
    private final BlockingQueue<TtsRequest> ttsQueue = new LinkedBlockingQueue<>(10);

    /** Lazy-initialized HttpClient for TTS API requests */
    private volatile HttpClient ttsHttpClient;

    // ─────────────────────────────────────────────
    // ENGINE RECOVERY PROBE (Phase 6 Step 3)
    // ─────────────────────────────────────────────

    /** Minimum interval between engine recovery probes (ms) */
    private static final long PROBE_COOLDOWN_MS = 10_000; // 10 seconds

    /** Timestamp of last recovery probe attempt */
    private long lastProbeTime = 0;

    /**
     * Probe the XTTS backend to check if it has come back online.
     * Used for opportunistic recovery from DEGRADED state.
     *
     * Phase 6 Step 3: Non-blocking socket probe with 500ms timeout.
     *
     * @return true if backend is responding on port 5005, false otherwise
     */
    private boolean probeEngine() {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", 5005), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Exception thrown when a required dependency is missing.
     */
    public static class DependencyMissingException extends RuntimeException {
        private final String dependencyName;
        private final String installUrl;

        public DependencyMissingException(String dependencyName, String message, String installUrl) {
            super(message);
            this.dependencyName = dependencyName;
            this.installUrl = installUrl;
        }

        public String getDependencyName() { return dependencyName; }
        public String getInstallUrl() { return installUrl; }
    }

    /**
     * Initialize synthesizer with optional strict mode.
     * @param strictMode If true, throws DependencyMissingException when VB-Cable or SoundVolumeView.exe is missing
     */
    public InbuiltVoiceSynthesizer(boolean strictMode) {
        initializePowerShell();
        loadAvailableVoices();
        findSoundVolumeView();

        // Phase 2: Strict dependency validation
        if (strictMode) {
            validateDependencies();
        }

        routeAudioToVbCable();

        // ─────────────────────────────────────────────
        // TTS QUEUE CONSUMER (VN-Parity Phase 4 Step 3)
        // ─────────────────────────────────────────────
        Thread consumer = new Thread(this::processQueue, "TTS-Consumer");
        consumer.setDaemon(true);
        consumer.start();
        logger.debug("[TTS Queue] Consumer thread started");
    }

    public InbuiltVoiceSynthesizer() {
        this(false); // Non-strict mode for backward compatibility
    }

    /**
     * Phase 2: Validate critical dependencies for voice injection.
     * VN-parity: Only VB-Cable is a hard dependency. SoundVolumeView.exe is optional.
     * If SoundVolumeView.exe is missing, audio routing is disabled but TTS still works.
     */
    private void validateDependencies() {
        // VN-parity: SoundVolumeView.exe is NOT a hard dependency
        // Log warning but continue - TTS still works, just audio routing disabled
        if (soundVolumeViewPath == null) {
            logger.warn("[AudioRouting] Disabled: SoundVolumeView.exe not found at %ProgramFiles%/ValVoice/SoundVolumeView.exe");
            logger.warn("[AudioRouting] TTS will play through default speakers instead of VB-Cable");
            // Do NOT throw - graceful degradation per VN architecture
        }

        // Check VB-Cable device - this IS a hard dependency for voice injection
        if (!isVbCableInstalled()) {
            logger.error("FATAL: VB-Audio Virtual Cable not detected - cannot inject voice into game");
            throw new DependencyMissingException(
                "VB-Audio Virtual Cable",
                "VB-Audio Virtual Cable is required for voice injection.\n\n" +
                "Please install VB-Cable from: https://vb-audio.com/Cable/\n" +
                "After installation, restart your computer and try again.",
                "https://vb-audio.com/Cable/"
            );
        }
    }

    /**
     * Phase 2: Detect if VB-Cable is installed by querying Windows audio devices.
     */
    private boolean isVbCableInstalled() {
        if (powershellWriter == null || powershellProcess == null || !powershellProcess.isAlive()) {
            logger.warn("PowerShell not available for VB-Cable detection");
            return false;
        }

        try {
            String sentinel = "VBCABLE_CHECK_" + System.currentTimeMillis();
            String command = "Get-CimInstance Win32_SoundDevice | Select-Object -ExpandProperty Name; echo '" + sentinel + "'";
            powershellWriter.println(command);

            StringBuilder buffer = new StringBuilder();
            long startTime = System.currentTimeMillis();
            long timeoutMs = 10000;

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                if (powershellReader.ready()) {
                    int ch = powershellReader.read();
                    if (ch == -1) break;
                    if (ch == '\n' || ch == '\r') {
                        String line = buffer.toString().trim();
                        if (line.equals(sentinel)) {
                            return false; // Finished without finding VB-Cable
                        }
                        String lower = line.toLowerCase();
                        if (lower.contains("vb-audio") || lower.contains("cable input") || lower.contains("cable output")) {
                            // Consume remaining output until sentinel
                            consumeUntilSentinel(sentinel, timeoutMs - (System.currentTimeMillis() - startTime));
                            logger.info("VB-Cable detected: {}", line);
                            return true;
                        }
                        buffer.setLength(0);
                    } else {
                        buffer.append((char) ch);
                    }
                } else {
                    Thread.sleep(50);
                }
            }
        } catch (Exception e) {
            logger.debug("VB-Cable detection error: {}", e.getMessage());
        }
        return false;
    }

    private void consumeUntilSentinel(String sentinel, long timeoutMs) {
        try {
            StringBuilder buffer = new StringBuilder();
            long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                if (powershellReader.ready()) {
                    int ch = powershellReader.read();
                    if (ch == -1) break;
                    if (ch == '\n' || ch == '\r') {
                        if (buffer.toString().trim().equals(sentinel)) return;
                        buffer.setLength(0);
                    } else {
                        buffer.append((char) ch);
                    }
                } else {
                    Thread.sleep(20);
                }
            }
        } catch (Exception ignored) {}
    }

    private void initializePowerShell() {
        try {
            powershellProcess = new ProcessBuilder("powershell.exe", "-NoExit", "-Command", "-").start();
            powershellWriter = new PrintWriter(new OutputStreamWriter(powershellProcess.getOutputStream()), true);
            powershellReader = new BufferedReader(new InputStreamReader(powershellProcess.getInputStream()));
        } catch (IOException e) {
            logger.error("Failed to start PowerShell process", e);
        }
    }

    private void loadAvailableVoices() {
        if (powershellWriter == null) return;

        try {
            String command = "Add-Type -AssemblyName System.Speech;" +
                "$speak = New-Object System.Speech.Synthesis.SpeechSynthesizer;" +
                "$speak.GetInstalledVoices() | Select-Object -ExpandProperty VoiceInfo | " +
                "Select-Object -Property Name | ConvertTo-Csv -NoTypeInformation | " +
                "Select-Object -Skip 1; echo 'END_OF_VOICES'";
            powershellWriter.println(command);

            String line;
            while ((line = powershellReader.readLine()) != null) {
                if (line.trim().equals("END_OF_VOICES")) break;
                if (!line.trim().isEmpty()) {
                    voices.add(line.replace("\"", "").trim());
                }
            }

            if (voices.isEmpty()) {
                logger.warn("No TTS voices found");
            } else {
                logger.info("Found {} TTS voices", voices.size());
            }
        } catch (Exception e) {
            logger.error("Failed to load voices", e);
        }
    }

    /**
     * PHASE 2 SECURITY: Absolute path enforcement for SoundVolumeView.exe.
     * VN-parity: Uses %ProgramFiles%/ValVoice/SoundVolumeView.exe exclusively.
     * No PATH search, no dynamic discovery - matches ValorantNarrator exactly.
     *
     * Guardrail: Checks file existence before setting path.
     * If missing → soundVolumeViewPath remains null → graceful degradation.
     */
    private void findSoundVolumeView() {
        // PHASE 2 SECURITY: Fixed absolute path - no PATH lookup
        String programFiles = System.getenv("ProgramFiles");
        if (programFiles == null) {
            logger.warn("[AudioRouting] ProgramFiles environment variable not set");
            soundVolumeViewPath = null;
            return;
        }

        String fileLocation = programFiles.replace("\\", "/") + "/ValVoice/SoundVolumeView.exe";
        File exeFile = new File(fileLocation);

        // PHASE 2 SECURITY: Existence check before storing path
        if (exeFile.exists() && exeFile.isFile()) {
            soundVolumeViewPath = fileLocation;
            logger.info("[AudioRouting] SoundVolumeView.exe found at absolute path: {}", fileLocation);
        } else {
            soundVolumeViewPath = null;
            logger.warn("[AudioRouting] SoundVolumeView.exe not found at {}", fileLocation);
            logger.warn("[AudioRouting] Audio routing disabled - TTS will play through default speakers");
        }
    }

    /**
     * PHASE 2 SECURITY: Route PowerShell TTS child process audio to VB-CABLE Input.
     * VN-parity: Uses absolute path from findSoundVolumeView() - never relies on PATH.
     * Command format: SoundVolumeView.exe /SetAppDefault "CABLE Input" all PID
     *
     * Guardrail: If soundVolumeViewPath is null (tool missing), logs warning and returns.
     * TTS continues to work but audio plays through default device instead of VB-Cable.
     */
    private void routeAudioToVbCable() {
        // PHASE 2 SECURITY: Graceful degradation if tool missing
        if (soundVolumeViewPath == null) {
            logger.warn("[AudioRouting] Skipping audio routing: SoundVolumeView.exe not available");
            audioRoutingConfigured = false;
            return;
        }

        if (powershellProcess == null || !powershellProcess.isAlive()) {
            logger.warn("[AudioRouting] Cannot route TTS audio: PowerShell process not running");
            audioRoutingConfigured = false;
            return;
        }

        try {
            long pid = powershellProcess.pid();

            // PHASE 2 SECURITY: Execute using absolute path (soundVolumeViewPath already validated)
            // Command format: SoundVolumeView.exe /SetAppDefault "CABLE Input" all PID
            String command = soundVolumeViewPath + " /SetAppDefault \"CABLE Input\" all " + pid;
            logger.debug("[AudioRouting] Executing (absolute path): {}", soundVolumeViewPath);

            Process routeProcess = Runtime.getRuntime().exec(command);
            int exitCode = routeProcess.waitFor();

            if (exitCode == 0) {
                logger.info("[AudioRouting] ✓ PowerShell TTS (PID {}) routed to CABLE Input", pid);
                audioRoutingConfigured = true;
            } else {
                logger.warn("[AudioRouting] SoundVolumeView returned exit code {} - routing may have failed", exitCode);
                audioRoutingConfigured = false;
            }

            // Configure VB-CABLE listen-through (so user can hear their own TTS)
            executeAndLog(soundVolumeViewPath, "/SetPlaybackThroughDevice", "CABLE Output", "Default Playback Device");
            executeAndLog(soundVolumeViewPath, "/SetListenToThisDevice", "CABLE Output", "1");
            executeAndLog(soundVolumeViewPath, "/unmute", "CABLE Output");

            logger.info("[AudioRouting] ✓ VB-CABLE listen-through configured");

        } catch (Exception e) {
            // PHASE 2 SECURITY: Graceful degradation - log and continue, do not crash
            logger.error("[AudioRouting] Failed to route TTS audio: {}", e.getMessage());
            audioRoutingConfigured = false;
        }
    }

    private void executeAndLog(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.start().waitFor();
        } catch (Exception e) {
            logger.debug("Command failed: {}", String.join(" ", command));
        }
    }

    /**
     * Check if audio routing was successfully configured.
     */
    public boolean isAudioRoutingConfigured() {
        return audioRoutingConfigured;
    }

    public List<String> getAvailableVoices() {
        return voices;
    }

    public boolean isReady() {
        return powershellProcess != null && powershellProcess.isAlive() && !voices.isEmpty();
    }

    /**
     * Speak text using Windows TTS. Blocks until speech completes.
     */
    public void speakInbuiltVoice(String voice, String text, short rate) {
        if (!isReady() || text == null || text.isEmpty()) return;

        // Convert rate from 0-100 UI scale to -10 to +10 SAPI scale
        short sapiRate = (short) (rate / 10.0 - 10);

        try {
            String escapedText = text.replace("'", "''");
            String sentinel = "TTS_DONE_" + System.currentTimeMillis();

            String command = String.format(
                "try { " +
                "Add-Type -AssemblyName System.Speech; " +
                "$speak = New-Object System.Speech.Synthesis.SpeechSynthesizer; " +
                "$speak.SelectVoice('%s'); " +
                "$speak.Rate = %d; " +
                "$speak.Speak('%s') " +
                "} catch { } finally { Write-Output '%s' }",
                voice, sapiRate, escapedText, sentinel);

            powershellWriter.println(command);
            logger.debug("Speaking: '{}' (voice={}, rate={})",
                text.length() > 50 ? text.substring(0, 47) + "..." : text, voice, sapiRate);

            // Wait for speech completion
            waitForSentinel(sentinel, text.length());
        } catch (Exception e) {
            logger.error("TTS failed: {}", e.getMessage());
        }
    }

    private void waitForSentinel(String sentinel, int textLength) {
        long maxWaitMs = Math.max(30000, textLength * 200);
        long startTime = System.currentTimeMillis();
        StringBuilder buffer = new StringBuilder();

        try {
            while (System.currentTimeMillis() - startTime < maxWaitMs) {
                if (powershellReader.ready()) {
                    int ch = powershellReader.read();
                    if (ch == -1) break;
                    if (ch == '\n' || ch == '\r') {
                        if (buffer.toString().contains(sentinel)) {
                            logger.debug("TTS completed in {}ms", System.currentTimeMillis() - startTime);
                            return;
                        }
                        buffer.setLength(0);
                    } else {
                        buffer.append((char) ch);
                    }
                } else {
                    Thread.sleep(50);
                }
            }
        } catch (Exception e) {
            logger.debug("Error waiting for TTS: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    // TTS API CLIENT METHODS (VN-Parity Phase 4)
    // ─────────────────────────────────────────────

    /**
     * Request TTS audio from the ValorantNarrator-agentVoices engine.
     * VN-Parity: 15s strict timeout, fail-fast on engine not ready, ConnectException → DEGRADED.
     * Phase 4 Step 2: Integrated cache layer with MD5 hashing.
     *
     * @param agent The agent voice to use (e.g., "jett", "sage", "phoenix")
     * @param text The text to synthesize
     * @return Path to the generated MP3 file (cached or freshly generated)
     * @throws IllegalStateException if TTS engine is not in READY state
     * @throws IOException if HTTP request fails or file write fails
     * @throws InterruptedException if request is interrupted
     */
    public Path requestTts(String agent, String text) throws IOException, InterruptedException {
        // === CACHE LAYER START ===
        // Step 1: Check cache first (before engine state check for cached responses)
        Path cachedFile = getCachedFile(agent, text);
        if (cachedFile != null) {
            logger.debug("[TTS Cache] HIT: {}", cachedFile.getFileName());
            return cachedFile;
        }
        // === CACHE LAYER END ===

        // Step 2: Engine state check - fail fast if not ready
        ValVoiceBackend.EngineState state = ValVoiceBackend.getInstance().getEngineState();
        if (state != ValVoiceBackend.EngineState.READY) {
            throw new IllegalStateException("TTS engine not ready (state=" + state + ")");
        }

        // Step 3: Initialize HttpClient lazily (thread-safe)
        HttpClient client = getOrCreateHttpClient();

        // Step 4: Build JSON payload safely (no string concatenation injection)
        // Phase 7: Language read from config.json for runtime configurability
        String language = ConfigManager.get().language;
        if (language == null || language.isBlank()) {
            language = DEFAULT_LANGUAGE;
        }
        String jsonPayload = buildJsonPayload(agent, text, language);

        // Step 5: Build HTTP request
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TTS_API_URL))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "audio/mpeg")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        logger.debug("[TTS API] POST /speak agent={} text='{}'",
                agent, text.length() > 30 ? text.substring(0, 27) + "..." : text);

        // Step 6: Execute request with ConnectException handling
        HttpResponse<byte[]> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (ConnectException e) {
            // VN-Parity: ConnectException = engine crashed/unreachable → permanent DEGRADED
            logger.error("[TTS API] ConnectException - marking engine as DEGRADED");
            ValVoiceBackend.getInstance().markDegraded();
            throw e; // Rethrow after marking degraded
        }

        // Step 7: Validate response status
        int statusCode = response.statusCode();
        if (statusCode != 200) {
            throw new IOException("TTS request failed with status: " + statusCode);
        }

        byte[] mp3Bytes = response.body();
        logger.debug("[TTS API] Received {} bytes", mp3Bytes.length);

        // === CACHE LAYER START ===
        // Step 8: Write to cache with atomic pattern
        Path finalFile = writeToCacheFile(agent, text, mp3Bytes);

        // Step 9: Evict old cache files if needed
        evictCacheIfNeeded();

        return finalFile;
        // === CACHE LAYER END ===
    }

    /**
     * Get or create the HttpClient instance (lazy, thread-safe).
     * VN-Parity: HTTP/1.1, 5s connect timeout, no redirects.
     */
    private HttpClient getOrCreateHttpClient() {
        if (ttsHttpClient == null) {
            synchronized (this) {
                if (ttsHttpClient == null) {
                    ttsHttpClient = HttpClient.newBuilder()
                            .version(HttpClient.Version.HTTP_1_1)
                            .connectTimeout(CONNECT_TIMEOUT)
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build();
                    logger.debug("[TTS API] HttpClient initialized (HTTP/1.1, 5s connect, 15s request timeout)");
                }
            }
        }
        return ttsHttpClient;
    }

    /**
     * Build JSON payload safely without string concatenation injection risk.
     * Uses simple escaping for JSON string values.
     */
    private String buildJsonPayload(String agent, String text, String language) {
        // Escape special JSON characters in text
        String escapedText = text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");

        String escapedAgent = agent
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");

        return String.format(
                "{\"agent\":\"%s\",\"text\":\"%s\",\"language\":\"%s\"}",
                escapedAgent, escapedText, language
        );
    }

    // === CACHE LAYER START ===

    /**
     * Compute MD5 hash of agent + text for cache key.
     * Thread-safe, deterministic.
     *
     * @param agent The agent name
     * @param text The text to synthesize
     * @return Lowercase hex string of MD5 hash
     */
    private String computeHash(String agent, String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            // Phase 7: Include language in hash so different languages produce different cache files
            String lang = ConfigManager.get().language;
            if (lang == null || lang.isBlank()) lang = DEFAULT_LANGUAGE;
            String input = agent + "|" + text + "|" + lang;
            byte[] hashBytes = md.digest(input.getBytes(StandardCharsets.UTF_8));

            // Convert to lowercase hex string
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
     * Get the cache directory path, creating it if needed.
     *
     * @return Path to %LOCALAPPDATA%\ValVoice\cache\
     * @throws IOException if directory cannot be created
     */
    private Path getCacheDirectory() throws IOException {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isEmpty()) {
            throw new IOException("LOCALAPPDATA environment variable not set");
        }

        Path cacheDir = Path.of(localAppData, CACHE_DIR_NAME);
        Files.createDirectories(cacheDir);
        return cacheDir;
    }

    /**
     * Get cached file if it exists.
     *
     * @param agent The agent name
     * @param text The text to synthesize
     * @return Path to cached .mp3 file, or null if not cached
     */
    private Path getCachedFile(String agent, String text) {
        try {
            String hash = computeHash(agent, text);
            Path cacheDir = getCacheDirectory();
            Path cachedFile = cacheDir.resolve(hash + ".mp3");

            if (Files.exists(cachedFile) && Files.isRegularFile(cachedFile)) {
                return cachedFile;
            }
        } catch (IOException e) {
            logger.debug("[TTS Cache] Error checking cache: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Write MP3 bytes to cache file using atomic pattern.
     *
     * @param agent The agent name
     * @param text The text to synthesize
     * @param mp3Bytes The MP3 audio data
     * @return Path to the final cached .mp3 file
     * @throws IOException if file operations fail
     */
    private Path writeToCacheFile(String agent, String text, byte[] mp3Bytes) throws IOException {
        String hash = computeHash(agent, text);
        Path cacheDir = getCacheDirectory();

        // Create temp file in cache directory
        Path tempFile = cacheDir.resolve(hash + ".tmp");
        Path finalFile = cacheDir.resolve(hash + ".mp3");

        try {
            // Write bytes to temp file
            Files.write(tempFile, mp3Bytes);

            // Atomic rename to .mp3
            try {
                Files.move(tempFile, finalFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (UnsupportedOperationException e) {
                // Fallback to regular move if atomic not supported
                Files.move(tempFile, finalFile, StandardCopyOption.REPLACE_EXISTING);
            }

            logger.debug("[TTS Cache] WRITE: {} ({} bytes)", finalFile.getFileName(), mp3Bytes.length);
            return finalFile;

        } catch (IOException e) {
            // Cleanup temp file on failure
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    /**
     * Evict oldest cache files if total size exceeds 100MB.
     * Deletes by lastModified (oldest first).
     * Fails silently if deletion fails (file may be in use).
     */
    private void evictCacheIfNeeded() {
        try {
            Path cacheDir = getCacheDirectory();

            // List all .mp3 files (ignore .tmp)
            List<Path> mp3Files;
            try (Stream<Path> stream = Files.list(cacheDir)) {
                mp3Files = stream
                        .filter(p -> p.toString().endsWith(".mp3"))
                        .filter(Files::isRegularFile)
                        .toList();
            }

            // Calculate total size
            long totalSize = 0;
            for (Path file : mp3Files) {
                try {
                    totalSize += Files.size(file);
                } catch (IOException ignored) {
                }
            }

            // If under limit, nothing to do
            if (totalSize <= MAX_CACHE_SIZE_BYTES) {
                return;
            }

            logger.debug("[TTS Cache] Evicting: total size {}MB exceeds 100MB limit",
                    totalSize / (1024 * 1024));

            // Sort by lastModified ascending (oldest first)
            List<Path> sortedFiles = new ArrayList<>(mp3Files);
            sortedFiles.sort(Comparator.comparingLong(p -> {
                try {
                    return Files.getLastModifiedTime(p).toMillis();
                } catch (IOException e) {
                    return 0L;
                }
            }));

            // Delete oldest until under limit
            for (Path file : sortedFiles) {
                if (totalSize <= MAX_CACHE_SIZE_BYTES) {
                    break;
                }

                try {
                    long fileSize = Files.size(file);
                    Files.delete(file);
                    totalSize -= fileSize;
                    logger.debug("[TTS Cache] Evicted: {} ({} bytes)", file.getFileName(), fileSize);
                } catch (IOException e) {
                    // File may be locked by MediaPlayer - skip silently
                    logger.debug("[TTS Cache] Could not delete {} (may be in use)", file.getFileName());
                }
            }

        } catch (IOException e) {
            // Eviction is best-effort - log and continue
            logger.debug("[TTS Cache] Eviction error: {}", e.getMessage());
        }
    }

    // === CACHE LAYER END ===

    /**
     * Write MP3 bytes to temp file using atomic move pattern.
     * VN-Parity: Prevents partial MP3 corruption on crash/interrupt.
     *
     * @param mp3Bytes The MP3 audio data
     * @return Path to the final .mp3 file
     * @throws IOException if file operations fail
     */
    private Path writeToTempFile(byte[] mp3Bytes) throws IOException {
        // Create temp directory if needed: %TEMP%/ValVoice/tmp/
        Path tempDir = Path.of(System.getProperty("java.io.tmpdir"), TTS_TEMP_DIR);
        Files.createDirectories(tempDir);

        // Create temp file with .tmp extension
        Path tempFile = Files.createTempFile(tempDir, "tts_", ".tmp");

        try {
            // Write bytes to temp file
            Files.write(tempFile, mp3Bytes);

            // Atomic rename to .mp3 (prevents partial file if crash during write)
            Path mp3File = tempFile.resolveSibling(
                    tempFile.getFileName().toString().replace(".tmp", ".mp3"));

            try {
                // Try atomic move first (preferred)
                Files.move(tempFile, mp3File, StandardCopyOption.ATOMIC_MOVE);
            } catch (UnsupportedOperationException e) {
                // Fallback to regular move if atomic not supported
                Files.move(tempFile, mp3File, StandardCopyOption.REPLACE_EXISTING);
            }

            logger.debug("[TTS API] Wrote {} bytes to {}", mp3Bytes.length, mp3File.getFileName());
            return mp3File;

        } catch (IOException e) {
            // Cleanup temp file on failure
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    // ─────────────────────────────────────────────
    // TTS QUEUE METHODS (VN-Parity Phase 4 Step 3)
    // ─────────────────────────────────────────────

    /**
     * Background consumer thread that processes TTS requests sequentially.
     * VN-Parity: No retries, no reconnect, permanent degrade on ConnectException.
     * Phase 5: Integrated MediaPlayer playback - blocks until audio finishes.
     * Phase 6: Routes to SAPI fallback when engine is DEGRADED.
     * Phase 6 Step 3: Opportunistic recovery with probe cooldown.
     */
    private void processQueue() {
        while (true) {
            try {
                // Blocking take - waits for next request
                TtsRequest req = ttsQueue.take();

                try {
                    // Phase 6: Route to appropriate engine based on current state
                    ValVoiceBackend.EngineState state = ValVoiceBackend.getInstance().getEngineState();
                    Path audioFile = null;

                    // ═══════════════════════════════════════════════════════
                    // PHASE 6 STEP 3: Opportunistic Recovery (Cooldown Protected)
                    // Probe backend at most once every 10 seconds to avoid latency
                    // Must execute BEFORE useXtts is computed so recovery takes effect immediately
                    // ═══════════════════════════════════════════════════════
                    if (state == ValVoiceBackend.EngineState.DEGRADED) {
                        long now = System.currentTimeMillis();

                        if (now - lastProbeTime > PROBE_COOLDOWN_MS) {
                            lastProbeTime = now;

                            if (probeEngine()) {
                                logger.info("[Recovery] XTTS backend detected on port 5005 — restoring READY state");
                                ValVoiceBackend.getInstance().setEngineReady();
                                state = ValVoiceBackend.EngineState.READY;
                            } else {
                                logger.debug("[Recovery] Probe failed, backend still offline. Remaining in DEGRADED state.");
                            }
                        }
                    }

                    // ═══════════════════════════════════════════════════════
                    // PHASE 7: Config-driven engine routing
                    // Computed AFTER recovery probe so restored state is reflected
                    // ═══════════════════════════════════════════════════════
                    boolean useXtts = ConfigManager.get().xttsEnabled && state == ValVoiceBackend.EngineState.READY;

                    if (useXtts) {
                        // Primary path: XTTS backend
                        audioFile = requestTts(req.agent, req.text);
                        logger.debug("[TTS Queue] XTTS processed: agent={} text='{}'",
                                req.agent, req.text.length() > 20 ? req.text.substring(0, 17) + "..." : req.text);

                    } else if (ConfigManager.get().sapiFallbackEnabled) {
                        // Fallback path: Windows SAPI (only if enabled in config)
                        Path cacheDir = java.nio.file.Paths.get(
                                System.getenv("LOCALAPPDATA"), "ValVoice", "cache");
                        audioFile = SapiVoiceEngine.generateFallbackAudio(req.text, cacheDir);
                        logger.debug("[TTS Queue] SAPI fallback processed: text='{}'",
                                req.text.length() > 20 ? req.text.substring(0, 17) + "..." : req.text);

                    } else {
                        // Phase 7: Both XTTS and SAPI disabled/unavailable — drop request
                        logger.warn("[TTS] XTTS disabled and SAPI fallback disabled. Dropping request.");
                        continue;
                    }

                    // ═══════════════════════════════════════════════════════
                    // PHASE 6 STEP 2: Queue Stability - Never freeze on null
                    // ═══════════════════════════════════════════════════════
                    if (audioFile == null) {
                        logger.debug("[TTS Queue] Audio generation returned null, skipping playback");
                        continue;  // Critical: ensure queue always continues
                    }

                    // Play audio - blocks until playback finishes
                    playAudio(audioFile);

                } catch (ConnectException e) {
                    // STRICT VN PARITY: Permanent degrade for session
                    logger.error("[TTS Queue] ConnectException - marking engine as DEGRADED");
                    ValVoiceBackend.getInstance().markDegraded();
                    // Do NOT rethrow - continue consuming queue (will use SAPI fallback next iteration)
                } catch (IllegalStateException e) {
                    // Engine not ready - expected during state transitions
                    logger.debug("[TTS Queue] Engine not ready: {}", e.getMessage());
                } catch (Exception e) {
                    // Log and continue - never crash the consumer
                    logger.warn("[TTS Queue] Processing error: {}", e.getMessage());
                }

            } catch (InterruptedException e) {
                // Graceful shutdown
                Thread.currentThread().interrupt();
                logger.debug("[TTS Queue] Consumer thread interrupted, exiting");
                break;
            }
        }
    }

    // ─────────────────────────────────────────────
    // PTT METHODS (VN-Parity Phase 5 Step 2)
    // ─────────────────────────────────────────────

    /**
     * Press PTT key using native Windows SendInput API.
     * VN-Parity: Direct native call, 50ms pre-open delay, thread-safe guard.
     */
    private void pressPtt() {
        // Phase 7: Apply configured PTT key from config.json
        configuredPttKey = resolveVirtualKey(ConfigManager.get().pttKey);

        // Guard against double press
        if (!isPttPressed.compareAndSet(false, true)) {
            logger.debug("[PTT] Already pressed, skipping");
            return;
        }

        try {
            // Build INPUT structure for key DOWN
            User32.INPUT input = new User32.INPUT();
            input.type = User32.INPUT_KEYBOARD;
            input.input = new User32.INPUT.INPUT_UNION();
            input.input.ki = new User32.KEYBDINPUT();
            input.input.ki.wVk = configuredPttKey;
            input.input.ki.wScan = 0;
            input.input.ki.dwFlags = 0; // 0 = key down
            input.input.ki.time = 0;
            input.input.ki.dwExtraInfo = null;
            input.input.setType(User32.KEYBDINPUT.class);

            // Send key down event
            int result = User32.INSTANCE.SendInput(1, new User32.INPUT[]{input}, input.size());
            if (result == 1) {
                logger.debug("[PTT] Key DOWN sent (vk=0x{})", Integer.toHexString(configuredPttKey));
            } else {
                logger.warn("[PTT] SendInput failed for key DOWN");
            }

            // VN-Parity: 50ms pre-open mic delay
            Thread.sleep(50);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.debug("[PTT] Pre-delay interrupted");
        } catch (Exception e) {
            logger.error("[PTT] Press error: {}", e.getMessage());
            // Ensure state is reset on error
            isPttPressed.set(false);
        }
    }

    /**
     * Release PTT key using native Windows SendInput API.
     * VN-Parity: Always safe to call, idempotent, never throws.
     */
    private void releasePtt() {
        // Only release if actually pressed
        if (!isPttPressed.compareAndSet(true, false)) {
            logger.debug("[PTT] Not pressed, skipping release");
            return;
        }

        try {
            // Build INPUT structure for key UP
            User32.INPUT input = new User32.INPUT();
            input.type = User32.INPUT_KEYBOARD;
            input.input = new User32.INPUT.INPUT_UNION();
            input.input.ki = new User32.KEYBDINPUT();
            input.input.ki.wVk = configuredPttKey;
            input.input.ki.wScan = 0;
            input.input.ki.dwFlags = User32.KEYEVENTF_KEYUP; // 0x0002 = key up
            input.input.ki.time = 0;
            input.input.ki.dwExtraInfo = null;
            input.input.setType(User32.KEYBDINPUT.class);

            // Send key up event
            int result = User32.INSTANCE.SendInput(1, new User32.INPUT[]{input}, input.size());
            if (result == 1) {
                logger.debug("[PTT] Key UP sent (vk=0x{})", Integer.toHexString(configuredPttKey));
            } else {
                logger.warn("[PTT] SendInput failed for key UP");
            }

        } catch (Exception e) {
            logger.error("[PTT] Release error: {}", e.getMessage());
            // Never throw - this is a cleanup operation
        }
    }

    /**
     * Play MP3 audio file using JavaFX MediaPlayer.
     * Blocks until playback completes (sequential VN parity).
     * Phase 5: Always creates new MediaPlayer, always disposes after playback.
     *
     * Hardened for production:
     * - Handles JavaFX not initialized
     * - Handles all MediaPlayer terminal states (end, error, stopped, halted)
     * - Timeout safety (max 60s per clip)
     * - Guaranteed dispose in all paths
     *
     * @param mp3File Path to the MP3 file to play
     */
    private void playAudio(Path mp3File) {
        if (mp3File == null || !Files.exists(mp3File)) {
            logger.warn("[TTS Audio] MP3 file not found: {}", mp3File);
            return;
        }

        CountDownLatch latch = new CountDownLatch(1);

        // Flag to track if latch was counted down inside Platform.runLater
        final boolean[] scheduled = {false};

        try {
            // ═══════════════════════════════════════════════════════
            // PHASE 5 STEP 2: PTT KEY DOWN BEFORE PLAYBACK
            // ═══════════════════════════════════════════════════════
            pressPtt();

            Platform.runLater(() -> {
                scheduled[0] = true;
                MediaPlayer mediaPlayer = null;
                try {
                    String mediaUri = mp3File.toUri().toString();
                    Media media = new Media(mediaUri);
                    mediaPlayer = new MediaPlayer(media);

                    // Phase 7: Apply configured playback volume
                    mediaPlayer.setVolume(ConfigManager.get().playbackVolume);

                    final MediaPlayer player = mediaPlayer;

                    // Success handler
                    player.setOnEndOfMedia(() -> {
                        logger.debug("[TTS Audio] Playback finished: {}", mp3File.getFileName());
                        player.dispose();
                        latch.countDown();
                    });

                    // Error handler
                    player.setOnError(() -> {
                        logger.error("[TTS Audio] Playback error: {}",
                                player.getError() != null ? player.getError().getMessage() : "unknown");
                        player.dispose();
                        latch.countDown();
                    });

                    // Stopped handler (external stop or very short clips)
                    player.setOnStopped(() -> {
                        logger.debug("[TTS Audio] Playback stopped: {}", mp3File.getFileName());
                        player.dispose();
                        latch.countDown();
                    });

                    // Halted handler (unrecoverable error state)
                    player.setOnHalted(() -> {
                        logger.warn("[TTS Audio] Playback halted: {}", mp3File.getFileName());
                        player.dispose();
                        latch.countDown();
                    });

                    logger.debug("[TTS Audio] Playing: {}", mp3File.getFileName());
                    player.play();

                } catch (Exception e) {
                    logger.error("[TTS Audio] Failed to create MediaPlayer: {}", e.getMessage());
                    if (mediaPlayer != null) {
                        mediaPlayer.dispose();
                    }
                    latch.countDown();
                }
            });

            // Block until playback finishes (or error occurs)
            // Timeout safety: max 60 seconds per clip to prevent permanent blocking
            boolean completed = latch.await(60, java.util.concurrent.TimeUnit.SECONDS);
            if (!completed) {
                logger.warn("[TTS Audio] Playback timeout (60s) for: {}", mp3File.getFileName());
            }

        } catch (IllegalStateException e) {
            // JavaFX toolkit not initialized - cannot play audio
            logger.error("[TTS Audio] JavaFX not initialized, cannot play audio: {}", e.getMessage());
            // Latch was never scheduled, no need to countdown
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.debug("[TTS Audio] Playback interrupted");
        } catch (Exception e) {
            logger.error("[TTS Audio] Unexpected error: {}", e.getMessage());
        } finally {
            // ═══════════════════════════════════════════════════════
            // PHASE 5 STEP 2: PTT KEY UP AFTER PLAYBACK (FAILSAFE)
            // VN-Parity: 100ms tail delay, always executes
            // ═══════════════════════════════════════════════════════
            try {
                Thread.sleep(100); // VN strict tail delay
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            releasePtt(); // Always releases, even if playback failed
        }
    }

    /**
     * Enqueue a TTS request for sequential processing.
     * VN-Parity: Graceful routing based on engine state.
     * - READY: Queue for XTTS processing
     * - DEGRADED: Queue for SAPI fallback processing
     * - STOPPED/STARTING: Drop silently
     *
     * Phase 6: Both READY and DEGRADED states now enter the queue.
     * The consumer thread decides which engine to use based on current state.
     *
     * @param agent The agent voice to use
     * @param text The text to synthesize
     */
    public void enqueueTts(String agent, String text) {
        ValVoiceBackend.EngineState state = ValVoiceBackend.getInstance().getEngineState();

        switch (state) {
            case READY, DEGRADED -> {
                // Phase 6: Both READY and DEGRADED enter the queue
                // Consumer thread will route to XTTS or SAPI based on state at processing time
                boolean queued = ttsQueue.offer(new TtsRequest(agent, text));
                if (!queued) {
                    logger.debug("[TTS Queue] Queue full, dropping newest request: '{}'",
                            text.length() > 30 ? text.substring(0, 27) + "..." : text);
                } else {
                    String engine = (state == ValVoiceBackend.EngineState.DEGRADED) ? "SAPI" : "XTTS";
                    logger.debug("[TTS Queue] Enqueued for {}: agent={} text='{}'",
                            engine, agent, text.length() > 20 ? text.substring(0, 17) + "..." : text);
                }
            }
            case STOPPED, STARTING, STOPPING -> {
                // Engine not ready - drop silently
                logger.debug("[TTS Queue] Engine not ready (state={}), dropping request: '{}'",
                        state, text.length() > 30 ? text.substring(0, 27) + "..." : text);
            }
        }
        // CRITICAL: Never throw, never block, never crash caller
    }

    public void shutdown() {
        try {
            // ═══════════════════════════════════════════════════════
            // PHASE 5 STEP 2: PTT FAILSAFE ON SHUTDOWN
            // Ensure PTT key is never left stuck down
            // ═══════════════════════════════════════════════════════
            if (isPttPressed.get()) {
                logger.warn("[PTT] Failsafe: Releasing stuck PTT key during shutdown");
                releasePtt();
            }

            if (powershellWriter != null) {
                powershellWriter.println("exit");
                powershellWriter.close();
            }
            if (powershellReader != null) powershellReader.close();
            if (powershellProcess != null && powershellProcess.isAlive()) powershellProcess.destroy();
            logger.debug("TTS synthesizer shutdown complete");
        } catch (Exception e) {
            logger.debug("Error during shutdown", e);
        }
    }
}
