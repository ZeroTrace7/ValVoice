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

    // ========== State Variables ==========
    private boolean isLoading = true;
    private boolean isAppDisabled = false;
    private AnchorPane lastAnchorPane;

    public ValVoiceController() {
        latestInstance = this;
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

        // Set initial visible panel
        lastAnchorPane = panelUser;

        // Populate ComboBoxes with data
        populateComboBoxes();

        // Start loading animation
        startLoadingAnimation();

        // Simulate loading process
        simulateLoading();
        // Removed enforceNoShrinkEffects as it caused perceived shrinking flicker
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
    }

    @FXML
    public void selectSource() {
        String selectedSource = sources.getValue();
        logger.info("Source selected: {}", selectedSource);
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
        if (privateChatButton.isSelected()) {
            logger.info("Private messages enabled");
        } else {
            logger.info("Private messages disabled");
        }
    }

    @FXML
    public void toggleTeamChat() {
        if (teamChatButton.isSelected()) {
            logger.info("Team chat enabled");
        } else {
            logger.info("Team chat disabled");
        }
    }

    @FXML
    public void ignorePlayer() {
        String player = addIgnoredPlayer.getValue();
        if (player != null && !player.equals("Add RiotId#RiotTag")) {
            logger.info("Ignoring player: {}", player);
            showInformation("Player Ignored", "Added " + player + " to ignore list");
        }
    }

    @FXML
    public void unignorePlayer() {
        String player = removeIgnoredPlayer.getValue();
        if (player != null && !player.equals("View/Remove RiotID#RiotTag")) {
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
}