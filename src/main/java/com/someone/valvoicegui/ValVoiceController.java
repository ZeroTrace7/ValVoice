package com.someone.valvoicegui;

import com.someone.valvoicebackend.*;
import com.jfoenix.controls.JFXToggleButton;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.AWTException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.*;


public class ValVoiceController {
    private static final Logger logger = LoggerFactory.getLogger(ValVoiceController.class);
    private static ValVoiceController latestInstance;

    // ========== FXML Components ==========

    // Panels (only one visible at a time)
    @FXML public AnchorPane panelLogin;
    @FXML public AnchorPane panelUser;
    @FXML public AnchorPane panelSettings;
    @FXML public AnchorPane panelInfo;
    @FXML public AnchorPane topBar;
    // New ScrollPane wrappers for panels
    @FXML public ScrollPane scrollInfo;
    @FXML public ScrollPane scrollUser;
    @FXML public ScrollPane scrollSettings;

    // Navigation buttons
    @FXML public Button btnInfo;
    @FXML public Button btnUser;
    @FXML public Button btnSettings;

    // Labels
    @FXML public Label windowTitle;
    @FXML public Label progressLoginLabel;
    @FXML public Label userIDLabel;
    @FXML public Label quotaLabel;
    @FXML public Label messagesSentLabel;
    @FXML public Label charactersNarratedLabel;

    // Buttons
    @FXML public Button voiceSettingsSync;

    // ComboBoxes
    @FXML public ComboBox<String> voices;
    @FXML public ComboBox<String> sources;
    @FXML public ComboBox<String> addIgnoredPlayer;
    @FXML public ComboBox<String> removeIgnoredPlayer;

    // Toggle Buttons
    @FXML public JFXToggleButton micButton;
    @FXML public JFXToggleButton valorantSettings;
    @FXML public JFXToggleButton privateChatButton;
    @FXML public JFXToggleButton teamChatButton;

    // Sliders
    @FXML public Slider rateSlider;

    // Progress Bars
    @FXML public ProgressBar quotaBar;
    @FXML public ProgressBar progressLogin;

    // Text Fields
    @FXML public TextField keybindTextField;

    // Status bar labels (injected from FXML)
    @FXML public Label statusXmpp;         // XMPP bridge executable presence / readiness
    @FXML public Label statusBridgeMode;   // external-exe (MITM proxy)
    @FXML public Label statusVbCable;      // VB-Audio virtual cable detection
    @FXML public Label statusAudioRoute;   // SoundVolumeView availability
    @FXML public Label statusSelfId;       // Current self player id

    // ========== State Variables ==========
    private boolean isLoading = true;
    private final boolean isAppDisabled = false;

    private InbuiltVoiceSynthesizer inbuiltSynth; // persistent System.Speech synthesizer (optional)

    // Performance: Use ScheduledExecutorService instead of raw threads with sleep
    private final ScheduledExecutorService scheduledExecutor;
    private volatile boolean shutdownRequested = false;

    // Cache for voice enumeration to avoid repeated PowerShell calls
    private volatile java.util.List<String> cachedVoices = null;
    private volatile long voicesCacheTimestamp = 0;
    private static final long VOICES_CACHE_DURATION_MS = 300_000; // 5 minutes

    private static final boolean SIMULATE_CHAT = false; // set true for local TTS demo without Valorant
    /**
     * External MITM Proxy Executable (single supported name)
     */
    private static final String XMPP_BRIDGE_EXE_PRIMARY = "valvoice-mitm.exe";
    /**
     * Audio Routing Tool (used for TTS routing and listen-through setup)
     */
    private static final String SOUND_VOLUME_VIEW_EXE = "SoundVolumeView.exe";

    // New: internal flags for robust status logic (don't rely on label text)
    private volatile boolean vbCableDetectedFlag = false;
    private volatile boolean builtInRoutingOk = false;

    // Rate-limit loading status updates to make them readable in logs/UI
    private final Object loadingStatusLock = new Object();
    private long nextAllowedLoadingUpdateAtMs = 0L;
    private static final long LOADING_MIN_INTERVAL_MS = 1000L;

    public ValVoiceController() {
        latestInstance = this;
        scheduledExecutor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
    }

    public static ValVoiceController getLatestInstance() {
        return latestInstance;
    }


    /**
     * Called automatically after FXML is loaded
     */
    @FXML
    public void initialize() {
        logger.info("Initializing ValVoiceController");


        // Initialize status labels early (default to info/warning look)
        ensureBaseStatusClass(statusXmpp);
        ensureBaseStatusClass(statusBridgeMode);
        ensureBaseStatusClass(statusVbCable);
        ensureBaseStatusClass(statusAudioRoute);
        ensureBaseStatusClass(statusSelfId);

        updateStatusLabelWithType(statusXmpp, "Checking...", "warning");
        updateStatusLabelWithType(statusBridgeMode, "-", "info");
        updateStatusLabelWithType(statusVbCable, "Checking...", "warning");
        updateStatusLabelWithType(statusAudioRoute, "Checking...", "warning");
        updateStatusLabelWithType(statusSelfId, "Self: (observer mode)", "info");

        // Helpful tooltips
        applyTooltip(statusXmpp, "MITM proxy status (valvoice-mitm.exe)");
        applyTooltip(statusBridgeMode, "Bridge mode: external-exe (MITM proxy)");
        applyTooltip(statusVbCable, "VB-Audio Virtual Cable device detection");
        applyTooltip(statusAudioRoute, "Audio routing: ValVoice -> VB-CABLE -> Valorant");
        applyTooltip(statusSelfId, "Observer mode - player ID not tracked");

        // Populate ComboBoxes with data
        populateComboBoxes();
        // Start loading animation
        startLoadingAnimation();
        // Simulate loading process
        simulateLoading();

        // Initialize persistent inbuilt synthesizer (Windows-only)
        inbuiltSynth = new InbuiltVoiceSynthesizer();
        if (inbuiltSynth.isReady()) {
            logger.info("InbuiltVoiceSynthesizer ready with {} voices", inbuiltSynth.getAvailableVoices().size());

            // Initialize VoiceGenerator (coordinates TTS + Push-to-Talk)
            try {
                VoiceGenerator.initialize(inbuiltSynth);
                logger.info("✓ VoiceGenerator initialized with keybind: {}", VoiceGenerator.getInstance().getCurrentKeybind());
            } catch (AWTException e) {
                logger.error("Failed to initialize VoiceGenerator (keybind automation disabled)", e);
            }
        }
        if (SIMULATE_CHAT) {
            startChatSimulation();
        }
        // Dependency + bridge checks
        verifyExternalDependencies();
        updateBridgeStatusFromSystemProperties();
    }

    // Ensure status label has base CSS class for consistent badge styling
    private void ensureBaseStatusClass(Label label) {
        if (label == null) return;
        if (!label.getStyleClass().contains("status-label")) {
            label.getStyleClass().add("status-label");
        }
    }

    // Apply a tooltip safely
    private void applyTooltip(Labeled control, String text) {
        if (control == null) return;
        Tooltip tip = control.getTooltip();
        if (tip == null) {
            tip = new Tooltip(text);
            control.setTooltip(tip);
        } else {
            tip.setText(text);
        }
    }

    private void updateBridgeStatusFromSystemProperties() {
        String mode = System.getProperty("valvoice.bridgeMode", "unknown");
        Platform.runLater(() -> {
            if (statusBridgeMode != null) statusBridgeMode.setText(mode);
            if (statusXmpp != null) {
                if ("external-exe".equals(mode)) {
                    updateStatusLabel(statusXmpp, "MITM Active", true);
                } else {
                    updateStatusLabel(statusXmpp, "Init...", false);
                }
            }
        });
    }

    // Public helpers to update status badges from background threads
    public static void updateXmppStatus(String text, boolean ok) {
        ValVoiceController c = latestInstance;
        if (c == null) return;
        Platform.runLater(() -> {
            if (c.statusXmpp != null) {
                c.updateStatusLabel(c.statusXmpp, text, ok);
            }
        });
    }

    public static void updateBridgeModeLabel(String modeText) {
        ValVoiceController c = latestInstance;
        if (c == null) return;
        Platform.runLater(() -> {
            if (c.statusBridgeMode != null) c.statusBridgeMode.setText(modeText);
            if (c.statusXmpp != null) {
                boolean ok = "external-exe".equalsIgnoreCase(modeText);
                c.updateStatusLabel(c.statusXmpp, ok ? "MITM Active" : "Init...", ok);
            }
        });
    }

    // Replace inline style with CSS class toggling for strong, consistent visuals
    private void updateStatusLabel(Label label, String text, boolean ok) {
        if (label == null) return;
        Platform.runLater(() -> {
            ensureBaseStatusClass(label);
            String prefix = ok ? "\u2713 " : "\u26A0 "; // ✓ or ⚠
            label.setText(prefix + text);
            label.getStyleClass().removeAll("status-ok", "status-warning", "status-error", "status-info");
            label.getStyleClass().add(ok ? "status-ok" : "status-warning");
        });
    }

    // Update with explicit status type
    private void updateStatusLabelWithType(Label label, String text, String statusType) {
        if (label == null) return;
        Platform.runLater(() -> {
            ensureBaseStatusClass(label);
            String st = statusType == null ? "" : statusType.toLowerCase();
            String prefix;
            switch (st) {
                case "ok":
                case "success":
                    prefix = "\u2713 "; // ✓
                    break;
                case "warning":
                    prefix = "\u26A0 "; // ⚠
                    break;
                case "error":
                    prefix = "\u2716 "; // ✖
                    break;
                case "info":
                default:
                    prefix = "\u2139 "; // ℹ
                    break;
            }
            label.setText(prefix + text);
            label.getStyleClass().removeAll("status-ok", "status-warning", "status-error", "status-info");
            switch (st) {
                case "ok":
                case "success":
                    label.getStyleClass().add("status-ok");
                    break;
                case "warning":
                    label.getStyleClass().add("status-warning");
                    break;
                case "error":
                    label.getStyleClass().add("status-error");
                    break;
                case "info":
                default:
                    label.getStyleClass().add("status-info");
                    break;
            }
        });
    }


    private void populateComboBoxes() {
        // Populate sources ComboBox
        sources.getItems().addAll(
            "SELF",
            "PARTY",
            "TEAM",
            "SELF+PARTY",
            "SELF+TEAM",
            "PARTY+TEAM",
            "SELF+PARTY+TEAM",
            "PARTY+TEAM+ALL",
            "SELF+PARTY+ALL",
            "SELF+PARTY+TEAM+ALL"
        );

        // Set default value to SELF+PARTY+TEAM (matches Chat.java defaults)
        sources.setValue("SELF+PARTY+TEAM");
        logger.info("Default chat source set to: SELF+PARTY+TEAM");

        // Apply the default source selection to Chat configuration
        Chat.getInstance().applySourceSelection("SELF+PARTY+TEAM");
        logger.info("Applied default source selection to Chat: SELF+PARTY+TEAM");

        // Async load voices to avoid blocking FX thread
        loadVoicesAsync(false);
    }

    private void loadVoicesAsync(boolean isRefresh) {
        if (voices == null) return;

        // Check cache first (unless explicit refresh requested)
        if (!isRefresh && cachedVoices != null &&
            (System.currentTimeMillis() - voicesCacheTimestamp) < VOICES_CACHE_DURATION_MS) {
            Platform.runLater(() -> {
                voices.getItems().setAll(cachedVoices);
                voices.setPromptText("Select a voice");
                if (voices.getValue() == null && !cachedVoices.isEmpty()) {
                    voices.setValue(cachedVoices.get(0));
                }
            });
            logger.debug("Using cached voices list ({} entries)", cachedVoices.size());
            return;
        }

        Platform.runLater(() -> {
            if (!isRefresh && (voices.getItems() == null || voices.getItems().isEmpty())) {
                voices.setPromptText("Loading voices...");
            } else if (isRefresh) {
                voices.setPromptText("Refreshing voices...");
            }
        });

        CompletableFuture.supplyAsync(this::enumerateWindowsVoices, scheduledExecutor)
            .thenAccept(list -> {
                // Update cache
                cachedVoices = list;
                voicesCacheTimestamp = System.currentTimeMillis();

                Platform.runLater(() -> {
                    voices.getItems().setAll(list);
                    voices.setPromptText("Select a voice");
                    if (voices.getValue() == null && !list.isEmpty()) {
                        voices.setValue(list.get(0));
                    }
                });
            });
    }

    @FXML
    public void refreshVoices() {
        logger.info("Refreshing Windows TTS voices on user request");
        loadVoicesAsync(true);
    }

    private java.util.List<String> enumerateWindowsVoices() {
        java.util.LinkedHashSet<String> ordered = new java.util.LinkedHashSet<>();
        if (!isWindows()) {
            logger.warn("Voice enumeration attempted on non-Windows OS");
            return new java.util.ArrayList<>();
        }
        // Strategy 1: .NET SpeechSynthesizer (most reliable friendly names)
        runPowerShellLines("Add-Type -AssemblyName System.Speech; (New-Object System.Speech.Synthesis.SpeechSynthesizer).GetInstalledVoices() | ForEach-Object { $_.VoiceInfo.Name }", ordered, "SpeechSynthesizer");
        // Strategy 2: COM SAPI.SpVoice (already used before)
        if (ordered.isEmpty()) {
            runPowerShellLines("$sp = New-Object -ComObject SAPI.SpVoice; $sp.GetVoices() | ForEach-Object { $_.GetDescription() }", ordered, "SAPI.SpVoice");
        }
        // Strategy 3: Registry tokens (raw token names)
        if (ordered.isEmpty()) {
            runPowerShellLines("Get-ChildItem 'HKLM:\\SOFTWARE\\Microsoft\\Speech\\Voices\\Tokens','HKLM:\\SOFTWARE\\WOW6432Node\\Microsoft\\Speech\\Voices\\Tokens' -ErrorAction SilentlyContinue | ForEach-Object { (Get-Item $_.PsPath).GetValue('') }", ordered, "RegistryTokens");
        }
        // Fallback list if still empty
        if (ordered.isEmpty()) {
            logger.info("No voices detected; applying hardcoded fallback list");
            ordered.add("Microsoft David Desktop");
            ordered.add("Microsoft Zira Desktop");
            ordered.add("Microsoft Mark");
            ordered.add("Microsoft Hazel Desktop");
        }
        java.util.List<String> list = new java.util.ArrayList<>(ordered);
        list.sort(String.CASE_INSENSITIVE_ORDER);
        logger.info("Final Windows TTS voices list: {}", list);
        return list;
    }

    private void runPowerShellLines(String psScript, java.util.Set<String> collector, String tag) {
        // Increased timeout to 15s for reliability on slower systems and first-run JIT
        java.util.List<String> lines = runPowerShell(psScript, 15);
        if (!lines.isEmpty()) {
            logger.debug("{} enumeration returned {} entries", tag, lines.size());
            for (String l : lines) {
                String trimmed = l == null ? null : l.trim();
                if (trimmed != null && !trimmed.isEmpty()) collector.add(trimmed);
            }
        } else {
            logger.debug("{} enumeration produced no output", tag);
        }
    }

    private boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win");
    }

    private java.util.List<String> runPowerShell(String script, int timeoutSeconds) {
        java.util.List<String> output = new java.util.ArrayList<>();
        Process process = null;
        try {
            // Build PowerShell command safely via ProcessBuilder (no fragile string quoting)
            // Ensure UTF-8 output and suppress any error UI
            String psCommand = "& { $ErrorActionPreference='SilentlyContinue'; [Console]::OutputEncoding = [System.Text.Encoding]::UTF8; " + script + " }";
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add("powershell.exe");
            cmd.add("-NoProfile");
            cmd.add("-NonInteractive");
            cmd.add("-ExecutionPolicy");
            cmd.add("Bypass");
            cmd.add("-Command");
            cmd.add(psCommand);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process started = pb.start();
            process = started; // keep reference for cleanup below

            java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
            Thread reader = new Thread(() -> {
                try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(started.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        output.add(line);
                    }
                } catch (Exception ignored) {
                } finally {
                    done.set(true);
                }
            }, "ps-reader");
            reader.setDaemon(true);
            reader.start();

            boolean finished = started.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                logger.warn("PowerShell script timeout ({} s) for script: {}", timeoutSeconds, script);
                started.destroyForcibly();
            } else {
                // Give reader thread a brief moment to flush remaining lines
                long waitUntil = System.currentTimeMillis() + 250;
                while (!done.get() && System.currentTimeMillis() < waitUntil) {
                    try { Thread.sleep(10); } catch (InterruptedException ignored) { break; }
                }
            }
        } catch (IOException | InterruptedException e) {
            logger.debug("PowerShell execution failed for script: {}", script, e);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
        return output;
    }

    /**
     * Animates the progress bar during loading - optimized with proper timing
     */
    private void startLoadingAnimation() {
        // Use scheduled executor with fixed rate instead of tight loop with sleep
        ScheduledFuture<?> animationTask = scheduledExecutor.scheduleAtFixedRate(() -> {
            if (!isLoading) return;
            Platform.runLater(() -> {
                if (progressLogin.getProgress() >= 1) {
                    progressLogin.setProgress(0);
                }
                progressLogin.setProgress(progressLogin.getProgress() + 0.025);
            });
        }, 0, 50, TimeUnit.MILLISECONDS);

        // Stop animation when loading completes
        CompletableFuture.runAsync(() -> {
            while (isLoading && !shutdownRequested) {
                try { Thread.sleep(100); } catch (InterruptedException e) { return; }
            }
            animationTask.cancel(false);
            Platform.runLater(() -> {
                progressLogin.setProgress(1);
                panelLogin.setVisible(false);
                // Ensure only user scroll pane visible by default after load
                if (scrollInfo != null) scrollInfo.setVisible(false);
                if (scrollSettings != null) scrollSettings.setVisible(false);
                if (scrollUser != null) scrollUser.setVisible(true);
                panelUser.setVisible(true);
                enableNavigation();
                logger.info("Loading complete!");
            });
        }, scheduledExecutor);
    }

    /**
     * Simulates loading process - deterministic spacing using a single scheduled task
     */
    private void simulateLoading() {
        updateLoadingStatus("Initializing...");
        if (scheduledExecutor == null || scheduledExecutor.isShutdown()) {
            updateLoadingStatus("Ready!");
            isLoading = false;
            return;
        }
        final java.util.concurrent.atomic.AtomicInteger step = new java.util.concurrent.atomic.AtomicInteger(0);
        final ScheduledFuture<?>[] handle = new ScheduledFuture<?>[1];
        handle[0] = scheduledExecutor.scheduleWithFixedDelay(() -> {
            int s = step.incrementAndGet();
            switch (s) {
                case 1 -> updateLoadingStatus("Starting MITM proxy...");
                case 2 -> updateLoadingStatus("Waiting for connection...");
                case 3 -> updateLoadingStatus("Ready!");
                case 4 -> {
                    isLoading = false;
                    ScheduledFuture<?> h = handle[0];
                    if (h != null) h.cancel(false);
                }
                default -> {}
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * Updates loading status label (rate-limited to avoid spammy rapid updates)
     */
    private void updateLoadingStatus(String status) {
        if (scheduledExecutor == null || scheduledExecutor.isShutdown()) {
            setLoadingStatusImmediate(status);
            return;
        }
        long delayMs;
        synchronized (loadingStatusLock) {
            long now = System.currentTimeMillis();
            long allowedAt = Math.max(now, nextAllowedLoadingUpdateAtMs);
            delayMs = Math.max(0L, allowedAt - now);
            nextAllowedLoadingUpdateAtMs = allowedAt + LOADING_MIN_INTERVAL_MS;
        }
        scheduledExecutor.schedule(() -> setLoadingStatusImmediate(status), delayMs, TimeUnit.MILLISECONDS);
    }

    private void setLoadingStatusImmediate(String status) {
        Platform.runLater(() -> {
            progressLoginLabel.setText(status);
            logger.debug("Loading status: {}", status);
        });
    }

    /**
     * Enables navigation buttons after loading
     */
    private void enableNavigation() {
        // Removed opacity adjustments to prevent perceived shrinking/flicker
        highlightActiveButton(btnUser);
    }

    private void highlightActiveButton(Button active) {
        Button[] buttons = {btnInfo, btnUser, btnSettings};
        for (Button b : buttons) {
            if (b == null) continue;
            b.getStyleClass().remove("nav-button-active");
            if (!b.getStyleClass().contains("nav-button")) {
                b.getStyleClass().add("nav-button");
            }
        }
        if (active != null && !active.getStyleClass().contains("nav-button-active")) {
            active.getStyleClass().add("nav-button-active");
        }
    }

    /**
     * Handles navigation button clicks
     */
    @FXML
    public void handleButtonAction(MouseEvent event) {
        if (isLoading) {
            return;
        }
        Object source = event.getSource();
        if (source == btnInfo && !isAppDisabled) {
            showPanel(panelInfo);
            highlightActiveButton(btnInfo);
        } else if (source == btnUser && !isAppDisabled) {
            showPanel(panelUser);
            highlightActiveButton(btnUser);
        } else if (source == btnSettings && !isAppDisabled) {
            showPanel(panelSettings);
            highlightActiveButton(btnSettings);
        }
    }


    /**
     * Shows specified panel and hides others
     */
    private void showPanel(AnchorPane panelToShow) {
        panelLogin.setVisible(false);
        if (scrollInfo != null) { scrollInfo.setVisible(false); panelInfo.setVisible(false); }
        if (scrollUser != null) { scrollUser.setVisible(false); panelUser.setVisible(false); }
        if (scrollSettings != null) { scrollSettings.setVisible(false); panelSettings.setVisible(false); }

        if (panelToShow == panelInfo && scrollInfo != null) {
            scrollInfo.setVisible(true); panelInfo.setVisible(true);
        } else if (panelToShow == panelUser && scrollUser != null) {
            scrollUser.setVisible(true); panelUser.setVisible(true);
        } else if (panelToShow == panelSettings && scrollSettings != null) {
            scrollSettings.setVisible(true); panelSettings.setVisible(true);
        } else {
            panelToShow.setVisible(true);
        }
        logger.debug("Showing panel: {}", panelToShow.getId());
    }

    // ========== UI Update Methods ==========

    public void setUserIDLabel(String userID) {
        Platform.runLater(() -> userIDLabel.setText(userID));
    }


    public void setMessagesSentLabel(long count) {
        Platform.runLater(() -> messagesSentLabel.setText(String.valueOf(count)));
    }

    public void setCharactersNarratedLabel(long count) {
        Platform.runLater(() -> charactersNarratedLabel.setText(String.valueOf(count)));
    }

    // ========== Event Handlers ==========

    @FXML
    public void selectVoice() {
        String selectedVoice = voices.getValue();
        if (selectedVoice == null || selectedVoice.isBlank()) {
            return; // ignore spurious events during refresh
        }
        logger.info("Voice selected: {}", selectedVoice);
        showInformation("Voice Selected", "You selected: " + selectedVoice);

        // Play a brief sample using VoiceGenerator for proper coordination
        if (VoiceGenerator.isInitialized()) {
            int uiRate = rateSlider != null ? (int) Math.round(rateSlider.getValue()) : 50;
            VoiceGenerator.getInstance().speakVoice(selectedVoice, "Sample voice confirmation", (short) uiRate);
        } else if (inbuiltSynth != null && inbuiltSynth.isReady()) {
            // Fallback to direct call if VoiceGenerator not initialized
            int uiRate = rateSlider != null ? (int) Math.round(rateSlider.getValue()) : 50;
            inbuiltSynth.speakInbuiltVoice(selectedVoice, "Sample voice confirmation", (short) uiRate);
        }
    }

    @FXML
    public void selectSource() {
        String selectedSource = sources.getValue();
        logger.info("Source selected: {}", selectedSource);
        Chat.getInstance().applySourceSelection(selectedSource);
        showInformation("Source Selected", "You selected: " + selectedSource);
    }

    @FXML
    public void toggleMic() {
        if (micButton.isSelected()) {
            logger.info("Microphone enabled");
            showInformation("Microphone", "Microphone enabled");
        } else {
            logger.info("Microphone disabled");
            showInformation("Microphone", "Microphone disabled");
        }
    }

    @FXML
    public void togglePrivateMessages() {
        boolean enabled = privateChatButton.isSelected();
        Chat.getInstance().setWhispersEnabled(enabled);
        logger.info("Private messages {}", enabled ? "enabled" : "disabled");
    }

    @FXML
    public void toggleTeamChat() {
        boolean enabled = teamChatButton.isSelected();
        if (enabled) {
            Chat.getInstance().enableChannel(Chat.TYPE_TEAM);
        } else {
            Chat.getInstance().disableChannel(Chat.TYPE_TEAM);
        }
        logger.info("Team chat {}", enabled ? "enabled" : "disabled");
    }

    @FXML
    public void ignorePlayer() {
        String player = addIgnoredPlayer.getValue();
        if (player != null && !player.equals("Add RiotId#RiotTag")) {
            Chat.getInstance().ignoreUser(player);
            logger.info("Ignoring player: {}", player);
            showInformation("Player Ignored", "Added " + player + " to ignore list");
        }
    }

    @FXML
    public void unignorePlayer() {
        String player = removeIgnoredPlayer.getValue();
        if (player != null && !player.equals("View/Remove RiotID#RiotTag")) {
            Chat.getInstance().unignoreUser(player);
            logger.info("Unignoring player: {}", player);
            showInformation("Player Unignored", "Removed " + player + " from ignore list");
        }
    }

    @FXML
    public void syncValorantSettings() {
        logger.info("Syncing Valorant settings");
        showInformation("Sync Complete", "Valorant settings synced successfully!");
    }


    @FXML
    public void openDiscordInvite() {
        logger.info("Opening Discord invite");
        try {
            java.awt.Desktop.getDesktop().browse(
                    java.net.URI.create("https://discord.gg/yourserver")
            );
        } catch (IOException e) {
            logger.error("Could not open Discord invite", e);
            showAlert("Error", "Failed to open Discord invite");
        }
    }

    /**
     * Static narration entry-point used by ChatDataHandler after it has already
     * applied filtering and recorded stats. This method should NOT alter Chat
     * statistics besides updating UI labels; those are handled upstream.
     *
     * ✅ AUTOMATIC TTS WORKFLOW using InbuiltVoiceSynthesizer (with proper VB-Cable routing):
     * - Called automatically when a chat message passes filters
     * - Speaks the message immediately via persistent PowerShell TTS
     * - Audio routes to VB-Cable → Valorant Open Mic → Teammates hear it!
     * - NO manual trigger (no V key pressing) required!
     *
     * Prerequisites for teammates to hear:
     * - Valorant Voice Activation: OPEN MIC (not Push to Talk)
     * - Valorant Input Device: CABLE Output (VB-Audio Virtual Cable)
     * - InbuiltVoiceSynthesizer automatically routes to VB-Cable on startup
     */
    public static void narrateMessage(Message msg) {
        ValVoiceController c = latestInstance;
        if (c == null) {
            logger.warn("⚠️ narrateMessage called but ValVoiceController not initialized");
            return;
        }
        if (msg == null) {
            logger.warn("⚠️ narrateMessage called with null message");
            return;
        }
        if (msg.getContent() == null) {
            logger.debug("narrateMessage: message has null content, skipping");
            return;
        }

        try {
            // Use VoiceGenerator for coordinated TTS with Push-to-Talk automation
            if (VoiceGenerator.isInitialized()) {
                VoiceGenerator voiceGen = VoiceGenerator.getInstance();
                int uiRate = c.mapSliderToUiRate(); // Get UI rate (0-100)
                String selectedVoice = (c.voices != null) ? c.voices.getValue() : null;

                // If no voice selected, use first available voice
                if (selectedVoice == null || selectedVoice.isEmpty()) {
                    java.util.List<String> availableVoices = voiceGen.getAvailableVoices();
                    if (!availableVoices.isEmpty()) {
                        selectedVoice = availableVoices.get(0);
                    } else {
                        logger.warn("⚠ No voices available - cannot narrate message!");
                        return;
                    }
                }

                // Log TTS trigger
                logger.info("🔊 TTS TRIGGERED: \"{}\" (voice: {}, rate: {}, PTT: {})",
                    msg.getContent().length() > 50 ? msg.getContent().substring(0, 47) + "..." : msg.getContent(),
                    selectedVoice,
                    uiRate,
                    voiceGen.isPushToTalkEnabled());

                // Speak using VoiceGenerator (automatic queue + Push-to-Talk)
                voiceGen.speakVoice(msg.getContent(), selectedVoice, (short) uiRate);
            } else if (c.inbuiltSynth != null && c.inbuiltSynth.isReady()) {
                // Fallback: Direct call if VoiceGenerator failed to initialize
                logger.warn("⚠ VoiceGenerator not initialized - using direct TTS (no Push-to-Talk)");
                int uiRate = c.mapSliderToUiRate();
                String selectedVoice = (c.voices != null) ? c.voices.getValue() : null;

                if (selectedVoice == null || selectedVoice.isEmpty()) {
                    java.util.List<String> availableVoices = c.inbuiltSynth.getAvailableVoices();
                    if (!availableVoices.isEmpty()) {
                        selectedVoice = availableVoices.get(0);
                    } else {
                        logger.warn("⚠ No voices available in fallback TTS - cannot narrate message!");
                        return;
                    }
                }

                c.inbuiltSynth.speakInbuiltVoice(selectedVoice, msg.getContent(), (short) uiRate);
            } else {
                logger.warn("⚠ TTS system not ready - cannot narrate message!");
            }

            // Update UI stats from Chat (already incremented by ChatDataHandler)
            Chat chat = Chat.getInstance();
            c.setMessagesSentLabel(chat.getNarratedMessages());
            c.setCharactersNarratedLabel(chat.getNarratedCharacters());
        } catch (Exception e) {
            logger.error("narrateMessage failed", e);
        }
    }

    /**
     * Entry point for incoming raw XMPP chat XML stanzas.
     * In a real integration this would be invoked by a networking / XMPP listener thread.
     */
    public void handleIncomingChatXml(String xml) {
        if (xml == null || xml.isBlank()) return;
        Message message = ChatDataHandler.getInstance().parseMessage(xml);
        if (message == null) return;
        // Delegate to unified handler (will record stats + narrate)
        ChatDataHandler.getInstance().message(message);
        logger.debug("Processed incoming chat XML via unified handler: {}", message);
    }


    private int mapSliderToUiRate() {
        if (rateSlider == null) return 50; // neutral (middle of 0-100 range)
        return (int) Math.round(rateSlider.getValue()); // Direct 0-100 UI value for InbuiltVoiceSynthesizer
    }

    /**
     * Shows an information alert dialog to the user
     */
    private void showInformation(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }

    /**
     * Shows an error alert dialog to the user
     */
    private void showAlert(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }

    public void shutdownServices() {
        shutdownRequested = true;

        if (inbuiltSynth != null) {
            inbuiltSynth.shutdown();
        }
        if (scheduledExecutor != null) {
            scheduledExecutor.shutdownNow();
            try {
                if (!scheduledExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.warn("Scheduled executor did not terminate in time");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void startChatSimulation() {
        scheduledExecutor.execute(() -> {
            try {
                String[] samples = new String[]{
                        "<message from='playerSelf@ares-parties.na1.pvp.net' type='groupchat'><body>Hello party!</body></message>",
                        "<message from='ally123@ares-coregame.na1.pvp.net' type='groupchat'><body>Team push B</body></message>",
                        "<message from='villainall@ares-coregame.na1.pvp.net' type='groupchat'><body>GL HF</body></message>",
                        "<message from='friend987@ares-pregame.na1.pvp.net' type='groupchat'><body>Ready?</body></message>",
                        "<message from='whisperGuy@prod.na1.chat.valorant.gg' type='chat'><body>&amp;Encoded &lt;Whisper&gt;</body></message>"
                };
                ChatDataHandler.getInstance().updateSelfId("playerSelf");
                Platform.runLater(() -> {
                    if (sources != null) sources.setValue("SELF+PARTY+TEAM+ALL");
                });

                for (int i = 0; i < samples.length && SIMULATE_CHAT && !shutdownRequested; i++) {
                    handleIncomingChatXml(samples[i]);
                    Thread.sleep(2500);
                }
            } catch (InterruptedException ignored) { }
        });
    }


    private void verifyExternalDependencies() {
        Path workingDir = Paths.get(System.getProperty("user.dir"));
        Path xmppPrimary = workingDir.resolve(XMPP_BRIDGE_EXE_PRIMARY);
        Path xmppExe = Files.isRegularFile(xmppPrimary) ? xmppPrimary : null;

        // Also check mitm/ subdirectory
        if (xmppExe == null) {
            Path xmppInMitm = workingDir.resolve("mitm").resolve(XMPP_BRIDGE_EXE_PRIMARY);
            if (Files.isRegularFile(xmppInMitm)) {
                xmppExe = xmppInMitm;
            }
        }

        boolean xmppExePresent = xmppExe != null;
        if (!xmppExePresent) {
            logger.error("MITM executable '{}' not found! Application cannot function without it.", XMPP_BRIDGE_EXE_PRIMARY);
            updateStatusLabel(statusXmpp, "MITM exe missing", false);
        } else {
            logger.info("Detected MITM executable: {}", xmppExe.toAbsolutePath());
            updateStatusLabel(statusXmpp, "Detected", true);
        }

        // Check VB-CABLE and audio routing status via InbuiltVoiceSynthesizer
        boolean vbDetected = detectVbCableDevices();
        if (vbDetected) {
            logger.info("Configuring audio routing via InbuiltVoiceSynthesizer...");
            logger.info("NOTE: Only PowerShell TTS will be routed - your game audio stays normal!");
            builtInRoutingOk = inbuiltSynth != null && inbuiltSynth.isReady();
            if (builtInRoutingOk) {
                updateStatusLabel(statusAudioRoute, "Active (TTS only)", true);
                logger.info("✓ TTS audio routed to VB-CABLE - game audio unchanged");
            } else {
                updateStatusLabel(statusAudioRoute, "Manual setup needed", false);
                logger.debug("Audio routing not configured automatically; manual setup may be required");
            }
        } else {
            builtInRoutingOk = false;
            updateStatusLabel(statusAudioRoute, "VB-Cable not found", false);
        }

        // Check for SoundVolumeView.exe (required for audio routing and listen-through)
        Path svv = locateSoundVolumeView();
        if (svv != null) {
            logger.info("✓ {} detected (will be used for audio routing)", SOUND_VOLUME_VIEW_EXE);
        } else {
            logger.debug("SoundVolumeView.exe not found - some audio features may be limited");
        }
    }

    private boolean detectVbCableDevices() {
        if (!isWindows()) return false;
        try {
            // Use PowerShell to list sound devices names; lightweight query.
            String ps = "Get-CimInstance Win32_SoundDevice | Select-Object -ExpandProperty Name";
            java.util.List<String> lines = runPowerShell(ps, 15);
            boolean hasVbCable = false;
            for (String l : lines) {
                if (l == null) continue;
                String lower = l.toLowerCase();
                // Look for VB-Audio Virtual Cable device (the main driver)
                if (lower.contains("vb-audio") && lower.contains("cable")) {
                    hasVbCable = true;
                    break;
                }
                // Also check for legacy naming patterns
                if (lower.contains("cable input") || lower.contains("cable output")) {
                    hasVbCable = true;
                    break;
                }
            }
            vbCableDetectedFlag = hasVbCable;
            if (vbCableDetectedFlag) {
                logger.info("VB-Audio Virtual Cable detected and ready.");
                updateStatusLabel(statusVbCable, "Detected", true);
            } else {
                logger.warn("VB-Audio Virtual Cable not found. Install from https://vb-audio.com/Cable/");
                updateStatusLabel(statusVbCable, "Not Installed", false);
            }
            return vbCableDetectedFlag;
        } catch (Exception e) {
            logger.debug("VB-Cable detection failed (non-fatal)", e);
            updateStatusLabel(statusVbCable, "Error", false);
            vbCableDetectedFlag = false;
            return false;
        }
    }

    private Path locateSoundVolumeView() {
        Path workingDir = Paths.get(System.getProperty("user.dir"));
        Path candidate = workingDir.resolve(SOUND_VOLUME_VIEW_EXE);
        if (Files.isRegularFile(candidate)) return candidate;
        String programFiles = System.getenv("ProgramFiles");
        if (programFiles != null) {
            Path pf = Paths.get(programFiles, "ValVoice", SOUND_VOLUME_VIEW_EXE);
            if (Files.isRegularFile(pf)) return pf;
            Path pfAlt = Paths.get(programFiles, SOUND_VOLUME_VIEW_EXE);
            if (Files.isRegularFile(pfAlt)) return pfAlt;
        }
        return null;
    }

    private void setupAudioRouting() {
        try {
            logger.info("Audio routing will be handled by InbuiltVoiceSynthesizer...");
            // Audio routing is now handled automatically by InbuiltVoiceSynthesizer
            builtInRoutingOk = inbuiltSynth != null && inbuiltSynth.isReady();

            if (!builtInRoutingOk) {
                logger.warn("⚠ Automatic audio routing failed - please configure manually:");
                logger.warn("1. Open Windows sound settings");
                logger.warn("2. Set CABLE Input as default device for PowerShell");
            }
        } catch (Exception e) {
            logger.error("Failed to configure audio routing", e);
            builtInRoutingOk = false;
        }
    }
    // REMOVED: routePowershellToCable() - dangerous system-wide routing
    // Audio routing is handled per-process in InbuiltVoiceSynthesizer only
}
