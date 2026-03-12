package com.someone.valvoicegui;

import com.someone.valvoicebackend.EnvironmentValidator;
import com.someone.valvoicebackend.config.ConfigManager;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

/**
 * SetupWizardController - Phase 8 Step 3: First-Run Setup Wizard.
 *
 * Controls the multi-step setup wizard displayed on first launch.
 * Steps:
 *   1. Welcome
 *   2. Environment Check (validates dependencies)
 *   3. Audio Setup (Valorant configuration instructions)
 *   4. Finish
 *
 * After completion, sets firstRunCompleted = true in config
 * and launches the main application window.
 *
 * No engine logic. No background threads. No architecture changes.
 */
public class SetupWizardController {

    private static final Logger logger = LoggerFactory.getLogger(SetupWizardController.class);

    // ========== FXML Fields ==========

    @FXML private StackPane wizardPages;

    @FXML private VBox page1Welcome;
    @FXML private VBox page2Environment;
    @FXML private VBox page3Audio;
    @FXML private VBox page4Finish;

    @FXML private Button backButton;
    @FXML private Button nextButton;
    @FXML private Button finishButton;

    // Custom title bar
    @FXML private HBox wizardTitleBar;
    @FXML private Button btnWizardMinimize;
    @FXML private Button btnWizardClose;

    // Custom title bar drag offsets
    private double xOffset = 0;
    private double yOffset = 0;

    // Environment check labels (Step 2)
    @FXML private Label statusVbCable;
    @FXML private Label statusSoundVolumeView;
    @FXML private Label statusPowerShell;
    @FXML private Label envWarningLabel;

    private int currentPage = 0;
    private static final int TOTAL_PAGES = 4;

    /** Cached validation results from environment check */
    private Map<String, Boolean> validationResults;

    /**
     * Called automatically after FXML load.
     * Shows the first wizard page.
     */
    @FXML
    public void initialize() {
        logger.info("[Wizard] Setup wizard initialized");

        // Custom title bar drag logic
        if (wizardTitleBar != null) {
            wizardTitleBar.setOnMousePressed(event -> {
                xOffset = event.getSceneX();
                yOffset = event.getSceneY();
            });
            wizardTitleBar.setOnMouseDragged(event -> {
                Stage stage = (Stage) wizardTitleBar.getScene().getWindow();
                stage.setX(event.getScreenX() - xOffset);
                stage.setY(event.getScreenY() - yOffset);
            });
        }

        showPage(0);
    }

    /**
     * Minimize wizard window to taskbar.
     */
    @FXML
    private void handleWizardMinimize() {
        Stage stage = (Stage) wizardTitleBar.getScene().getWindow();
        stage.setIconified(true);
    }

    /**
     * Close wizard window and exit application.
     */
    @FXML
    private void handleWizardClose() {
        javafx.application.Platform.exit();
        System.exit(0);
    }

    /**
     * Navigate to the next wizard page.
     * Triggers environment validation when entering page 2.
     */
    @FXML
    private void handleNext() {
        if (currentPage < TOTAL_PAGES - 1) {
            currentPage++;
            showPage(currentPage);

            // Run environment checks when entering the Environment page
            if (currentPage == 1) {
                runEnvironmentChecks();
            }
        }
    }

    /**
     * Navigate to the previous wizard page.
     */
    @FXML
    private void handleBack() {
        if (currentPage > 0) {
            currentPage--;
            showPage(currentPage);
        }
    }

    /**
     * Complete the wizard — mark first run as done and launch main app.
     */
    @FXML
    private void handleFinish() {
        logger.info("[Wizard] Setup wizard completed");

        // Mark first run as completed
        ConfigManager.get().firstRunCompleted = true;
        ConfigManager.save();

        // Close the wizard window
        Stage wizardStage = (Stage) finishButton.getScene().getWindow();

        // Launch the main application window
        try {
            launchMainApplication(wizardStage);
        } catch (Exception e) {
            logger.error("[Wizard] Failed to launch main application", e);
        }
    }

    /**
     * Display the specified wizard page and update button visibility.
     *
     * @param pageIndex 0-based page index (0=Welcome, 1=Environment, 2=Audio, 3=Finish)
     */
    private void showPage(int pageIndex) {
        page1Welcome.setVisible(pageIndex == 0);
        page1Welcome.setManaged(pageIndex == 0);

        page2Environment.setVisible(pageIndex == 1);
        page2Environment.setManaged(pageIndex == 1);

        page3Audio.setVisible(pageIndex == 2);
        page3Audio.setManaged(pageIndex == 2);

        page4Finish.setVisible(pageIndex == 3);
        page4Finish.setManaged(pageIndex == 3);

        // Update button visibility
        backButton.setVisible(pageIndex > 0 && pageIndex < TOTAL_PAGES - 1);
        backButton.setManaged(pageIndex > 0 && pageIndex < TOTAL_PAGES - 1);

        nextButton.setVisible(pageIndex < TOTAL_PAGES - 1);
        nextButton.setManaged(pageIndex < TOTAL_PAGES - 1);

        finishButton.setVisible(pageIndex == TOTAL_PAGES - 1);
        finishButton.setManaged(pageIndex == TOTAL_PAGES - 1);

        logger.debug("[Wizard] Showing page {}", pageIndex + 1);
    }

    /**
     * Run environment dependency checks and update the UI labels.
     * Uses EnvironmentValidator.runChecksWithResults() which returns
     * a Map of dependency name → boolean availability.
     */
    private void runEnvironmentChecks() {
        logger.info("[Wizard] Running environment validation...");

        try {
            validationResults = EnvironmentValidator.runChecksWithResults();

            updateStatusLabel(statusVbCable, validationResults.getOrDefault("VBCable", false));
            updateStatusLabel(statusSoundVolumeView, validationResults.getOrDefault("SoundVolumeView", false));
            updateStatusLabel(statusPowerShell, validationResults.getOrDefault("PowerShell", false));

            // Show warning if any dependency is missing
            boolean allPassed = validationResults.values().stream().allMatch(Boolean::booleanValue);
            if (!allPassed) {
                envWarningLabel.setText("⚠ Some dependencies are missing. ValVoice may not function correctly.");
                envWarningLabel.setVisible(true);
                envWarningLabel.setManaged(true);
            } else {
                envWarningLabel.setText("✅ All dependencies detected successfully!");
                envWarningLabel.setStyle("-fx-text-fill: #a6e3a1; -fx-font-size: 13;");
                envWarningLabel.setVisible(true);
                envWarningLabel.setManaged(true);
            }

        } catch (Exception e) {
            logger.error("[Wizard] Environment validation failed", e);
            envWarningLabel.setText("⚠ Could not complete environment check. You may continue anyway.");
            envWarningLabel.setVisible(true);
            envWarningLabel.setManaged(true);
        }
    }

    /**
     * Update a status label with OK (green) or Missing (red) styling.
     *
     * @param label  The FXML label to update
     * @param passed true = dependency found, false = missing
     */
    private void updateStatusLabel(Label label, boolean passed) {
        if (passed) {
            label.setText("✅ OK");
            label.setStyle("-fx-text-fill: #a6e3a1; -fx-font-weight: bold; -fx-font-size: 14;");
        } else {
            label.setText("❌ Missing");
            label.setStyle("-fx-text-fill: #f38ba8; -fx-font-weight: bold; -fx-font-size: 14;");
        }
    }

    /**
     * Close the wizard and open the main application window.
     * Creates a new Stage with the main FXML, theme, and tray icon.
     * Matches ValVoiceApplication.launchMainApp() behavior.
     */
    private void launchMainApplication(Stage wizardStage) throws IOException, java.awt.AWTException {
        logger.info("[Wizard] Launching main application...");

        // Load premium tactical header font
        Font.loadFont(getClass().getResourceAsStream("/fonts/BebasNeue-Regular.ttf"), 14);

        // Load main FXML
        FXMLLoader fxmlLoader = new FXMLLoader(
                ValVoiceApplication.class.getResource("/com/someone/valvoicegui/mainApplication.fxml")
        );
        Scene scene = new Scene(fxmlLoader.load());
        ValVoiceController controller = fxmlLoader.getController();

        // Load theme
        String valorantTheme = getClass().getResource("/css/valorant-theme.css").toExternalForm();
        scene.getStylesheets().add(valorantTheme);

        // Configure new stage — strip native OS frame
        Stage mainStage = new Stage();
        mainStage.initStyle(StageStyle.UNDECORATED);
        mainStage.setTitle("ValVoice");

        scene.setFill(Color.web("#0F1923"));
        mainStage.setScene(scene);
        mainStage.setResizable(false);
        mainStage.setMinWidth(600);
        mainStage.setMinHeight(450);

        // Create tray icon
        ValVoiceApplication app = new ValVoiceApplication();
        app.createTrayIcon(mainStage);

        // Register shutdown hook for controller cleanup
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            controller.shutdownServices();
        }, "ValVoice-ShutdownHook"));

        // Close wizard, show main app
        wizardStage.close();
        mainStage.sizeToScene();
        mainStage.centerOnScreen();
        mainStage.show();

        logger.info("[Wizard] Main application launched successfully");
    }
}

