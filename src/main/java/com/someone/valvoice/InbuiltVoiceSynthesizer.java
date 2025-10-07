package com.someone.valvoice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Persistent PowerShell-based Windows voice synthesizer using System.Speech.
 *
 * Responsibilities:
 *  - Launch a reusable hidden PowerShell process (-NoExit) for lower latency voice playback.
 *  - Enumerate installed System.Speech voices (friendly names) on initialization.
 *  - Provide simple speak method with selectable voice + rate conversion (0..100 UI -> -10..10 SAPI range).
 *  - (Best effort) route audio through VB-CABLE if SoundVolumeView.exe is present (mirrors original logic).
 *
 * Notes:
 *  - This is Windows-only. Guard usages with OS checks.
 *  - Output from speak commands isn't consumed (they produce none on success). We only read during enumeration.
 *  - If the persistent process dies, further speaks will be ignored; could be enhanced with auto-restart logic.
 */
public class InbuiltVoiceSynthesizer {
    private static final Logger logger = LoggerFactory.getLogger(InbuiltVoiceSynthesizer.class);
    private static final String END_SENTINEL = "END_OF_VOICES";

    private Process powershellProcess;
    private PrintWriter psWriter;
    private BufferedReader psReader;
    private final List<String> voices = new ArrayList<>();
    private final AtomicBoolean ready = new AtomicBoolean(false);

    public InbuiltVoiceSynthesizer() {
        if (!isWindows()) {
            logger.warn("InbuiltVoiceSynthesizer initialized on non-Windows OS; disabled");
            return;
        }
        try {
            // Start persistent PowerShell session. Using "-" trick with -Command to keep session open.
            powershellProcess = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass", "-NoExit", "-Command", "-")
                    .redirectErrorStream(true)
                    .start();
            psWriter = new PrintWriter(new OutputStreamWriter(powershellProcess.getOutputStream(), StandardCharsets.UTF_8), true);
            psReader = new BufferedReader(new InputStreamReader(powershellProcess.getInputStream(), StandardCharsets.UTF_8));
            enumerateVoices();
            configureAppAudioRoute();
            if (!voices.isEmpty()) {
                speakInbuiltVoice(voices.get(0), "Inbuilt voice synthesizer initialized.", 50);
            }
            ready.set(true);
        } catch (IOException e) {
            logger.error("Failed to start persistent PowerShell for inbuilt synthesizer", e);
            cleanup();
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private void enumerateVoices() {
        try {
            String command = String.join(";",
                    "Add-Type -AssemblyName System.Speech",
                    "$speak = New-Object System.Speech.Synthesis.SpeechSynthesizer",
                    // Convert to CSV to get clean lines; skip header
                    "$speak.GetInstalledVoices() | Select-Object -ExpandProperty VoiceInfo | Select-Object -Property Name | ConvertTo-Csv -NoTypeInformation | Select-Object -Skip 1",
                    "echo '" + END_SENTINEL + "'"
            );
            send(command);
            String line;
            while ((line = psReader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.equals(END_SENTINEL)) break;
                if (!trimmed.isEmpty()) {
                    // CSV line => "VoiceName"; remove quotes if present
                    voices.add(trimmed.replace("\"", "").trim());
                }
            }
            if (voices.isEmpty()) {
                logger.warn("No inbuilt voices found via System.Speech enumeration");
            } else {
                logger.info("InbuiltVoiceSynthesizer detected {} voices", voices.size());
            }
        } catch (Exception e) {
            logger.error("Error enumerating inbuilt voices", e);
        }
    }

    private void configureAppAudioRoute() {
        try {
            String programFiles = System.getenv("ProgramFiles");
            if (programFiles == null) return;
            File svv = new File(programFiles, "ValorantNarrator/ SoundVolumeView.exe".replace(" ", "")); // original path logic had a space; normalize
            if (!svv.exists()) {
                // Try alternative without folder name mismatch
                svv = new File(programFiles, "ValorantNarrator/SoundVolumeView.exe");
            }
            if (!svv.exists()) {
                logger.debug("SoundVolumeView.exe not found; skipping VB-CABLE routing");
                return;
            }
            if (powershellProcess == null) return;
            long pid = powershellProcess.pid();
            // Route to CABLE Input if installed; swallow failures
            String cmd = '"' + svv.getAbsolutePath() + '"' + " /SetAppDefault \"CABLE Input\" all " + pid;
            Runtime.getRuntime().exec(cmd);
            logger.info("Attempted audio routing of PowerShell PID {} via SoundVolumeView", pid);
        } catch (Exception e) {
            logger.warn("Audio routing (SoundVolumeView) failed", e);
        }
    }

    private synchronized void send(String psCommand) {
        if (psWriter == null) return;
        psWriter.println(psCommand);
        psWriter.flush();
    }

    public boolean isReady() { return ready.get(); }

    /**
     * Return immutable list of discovered voices.
     */
    public List<String> getAvailableVoices() {
        return Collections.unmodifiableList(voices);
    }

    /**
     * Speak text using the persistent System.Speech synthesizer session.
     * @param voice exact Name as returned by getAvailableVoices(); null => default voice
     * @param text phrase to speak
     * @param uiRate 0..100 UI slider value (converted to -10..10 SAPI rate)
     */
    public void speakInbuiltVoice(String voice, String text, int uiRate) {
        if (text == null || text.isBlank()) return;
        if (psWriter == null) {
            logger.debug("speakInbuiltVoice called but PowerShell not initialized");
            return;
        }
        // Convert UI 0..100 => -10..10 (integer)
        int sapiRate = (int) Math.round((uiRate / 100.0) * 20.0 - 10.0);
        sapiRate = Math.max(-10, Math.min(10, sapiRate));
        String safeText = escape(text);
        String select = (voice != null && !voice.isBlank())
                ? String.format("$speak.SelectVoice('%s');", escape(voice))
                : "";
        String cmd = String.format(
                "Add-Type -AssemblyName System.Speech;$speak = New-Object System.Speech.Synthesis.SpeechSynthesizer;" +
                "%s$speak.Rate=%d;$speak.Speak('%s');",
                select, sapiRate, safeText);
        try {
            send(cmd);
        } catch (Exception e) {
            logger.warn("Failed to speak text via inbuilt synthesizer", e);
        }
    }

    private String escape(String s) {
        return s.replace("'", "''").replace("\n", " ").replace("\r", " ");
    }

    /**
     * Stop the persistent PowerShell process.
     */
    public void shutdown() {
        cleanup();
    }

    private void cleanup() {
        ready.set(false);
        if (psWriter != null) {
            try { psWriter.println("exit"); psWriter.flush(); } catch (Exception ignored) {}
        }
        closeQuiet(psReader);
        if (powershellProcess != null) {
            try { powershellProcess.destroyForcibly(); } catch (Exception ignored) {}
        }
        psWriter = null;
        psReader = null;
        powershellProcess = null;
    }

    private void closeQuiet(Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (IOException ignored) {}
    }
}

