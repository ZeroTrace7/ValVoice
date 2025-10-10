package com.someone.valvoice;

import com.jfoenix.controls.JFXToggleButton;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static com.someone.valvoice.ValVoiceApplication.*;

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
    @FXML public Label statusBridgeMode;   // external-exe vs embedded-script
    @FXML public Label statusVbCable;      // VB-Audio virtual cable detection
    @FXML public Label statusAudioRoute;   // SoundVolumeView availability
    @FXML public Label statusSelfId;       // Current self player id

    // ========== State Variables ==========
    private boolean isLoading = true;
    private boolean isAppDisabled = false;
    private AnchorPane lastAnchorPane;

    private TtsEngine ttsEngine; // Text-to-speech engine
    private ChatListenerService chatListenerService; // polls local Riot chat REST
    private InbuiltVoiceSynthesizer inbuiltSynth; // persistent System.Speech synthesizer (optional)

    private static final boolean SIMULATE_CHAT = false; // set true for local TTS demo without Valorant
    /**
     * External XMPP Handler Executable (single supported name)
     */
    private static final String XMPP_BRIDGE_EXE_PRIMARY = "valvoice-xmpp.exe";
    /**
     * Audio Routing Tool (DEPRECATED - now using built-in AudioRouter)
     */
    private static final String SOUND_VOLUME_VIEW_EXE = "SoundVolumeView.exe";

    public ValVoiceController() {
        latestInstance = this;
    }

    public static ValVoiceController getLatestInstance() {
        return latestInstance;
    }

    public ChatListenerService getChatListenerService() {
        return chatListenerService;
    }

    /**
     * Called automatically after FXML is loaded
     */
    @FXML
    public void initialize() {
        logger.info("Initializing ValVoiceController");

        // Register listener for self ID changes (immediate invoke to show current value)
        ChatDataHandler.getInstance().registerSelfIdListener(id -> {
            if (id != null) {
                Platform.runLater(() -> {
                    setUserIDLabel(id);
                    updateStatusLabel(statusSelfId, "Self: " + id, true);
                });
            }
        }, true);

        // Set initial visible panel
        lastAnchorPane = panelUser;
        // Initialize status labels early
        updateStatusLabel(statusXmpp, "Checking...", false);
        updateStatusLabel(statusBridgeMode, "-", false);
        updateStatusLabel(statusVbCable, "Checking...", false);
        updateStatusLabel(statusAudioRoute, "Checking...", false);
        updateStatusLabel(statusSelfId, "Self: (pending)", false);

        // Populate ComboBoxes with data
        populateComboBoxes();
        // Start loading animation
        startLoadingAnimation();
        // Simulate loading process
        simulateLoading();

        ttsEngine = new TtsEngine();
        // Initialize persistent inbuilt synthesizer (Windows-only)
        inbuiltSynth = new InbuiltVoiceSynthesizer();
        if (inbuiltSynth.isReady()) {
            logger.info("InbuiltVoiceSynthesizer ready with {} voices", inbuiltSynth.getAvailableVoices().size());
        }
        if (SIMULATE_CHAT) {
            startChatSimulation();
        }
        // New: attempt to auto-initialize Riot local API and resolve self player ID.
        initRiotLocalApiAsync();
        // Dependency + bridge checks
        verifyExternalDependencies();
        updateBridgeStatusFromSystemProperties();
    }

    private void updateBridgeStatusFromSystemProperties() {
        String mode = System.getProperty("valvoice.bridgeMode", "unknown");
        Platform.runLater(() -> {
            if (statusBridgeMode != null) statusBridgeMode.setText(mode);
            if (statusXmpp != null) {
                if ("external-exe".equalsIgnoreCase(mode)) {
                    updateStatusLabel(statusXmpp, "Ready", true);
                } else if ("embedded-script".equalsIgnoreCase(mode)) {
                    updateStatusLabel(statusXmpp, "Stub (demo only)", false);
                } else {
                    updateStatusLabel(statusXmpp, "Init...", false);
                }
            }
        });
    }

    private void updateStatusLabel(Label label, String text, boolean ok) {
        if (label == null) return;
        Platform.runLater(() -> {
            label.setText(text);
            label.setStyle(ok ? "-fx-text-fill:#59c164;" : "-fx-text-fill:#d89b52;");
        });
    }

    private void initRiotLocalApiAsync() {
        Thread t = new Thread(() -> {
            try {
                updateLoadingStatus("Locating Riot lockfile...");
                String path = LockFileHandler.findDefaultLockfile();
                if (path == null) {
                    logger.warn("Riot lockfile not found in default locations; Valorant integration inactive");
                    return;
                }
                logger.info("Found Riot lockfile: {}", path);
                updateLoadingStatus("Reading Riot lockfile...");
                boolean loaded = ChatDataHandler.getInstance().initializeFromLockfile(path);
                if (!loaded) {
                    logger.warn("Failed to read Riot lockfile; Valorant integration inactive");
                    return;
                }
                updateLoadingStatus("Resolving player identity...");
                // Self ID resolution happens inside initializeFromLockfile; reflect it in UI if set later.
                String selfId = ChatDataHandler.getInstance().getProperties().getSelfID();
                if (selfId != null) {
                    Platform.runLater(() -> setUserIDLabel(selfId));
                }
                // Fetch extended client details (version / region)
                APIHandler.getInstance().fetchClientDetails().ifPresent(details ->
                        logger.info("Riot client details: {}", details)
                );
                // Start polling chat messages for TTS
                startChatListener();
                startSelfIdRefreshTask();
                logger.info("Riot local API initialized (self ID: {})", selfId);
            } catch (Exception e) {
                logger.debug("Riot local API initialization failed", e);
            }
        }, "RiotInitThread");
        t.setDaemon(true);
        t.start();
    }

    private void startChatListener() {
        if (chatListenerService != null) return; // already started
        chatListenerService = new ChatListenerService(this::handleIncomingChatXml);
        chatListenerService.start();
        logger.info("Valorant chat listener started");
    }

    private void startSelfIdRefreshTask() {
        Thread refresher = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(60_000); // 1 minute
                    ChatDataHandler.getInstance().refreshSelfId();
                } catch (InterruptedException e) {
                    return; // stop on interrupt
                } catch (Exception e) {
                    logger.trace("Self ID refresh encountered an error", e);
                }
            }
        }, "SelfIdRefreshThread");
        refresher.setDaemon(true);
        refresher.start();
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

        // Async load voices to avoid blocking FX thread
        loadVoicesAsync(false);
    }

    private void loadVoicesAsync(boolean isRefresh) {
        if (voices == null) return;
        Platform.runLater(() -> {
            if (!isRefresh && (voices.getItems() == null || voices.getItems().isEmpty())) {
                voices.setPromptText("Loading voices...");
            } else if (isRefresh) {
                voices.setPromptText("Refreshing voices...");
            }
        });
        Thread t = new Thread(() -> {
            java.util.List<String> list = enumerateWindowsVoices();
            // Merge in System.Speech voices from inbuilt synthesizer (if any) to ensure coverage
            if (inbuiltSynth != null && inbuiltSynth.isReady()) {
                for (String v : inbuiltSynth.getAvailableVoices()) {
                    if (!list.contains(v)) list.add(v);
                }
            }
            list.sort(String.CASE_INSENSITIVE_ORDER);
            Platform.runLater(() -> {
                voices.getItems().setAll(list);
                voices.setPromptText("Select a voice");
                if (voices.getValue() == null && !list.isEmpty()) {
                    voices.setValue(list.get(0));
                }
            });
        }, "VoiceEnumThread");
        t.setDaemon(true);
        t.start();
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
        java.util.List<String> lines = runPowerShell(psScript, 6);
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
        String command = "powershell.exe -NoProfile -NonInteractive -Command \"$ErrorActionPreference='SilentlyContinue'; " + script.replace("\"", "\\\"") + "\"";
        try {
            process = Runtime.getRuntime().exec(command);
            try (java.io.BufferedReader stdOut = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
                 java.io.BufferedReader stdErr = new java.io.BufferedReader(new java.io.InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = stdOut.readLine()) != null) {
                    output.add(line);
                }
                StringBuilder err = new StringBuilder();
                while ((line = stdErr.readLine()) != null) {
                    err.append(line).append('\n');
                }
                if (err.length() > 0) {
                    logger.trace("PowerShell stderr for script [{}]: {}", script, err);
                }
            }
            if (!process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
                logger.warn("PowerShell script timeout ({} s) for script: {}", timeoutSeconds, script);
            }
        } catch (IOException | InterruptedException e) {
            logger.debug("PowerShell execution failed for script: {}", script, e);
        } finally {
            if (process != null) process.destroyForcibly();
        }
        return output;
    }

    /**
     * Animates the progress bar during loading
     */
    private void startLoadingAnimation() {
        Thread loadingThread = new Thread(() -> {
            while (isLoading) {
                try { Thread.sleep(50); } catch (InterruptedException e) { e.printStackTrace(); }
                Platform.runLater(() -> {
                    if (progressLogin.getProgress() >= 1) { progressLogin.setProgress(0); }
                    progressLogin.setProgress(progressLogin.getProgress() + 0.025);
                });
            }
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
        });
        loadingThread.setDaemon(true);
        loadingThread.start();
    }

    /**
     * Simulates loading process (replace with actual initialization)
     */
    private void simulateLoading() {
        Thread initThread = new Thread(() -> {
            try {
                // Simulate initialization steps
                updateLoadingStatus("Initializing...");
                Thread.sleep(1000);

                updateLoadingStatus("Loading configuration...");
                Thread.sleep(1000);

                updateLoadingStatus("Connecting to services...");
                Thread.sleep(1000);

                updateLoadingStatus("Ready!");
                Thread.sleep(500);

                // Mark loading complete
                isLoading = false;

            } catch (InterruptedException e) {
                logger.error("Loading interrupted", e);
            }
        });
        initThread.setDaemon(true);
        initThread.start();
    }

    /**
     * Updates loading status label
     */
    private void updateLoadingStatus(String status) {
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
        lastAnchorPane = panelToShow;
        logger.debug("Showing panel: {}", panelToShow.getId());
    }

    // ========== UI Update Methods ==========

    public void setUserIDLabel(String userID) {
        Platform.runLater(() -> userIDLabel.setText(userID));
    }

    public void setQuotaLabel(String quota) {
        Platform.runLater(() -> quotaLabel.setText(quota));
    }

    public void setMessagesSentLabel(long count) {
        Platform.runLater(() -> messagesSentLabel.setText(String.valueOf(count)));
    }

    public void setCharactersNarratedLabel(long count) {
        Platform.runLater(() -> charactersNarratedLabel.setText(String.valueOf(count)));
    }

    public void updateQuotaBar(double progress) {
        Platform.runLater(() -> quotaBar.setProgress(progress));
    }

    // ========== Event Handlers (Placeholders) ==========

    @FXML
    public void selectVoice() {
        String selectedVoice = voices.getValue();
        logger.info("Voice selected: {}", selectedVoice);
        showInformation("Voice Selected", "You selected: " + selectedVoice);
        // Play a brief sample using the persistent inbuilt synthesizer for immediate feedback
        if (inbuiltSynth != null && inbuiltSynth.isReady() && selectedVoice != null) {
            int uiRate = rateSlider != null ? (int) Math.round(rateSlider.getValue()) : 50;
            inbuiltSynth.speakInbuiltVoice(selectedVoice, "Sample voice confirmation", uiRate);
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
            Chat.getInstance().enableChannel(ChatMessageType.TEAM);
        } else {
            Chat.getInstance().disableChannel(ChatMessageType.TEAM);
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
     */
    public static void narrateMessage(Message msg) {
        ValVoiceController c = latestInstance;
        if (c == null || msg == null) return;
        if (msg.getContent() == null) return;
        try {
            if (c.ttsEngine != null) {
                int sapiRate = c.mapSliderToSapiRate();
                String selectedVoice = (c.voices != null) ? c.voices.getValue() : null;
                c.ttsEngine.speak(msg.getContent(), selectedVoice, sapiRate);
            }
            // Update UI stats from Chat (already incremented by ChatDataHandler)
            Chat chat = Chat.getInstance();
            c.setMessagesSentLabel(chat.getNarratedMessages());
            c.setCharactersNarratedLabel(chat.getNarratedCharacters());
        } catch (Exception e) {
            logger.debug("narrateMessage failed", e);
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

    private int mapSliderToSapiRate() {
        if (rateSlider == null) return 0; // neutral
        double v = rateSlider.getValue(); // 0..100
        // Linear map 0 -> -5, 50 -> 0, 100 -> +5 (conservative to keep quality) or extend to -10..10 if desired
        // Adjusted to full SAPI range:
        return (int) Math.round((v / 100.0) * 20.0 - 10.0); // 0..100 -> -10..10
    }

    public void shutdownServices() {
        if (ttsEngine != null) {
            ttsEngine.shutdown();
        }
        if (chatListenerService != null) {
            chatListenerService.stop();
        }
        if (inbuiltSynth != null) {
            inbuiltSynth.shutdown();
        }
    }

    private void startChatSimulation() {
        Thread sim = new Thread(() -> {
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
                int i = 0;
                while (SIMULATE_CHAT && i < samples.length) {
                    handleIncomingChatXml(samples[i]);
                    i++;
                    Thread.sleep(2500);
                }
            } catch (InterruptedException ignored) { }
        }, "ChatSimThread");
        sim.setDaemon(true);
        sim.start();
    }

    // Placeholder for future real Valorant chat integration.
    // A future implementation could establish authenticated WebSocket / XMPP connection
    // and forward raw XML / JSON converted messages to handleIncomingChatXml().
    private void startValorantChatBridge() {
        // TODO: Implement Valorant chat capture
    }

    private void verifyExternalDependencies() {
        Path workingDir = Paths.get(System.getProperty("user.dir"));
        Path xmppPrimary = workingDir.resolve(XMPP_BRIDGE_EXE_PRIMARY);
        Path xmppExe = Files.isRegularFile(xmppPrimary) ? xmppPrimary : null;
        boolean xmppExePresent = xmppExe != null;
        if (!xmppExePresent) {
            logger.warn("XMPP bridge executable '{}' not found in working directory (using fallback embedded script).", XMPP_BRIDGE_EXE_PRIMARY);
            updateStatusLabel(statusXmpp, "Missing exe (stub)", false);
        } else {
            logger.info("Detected external XMPP bridge executable: {}", xmppExe.toAbsolutePath());
            updateStatusLabel(statusXmpp, "Detected", true);
        }

        // NEW: Use built-in AudioRouter instead of external tool
        detectVbCableDevices();
        if ("Detected".equalsIgnoreCase(statusVbCable != null ? statusVbCable.getText() : "")) {
            logger.info("Configuring audio routing using built-in AudioRouter...");
            boolean routingSuccess = AudioRouter.routeToVirtualCable();
            if (routingSuccess) {
                updateStatusLabel(statusAudioRoute, "Active (built-in)", true);
            } else {
                updateStatusLabel(statusAudioRoute, "Manual setup needed", false);
                logger.warn("Automatic audio routing failed. Please set audio manually:");
                logger.warn("  Windows → Sound Settings → App volume → Java/PowerShell → Output: CABLE Input");
            }
        } else {
            updateStatusLabel(statusAudioRoute, "VB-Cable not found", false);
        }

        // Legacy support: Still check for SoundVolumeView.exe but don't require it
        Path svv = locateSoundVolumeView();
        if (svv != null) {
            logger.info("Note: {} detected but not needed (using built-in routing)", SOUND_VOLUME_VIEW_EXE);
            // Fallback: Try SoundVolumeView if built-in routing failed
            if (!"Active (built-in)".equals(statusAudioRoute.getText())) {
                logger.info("Attempting fallback routing via SoundVolumeView...");
                routePowershellToCable(svv);
                updateStatusLabel(statusAudioRoute, "Active (SoundVolumeView)", true);
            }
        }
    }

    private void detectVbCableDevices() {
        if (!isWindows()) return;
        try {
            // Use PowerShell to list sound devices names; lightweight query.
            String ps = "Get-CimInstance Win32_SoundDevice | Select-Object -ExpandProperty Name";
            java.util.List<String> lines = runPowerShell(ps, 8);
            boolean hasCableInput = false;
            boolean hasCableOutput = false;
            for (String l : lines) {
                if (l == null) continue;
                String lower = l.toLowerCase();
                if (lower.contains("cable input")) hasCableInput = true;
                if (lower.contains("cable output")) hasCableOutput = true;
            }
            if (hasCableInput && hasCableOutput) {
                logger.info("VB-Audio Virtual Cable devices detected (Input & Output).");
                updateStatusLabel(statusVbCable, "Detected", true);
            } else {
                logger.warn("VB-Audio Virtual Cable devices missing or incomplete: Input={} Output={}. Narration may not reach Valorant mic.", hasCableInput, hasCableOutput);
                updateStatusLabel(statusVbCable, "Missing", false);
            }
        } catch (Exception e) {
            logger.debug("VB-Cable detection failed (non-fatal)", e);
            updateStatusLabel(statusVbCable, "Error", false);
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

    // New: route short-lived powershell.exe TTS processes to VB-CABLE using SoundVolumeView
    private void routePowershellToCable(Path soundVolumeViewPath) {
        if (soundVolumeViewPath == null) return;
        try {
            String cmd = '"' + soundVolumeViewPath.toAbsolutePath().toString() + '"' +
                    " /SetAppDefault \"CABLE Input\" all \"powershell.exe\"";
            Runtime.getRuntime().exec(cmd);
            logger.info("Requested audio routing of powershell.exe to 'CABLE Input' via SoundVolumeView");
        } catch (Exception e) {
            logger.debug("Failed to route powershell.exe via SoundVolumeView", e);
        }
    }
}
