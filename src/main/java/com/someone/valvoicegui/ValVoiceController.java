package com.someone.valvoicegui;

import com.someone.valvoicebackend.*;
import com.jfoenix.controls.JFXToggleButton;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.AWTException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.concurrent.*;

/**
 * Main UI controller for ValVoice.
 *
 * PHASE 5: EVENT-DRIVEN UI
 * Implements ValVoiceBackend.ValVoiceEventListener to receive backend events
 * without direct coupling. All UI updates are wrapped in Platform.runLater().
 */
public class ValVoiceController implements ValVoiceBackend.ValVoiceEventListener {
    private static final Logger logger = LoggerFactory.getLogger(ValVoiceController.class);

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

    // Toast notification container (overlay)
    @FXML public VBox toastContainer;

    // ========== State Variables ==========
    private boolean isLoading = true;

    private InbuiltVoiceSynthesizer inbuiltSynth; // persistent System.Speech synthesizer (optional)

    // Performance: Use ScheduledExecutorService instead of raw threads with sleep
    private final ScheduledExecutorService scheduledExecutor;
    private volatile boolean shutdownRequested = false;

    // Cache for voice enumeration to avoid repeated PowerShell calls
    private volatile java.util.List<String> cachedVoices = null;
    private volatile long voicesCacheTimestamp = 0;
    private static final long VOICES_CACHE_DURATION_MS = 300_000; // 5 minutes

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
        scheduledExecutor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
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

        // Phase 2: Initialize persistent inbuilt synthesizer with strict dependency validation
        // VN-parity: Only VB-Cable is a hard dependency. SoundVolumeView.exe is optional.
        try {
            inbuiltSynth = new InbuiltVoiceSynthesizer(true); // Strict mode: validate VB-Cable (required)
            if (inbuiltSynth.isReady()) {
                logger.info("InbuiltVoiceSynthesizer ready with {} voices", inbuiltSynth.getAvailableVoices().size());
                if (inbuiltSynth.isAudioRoutingConfigured()) {
                    updateStatusLabel(statusAudioRoute, "Active (TTS only)", true);
                    updateStatusLabel(statusVbCable, "Detected", true);
                } else {
                    // VN-parity: Audio routing disabled but TTS still works
                    updateStatusLabelWithType(statusAudioRoute, "Disabled (SoundVolumeView missing)", "warning");
                    updateStatusLabel(statusVbCable, "Detected", true);
                    logger.warn("[AudioRouting] Audio routing disabled - TTS will play through default speakers");
                }
            }
        } catch (InbuiltVoiceSynthesizer.DependencyMissingException e) {
            // VN-parity: Only VB-Cable missing should block startup
            logger.error("Missing dependency: {}", e.getDependencyName());
            showDependencyError(e.getDependencyName(), e.getMessage(), e.getInstallUrl());
            return; // Stop initialization - VB-Cable is required
        }

        // === VN-parity: Route main Java process audio to CABLE Input ===
        routeMainProcessAudioToVbCable();

        if (inbuiltSynth != null && inbuiltSynth.isReady()) {
            // Initialize VoiceGenerator (coordinates TTS + Push-to-Talk)
            try {
                VoiceGenerator.initialize(inbuiltSynth);
                logger.info("✓ VoiceGenerator initialized with keybind: {}", VoiceGenerator.getInstance().getCurrentKeybind());

                // === UI → VoiceGenerator Propagation Listeners ===
                // Ensure voice selection changes are always synced to VoiceGenerator
                if (voices != null) {
                    voices.valueProperty().addListener((obs, oldVal, newVal) -> {
                        if (newVal != null && !newVal.isBlank() && VoiceGenerator.isInitialized()) {
                            VoiceGenerator.getInstance().setCurrentVoice(newVal);
                            logger.debug("Voice synced to VoiceGenerator: {}", newVal);
                        }
                    });
                }

                // Ensure rate slider changes are synced to VoiceGenerator
                if (rateSlider != null) {
                    rateSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
                        if (newVal != null && VoiceGenerator.isInitialized()) {
                            VoiceGenerator.getInstance().setCurrentVoiceRate(newVal.shortValue());
                            logger.debug("Voice rate synced to VoiceGenerator: {}", newVal.shortValue());
                        }
                });
                }
            } catch (AWTException e) {
                logger.error("Failed to initialize VoiceGenerator (keybind automation disabled)", e);
            }

            // === Phase 3: Restore UI from persisted config ===
            restorePersistedSettings();
        }
        // Dependency + bridge checks
        verifyExternalDependencies();
        updateBridgeStatusFromSystemProperties();

        // === PHASE 5: EVENT-DRIVEN UI ===
        // Register this controller as listener for backend events BEFORE starting backend.
        // This decouples backend from direct UI references while preserving all behavior.
        ValVoiceBackend.getInstance().addListener(this);
        logger.info("Controller registered as backend event listener");

        // Register stats callback with ChatDataHandler
        ChatDataHandler.getInstance().setStatsCallback((sent, chars) -> {
            Platform.runLater(() -> {
                setMessagesSentLabel(sent);
                setCharactersNarratedLabel(chars);
            });
        });
        logger.info("Stats callback registered with ChatDataHandler");

        // === START BACKEND SERVICES ===
        // Start ValVoiceBackend from a background thread after UI is ready
        // This matches ValorantNarrator's architecture: UI-triggered backend startup
        scheduledExecutor.submit(() -> {
            try {
                logger.info("Starting ValVoiceBackend from controller...");
                ValVoiceBackend.getInstance().start();
                logger.info("ValVoiceBackend started successfully");
            } catch (Exception e) {
                logger.error("Failed to start ValVoiceBackend", e);
            }
        });
    }

    /**
     * Phase 3: Restore UI controls from persisted VoiceGenerator config.
     * Called after VoiceGenerator is initialized to reflect saved settings.
     */
    private void restorePersistedSettings() {
        if (!VoiceGenerator.isInitialized()) {
            logger.debug("VoiceGenerator not initialized - skipping settings restoration");
            return;
        }

        VoiceGenerator vg = VoiceGenerator.getInstance();

        // Restore rate slider
        if (rateSlider != null) {
            short persistedRate = vg.getCurrentVoiceRate();
            rateSlider.setValue(persistedRate);
            logger.info("Restored rate slider to persisted value: {}", persistedRate);
        }

        // VN-parity: Restore sources ComboBox from Chat runtime model
        // The Chat model was already populated from config.properties by Main.applySourceToChat()
        if (sources != null) {
            EnumSet<Source> currentSources = Chat.getInstance().getSources();
            String uiTierString = mapEnumSetToUiTier(currentSources);
            sources.setValue(uiTierString);
            logger.info("Restored sources ComboBox to VN tier: {} (from EnumSet: {})",
                uiTierString, Source.toString(currentSources));
        }

        // Setup and restore keybind text field
        setupKeybindField();
    }

    /**
     * VN-parity: Map EnumSet<Source> to one of the 4 valid UI tier strings.
     *
     * UI invariant: SELF is always included in UI (even if backend allows disabling).
     * This ensures persisted state maps to a valid ComboBox option.
     *
     * Mapping logic (additive tiers):
     * - SELF only → "SELF"
     * - SELF + PARTY → "SELF+PARTY"
     * - SELF + PARTY + TEAM → "SELF+PARTY+TEAM"
     * - SELF + PARTY + TEAM + ALL → "SELF+PARTY+TEAM+ALL"
     *
     * Edge cases (fail-safe to closest tier):
     * - Missing SELF → add SELF to match UI invariant
     * - Partial combinations → round up to next tier
     *
     * @param sources Current EnumSet from Chat runtime
     * @return One of the 4 valid UI tier strings
     */
    private String mapEnumSetToUiTier(EnumSet<Source> sources) {
        if (sources == null || sources.isEmpty()) {
            logger.warn("Empty source set, defaulting to SELF+PARTY+TEAM");
            return "SELF+PARTY+TEAM";
        }

        // VN invariant: UI always includes SELF
        // If backend somehow disabled SELF, force it for UI display
        boolean hasSelf = sources.contains(Source.SELF);
        boolean hasParty = sources.contains(Source.PARTY);
        boolean hasTeam = sources.contains(Source.TEAM);
        boolean hasAll = sources.contains(Source.ALL);

        // Match to one of the 4 valid tiers (additive hierarchy)
        if (hasAll) {
            // Tier 4: All channels enabled
            return "SELF+PARTY+TEAM+ALL";
        } else if (hasTeam) {
            // Tier 3: SELF + PARTY + TEAM
            return "SELF+PARTY+TEAM";
        } else if (hasParty) {
            // Tier 2: SELF + PARTY
            return "SELF+PARTY";
        } else {
            // Tier 1: SELF only (or force SELF if missing)
            if (!hasSelf) {
                logger.warn("SELF not in EnumSet, forcing SELF for UI invariant compliance");
            }
            return "SELF";
        }
    }

    /**
     * Phase 3: Setup keybind text field for PTT key capture.
     * The field captures key presses and updates VoiceGenerator config.
     */
    private void setupKeybindField() {
        if (keybindTextField == null) {
            logger.debug("keybindTextField not found in FXML");
            return;
        }

        // Restore current keybind from VoiceGenerator
        if (VoiceGenerator.isInitialized()) {
            keybindTextField.setText(VoiceGenerator.getInstance().getCurrentKeybind());
            logger.info("Restored keybind field to: {}", VoiceGenerator.getInstance().getCurrentKeybind());
        }

        // Make field non-editable but focusable for key capture
        keybindTextField.setEditable(false);
        keybindTextField.setFocusTraversable(true);

        // Capture key press events
        keybindTextField.setOnKeyPressed(event -> {
            if (event.getCode() != null) {
                int keyCode = event.getCode().getCode();
                String keyName = event.getCode().getName();

                // Update UI
                keybindTextField.setText(keyName);

                // Persist to VoiceGenerator
                if (VoiceGenerator.isInitialized()) {
                    VoiceGenerator.getInstance().setKeybind(keyCode);
                    logger.info("PTT keybind changed to: {} (code: {})", keyName, keyCode);
                }

                // Consume event to prevent further processing
                event.consume();
            }
        });

        // Visual feedback on focus
        keybindTextField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (isFocused) {
                keybindTextField.setPromptText("Press a key...");
            } else {
                keybindTextField.setPromptText("Click and press key...");
            }
        });
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


    // ═══════════════════════════════════════════════════════════════════════════════
    // PHASE 5: EVENT-DRIVEN UI - ValVoiceEventListener Implementation
    // ═══════════════════════════════════════════════════════════════════════════════
    // These methods receive events from ValVoiceBackend without direct coupling.
    // All UI updates are wrapped in Platform.runLater() for thread safety.
    // This is an intentional deviation from ValorantNarrator for production hardening.
    // NO RUNTIME BEHAVIOR IS CHANGED - only the communication pattern is decoupled.
    // ═══════════════════════════════════════════════════════════════════════════════

    @Override
    public void onStatusChanged(String component, String status, boolean ok) {
        Platform.runLater(() -> {
            switch (component) {
                case "xmpp" -> {
                    if (statusXmpp != null) {
                        updateStatusLabel(statusXmpp, status, ok);
                    }
                }
                case "bridge" -> {
                    if (statusBridgeMode != null) {
                        statusBridgeMode.setText(status);
                    }
                    // Also update XMPP status when bridge mode changes
                    if (statusXmpp != null && "external-exe".equalsIgnoreCase(status)) {
                        updateStatusLabel(statusXmpp, "MITM Active", true);
                    }
                }
                default -> logger.debug("Unknown status component: {}", component);
            }
        });
    }

    @Override
    public void onIdentityCaptured(String puuid) {
        Platform.runLater(() -> {
            if (statusSelfId != null) {
                if (puuid != null && !puuid.isBlank()) {
                    // Show abbreviated PUUID for privacy
                    String abbreviated = puuid.length() > 8 ? puuid.substring(0, 8) + "..." : puuid;
                    updateStatusLabelWithType(statusSelfId, "Self: " + abbreviated, "ok");
                    applyTooltip(statusSelfId, "Your PUUID: " + puuid);
                } else {
                    updateStatusLabelWithType(statusSelfId, "Self: (observer mode)", "info");
                    applyTooltip(statusSelfId, "Observer mode - player ID not tracked");
                }
            }
        });
    }

    @Override
    public void onStatsUpdated(long messagesSent, long charactersSent) {
        Platform.runLater(() -> {
            setMessagesSentLabel(messagesSent);
            setCharactersNarratedLabel(charactersSent);
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
        // VN-parity: Populate sources ComboBox with EXACTLY 4 additive tiers
        // UI invariant: SELF is ALWAYS included (backend may allow disabling, UI does not expose it)
        sources.getItems().addAll(
            "SELF",
            "SELF+PARTY",
            "SELF+PARTY+TEAM",
            "SELF+PARTY+TEAM+ALL"
        );

        // ValVoice default: SELF+PARTY+TEAM (excludes ALL chat for cleaner default experience)
        // This differs from VN's fail-open default - intentional UX choice for voice injection
        sources.setValue("SELF+PARTY+TEAM");
        logger.info("Default chat source set to: SELF+PARTY+TEAM (ALL chat excluded by default)");

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

                    // Prefer the persisted voice from VoiceGenerator config, otherwise use first available
                    String preferredVoice = null;
                    if (VoiceGenerator.isInitialized()) {
                        preferredVoice = VoiceGenerator.getInstance().getCurrentVoice();
                    }

                    if (preferredVoice != null && list.contains(preferredVoice)) {
                        voices.setValue(preferredVoice);
                        logger.info("Restored persisted voice selection: {}", preferredVoice);
                    } else if (voices.getValue() == null && !list.isEmpty()) {
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
        if (source == btnInfo) {
            showPanel(panelInfo);
            highlightActiveButton(btnInfo);
        } else if (source == btnUser) {
            showPanel(panelUser);
            highlightActiveButton(btnUser);
        } else if (source == btnSettings) {
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

        // CRITICAL FIX: Store the selected voice in VoiceGenerator so all future TTS uses it
        if (VoiceGenerator.isInitialized()) {
            VoiceGenerator.getInstance().setCurrentVoice(selectedVoice);
            logger.debug("VoiceGenerator.currentVoice updated to: {}", selectedVoice);
        }

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

        // VN-parity: Persist source immediately
        persistSourceConfig();

        showInformation("Source Selected", "You selected: " + selectedSource);
    }

    /**
     * VN-parity: Persist source (channel filters) to config.properties immediately.
     * Called on any channel toggle/selection change.
     */
    private void persistSourceConfig() {
        if (VoiceGenerator.isInitialized()) {
            // VoiceGenerator.saveConfig() includes source from Chat.getSources()
            VoiceGenerator.getInstance().saveConfig();
            logger.debug("[Config] Source persisted: {}", Source.toString(Chat.getInstance().getSources()));
        }
    }

    /**
     * TODO: Stub handler - mic passthrough not implemented for Golden Build v1.0
     * This toggle currently only shows an alert, no actual audio routing.
     */
    @FXML
    public void toggleMic() {
        if (micButton.isSelected()) {
            logger.info("Microphone enabled (stub - no implementation)");
            showInformation("Microphone", "Microphone passthrough is not yet implemented.");
        } else {
            logger.info("Microphone disabled (stub - no implementation)");
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

    /**
     * TODO: Stub handler - Valorant settings sync not implemented for Golden Build v1.0
     * This button currently only shows a success alert, no actual sync logic.
     */
    @FXML
    public void syncValorantSettings() {
        logger.info("Syncing Valorant settings (stub - no implementation)");
        showInformation("Not Implemented", "Valorant settings sync is not yet implemented.");
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
            showToast("Error", "Failed to open Discord invite", ToastType.ERROR);
        }
    }

    // ========== TOAST NOTIFICATION SYSTEM ==========

    /**
     * Toast notification types for styling
     */
    public enum ToastType {
        INFO,       // Blue/neutral - general information
        SUCCESS,    // Green - successful operations
        WARNING,    // Yellow/amber - warnings (non-fatal)
        ERROR       // Red - errors (non-fatal)
    }

    /**
     * Shows a non-blocking toast notification that auto-dismisses.
     * Replaces blocking Alert.showAndWait() for non-fatal messages.
     *
     * @param title   Toast title (bold header)
     * @param message Toast message content
     * @param type    Toast type for styling (INFO, SUCCESS, WARNING, ERROR)
     */
    private void showToast(String title, String message, ToastType type) {
        showToast(title, message, type, 3000); // Default 3 second duration
    }

    /**
     * Shows a non-blocking toast notification with custom duration.
     *
     * @param title      Toast title
     * @param message    Toast message
     * @param type       Toast type for styling
     * @param durationMs Duration in milliseconds before auto-dismiss
     */
    private void showToast(String title, String message, ToastType type, int durationMs) {
        Platform.runLater(() -> {
            if (toastContainer == null) {
                logger.warn("Toast container not initialized, falling back to logger");
                logger.info("[Toast-{}] {}: {}", type, title, message);
                return;
            }

            // Create toast HBox container
            HBox toast = new HBox(10);
            toast.setAlignment(Pos.CENTER_LEFT);
            toast.setPadding(new Insets(12, 16, 12, 16));
            toast.setMaxWidth(500);
            toast.getStyleClass().add("toast");
            toast.getStyleClass().add(getToastStyleClass(type));

            // Icon label based on type
            Label iconLabel = new Label(getToastIcon(type));
            iconLabel.setStyle("-fx-font-size: 16px;");

            // Content VBox with title and message
            VBox content = new VBox(2);
            Label titleLabel = new Label(title);
            titleLabel.getStyleClass().add("toast-title");
            Label messageLabel = new Label(message);
            messageLabel.getStyleClass().add("toast-message");
            messageLabel.setWrapText(true);
            messageLabel.setMaxWidth(400);
            content.getChildren().addAll(titleLabel, messageLabel);

            toast.getChildren().addAll(iconLabel, content);

            // Start fully transparent
            toast.setOpacity(0);

            // Add to container
            toastContainer.getChildren().add(toast);

            // Animate: fade in -> pause -> fade out -> remove
            FadeTransition fadeIn = new FadeTransition(Duration.millis(200), toast);
            fadeIn.setFromValue(0);
            fadeIn.setToValue(1);

            PauseTransition pause = new PauseTransition(Duration.millis(durationMs));

            FadeTransition fadeOut = new FadeTransition(Duration.millis(300), toast);
            fadeOut.setFromValue(1);
            fadeOut.setToValue(0);

            SequentialTransition sequence = new SequentialTransition(fadeIn, pause, fadeOut);
            sequence.setOnFinished(e -> toastContainer.getChildren().remove(toast));
            sequence.play();

            logger.debug("[Toast-{}] {}: {}", type, title, message);
        });
    }

    /**
     * Get CSS style class for toast type
     */
    private String getToastStyleClass(ToastType type) {
        return switch (type) {
            case SUCCESS -> "toast-success";
            case WARNING -> "toast-warning";
            case ERROR -> "toast-error";
            default -> "toast-info";
        };
    }

    /**
     * Get emoji icon for toast type
     */
    private String getToastIcon(ToastType type) {
        return switch (type) {
            case SUCCESS -> "✓";
            case WARNING -> "⚠";
            case ERROR -> "✕";
            default -> "ℹ";
        };
    }

    /**
     * Shows an information toast notification (non-blocking replacement for Alert)
     */
    private void showInformation(String title, String message) {
        showToast(title, message, ToastType.INFO);
    }

    /**
     * Shows an error toast notification (non-blocking replacement for Alert)
     */
    private void showAlert(String title, String message) {
        showToast(title, message, ToastType.ERROR);
    }

    /**
     * Shows a warning toast notification
     */
    private void showWarning(String title, String message) {
        showToast(title, message, ToastType.WARNING);
    }

    /**
     * Shows a success toast notification
     */
    private void showSuccess(String title, String message) {
        showToast(title, message, ToastType.SUCCESS);
    }

    /**
     * Phase 3: Shows a dependency missing error with option to open install URL.
     * This provides a graceful failure experience when critical dependencies are missing.
     * @param dependencyName Name of the missing dependency
     * @param message Detailed message with remediation steps
     * @param installUrl Optional URL to download/install the dependency (null if not applicable)
     */
    private void showDependencyError(String dependencyName, String message, String installUrl) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("ValVoice - Missing Dependency");
            alert.setHeaderText(dependencyName + " is required");
            alert.setContentText(message);

            // Add appropriate buttons based on whether we have an install URL
            ButtonType exitButton = new ButtonType("Exit", ButtonBar.ButtonData.CANCEL_CLOSE);
            if (installUrl != null && !installUrl.isBlank()) {
                ButtonType openUrlButton = new ButtonType("Open Download Page");
                alert.getButtonTypes().setAll(openUrlButton, exitButton);

                alert.showAndWait().ifPresent(response -> {
                    if (response == openUrlButton) {
                        try {
                            java.awt.Desktop.getDesktop().browse(new java.net.URI(installUrl));
                        } catch (Exception e) {
                            logger.error("Failed to open URL: {}", installUrl, e);
                        }
                    }
                    // Exit the application after dialog is closed
                    logger.error("Exiting due to missing dependency: {}", dependencyName);
                    System.exit(1);
                });
            } else {
                // No install URL - just show exit button
                alert.getButtonTypes().setAll(exitButton);
                alert.showAndWait();
                logger.error("Exiting due to missing dependency: {}", dependencyName);
                System.exit(1);
            }
        });
    }

    public void shutdownServices() {
        shutdownRequested = true;

        // Stop the backend services (MITM, XMPP, etc.)
        try {
            ValVoiceBackend.getInstance().stop();
        } catch (Exception e) {
            logger.error("Error stopping ValVoiceBackend", e);
        }

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



    /**
     * Phase 3: Verify critical external dependencies on startup.
     * Shows blocking error dialogs with remediation steps if dependencies are missing.
     */
    private void verifyExternalDependencies() {
        // VN-parity: Single canonical path - no fallbacks
        Path workingDir = Paths.get(System.getProperty("user.dir"));
        Path xmppExe = workingDir.resolve("mitm").resolve(XMPP_BRIDGE_EXE_PRIMARY);

        if (!Files.isRegularFile(xmppExe)) {
            logger.error("FATAL: MITM executable '{}' not found at canonical location: {}",
                XMPP_BRIDGE_EXE_PRIMARY, xmppExe.toAbsolutePath());
            updateStatusLabel(statusXmpp, "MITM exe missing", false);
            // Phase 3: Show blocking error dialog for missing MITM
            showDependencyError(
                XMPP_BRIDGE_EXE_PRIMARY,
                "The MITM proxy executable is required for ValVoice to function.\n\n" +
                "Expected location:\n" +
                "  " + xmppExe.toAbsolutePath() + "\n\n" +
                "If running from source:\n" +
                "  cd mitm\n" +
                "  npm install\n" +
                "  npm run build:exe\n\n" +
                "If this is an installed version, please reinstall ValVoice.",
                null // No external download URL for bundled component
            );
            return;
        } else {
            logger.info("✓ Detected MITM executable at canonical location: {}", xmppExe.toAbsolutePath());
            updateStatusLabel(statusXmpp, "Detected", true);
        }

        // VB-CABLE and audio routing are already checked in InbuiltVoiceSynthesizer with strict mode
        // Just update status labels here for informational purposes
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

    /**
     * VN-parity: Route main Java process audio to CABLE Input.
     * Uses fixed path %ProgramFiles%/ValVoice/SoundVolumeView.exe - no PATH search.
     * Command format: SoundVolumeView.exe /SetAppDefault "CABLE Input" all PID
     *
     * GRACEFUL DEGRADATION: If SoundVolumeView.exe is missing, logs warning and continues.
     * TTS still works but audio plays through default speakers instead of VB-Cable.
     */
    private void routeMainProcessAudioToVbCable() {
        // VN-parity: Fixed path construction - no fallbacks, no PATH search
        String fileLocation = String.format(
            "%s/ValVoice/SoundVolumeView.exe",
            System.getenv("ProgramFiles").replace("\\", "/")
        );

        // VN-parity: Check if SoundVolumeView.exe exists - graceful degradation if missing
        if (!new java.io.File(fileLocation).exists()) {
            logger.warn("[AudioRouting] Disabled: SoundVolumeView.exe not found at %ProgramFiles%/ValVoice/SoundVolumeView.exe");
            // Update status indicator - do NOT block startup
            Platform.runLater(() -> {
                if (statusAudioRoute != null) {
                    updateStatusLabelWithType(statusAudioRoute, "Disabled (tool missing)", "warning");
                }
            });
            return;
        }

        try {
            // Get current Java process PID
            long pid = ProcessHandle.current().pid();

            // VN-parity command format: SoundVolumeView.exe /SetAppDefault "CABLE Input" all PID
            String command = fileLocation + " /SetAppDefault \"CABLE Input\" all " + pid;
            logger.debug("[AudioRouting] Executing: {}", command);

            Runtime.getRuntime().exec(command);
            logger.info("[AudioRouting] ✓ Main Java process (PID {}) routed to CABLE Input", pid);

        } catch (IOException e) {
            // VN-parity: Wrap in try/catch - log and continue, do not propagate fatal exception
            logger.error("[AudioRouting] Failed to route main process audio: {}", e.getMessage());
            Platform.runLater(() -> {
                if (statusAudioRoute != null) {
                    updateStatusLabelWithType(statusAudioRoute, "Failed (exec error)", "error");
                }
            });
            // Do NOT crash - TTS still works, just won't be injected into mic
        }
    }
}
