package com.someone.valvoicegui;

import com.someone.valvoicebackend.config.ConfigManager;
import com.someone.valvoicebackend.config.ValVoiceConfig;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SettingsController - Phase 7 Step 2: Runtime Configuration Editor.
 *
 * Controls the Settings window for editing config.json at runtime.
 * All changes are saved immediately via ConfigManager and applied
 * dynamically — the backend reads ConfigManager.get() on each request.
 *
 * No EngineState mutation. No queue modification. No backend coupling.
 */
public class SettingsController {

    private static final Logger logger = LoggerFactory.getLogger(SettingsController.class);

    // ========== FXML Fields ==========

    @FXML private TextField pttKeyField;
    @FXML private CheckBox xttsEnabledCheckBox;
    @FXML private CheckBox sapiFallbackCheckBox;
    @FXML private Slider volumeSlider;
    @FXML private ChoiceBox<String> languageChoice;
    @FXML private Button saveButton;
    @FXML private Label volumeValueLabel;

    /**
     * Called automatically after FXML is loaded.
     * Populates UI controls from current config.
     */
    @FXML
    public void initialize() {
        // Phase 7 Step 3: Reload config from disk to ensure UI reflects latest state
        ConfigManager.reload();

        // Populate language options
        languageChoice.getItems().addAll("en", "es", "fr", "de", "it", "pt", "pl", "tr", "ru", "nl", "cs", "ar", "zh-cn", "ja", "hu", "ko");

        // Configure volume slider
        volumeSlider.setMin(0.0);
        volumeSlider.setMax(1.0);
        volumeSlider.setBlockIncrement(0.05);

        // Load current config into UI with null protection
        ValVoiceConfig config = ConfigManager.get();

        // Phase 7 Step 3: Null config protection — safe defaults
        String safePttKey = (config.pttKey != null && !config.pttKey.isEmpty()) ? config.pttKey : "V";
        String safeLanguage = (config.language != null && !config.language.isEmpty()) ? config.language : "en";
        double safeVolume = (config.playbackVolume > 0) ? config.playbackVolume : 0.75;

        pttKeyField.setText(safePttKey);
        xttsEnabledCheckBox.setSelected(config.xttsEnabled);
        sapiFallbackCheckBox.setSelected(config.sapiFallbackEnabled);
        volumeSlider.setValue(safeVolume);
        languageChoice.setValue(safeLanguage);

        // Live volume label update
        if (volumeValueLabel != null) {
            volumeValueLabel.setText(String.format("%.0f%%", config.playbackVolume * 100));
            volumeSlider.valueProperty().addListener((obs, oldVal, newVal) ->
                    volumeValueLabel.setText(String.format("%.0f%%", newVal.doubleValue() * 100)));
        }

        // Force PTT field to uppercase as user types
        pttKeyField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.isEmpty()) {
                // Keep only first character, uppercase
                String single = newVal.substring(0, 1).toUpperCase();
                if (!single.equals(newVal)) {
                    pttKeyField.setText(single);
                }
            }
        });

        logger.debug("[Settings] UI initialized from config");
    }

    /**
     * Save configuration to disk and apply immediately.
     * Phase 7: Backend reads ConfigManager.get() dynamically — no restart needed.
     */
    @FXML
    private void handleSave() {
        // Step 1: Validate PTT key
        String key = pttKeyField.getText();
        if (key == null || key.length() != 1 || !Character.isLetterOrDigit(key.charAt(0))) {
            showError("PTT key must be a single letter or number.");
            return;
        }

        // Step 2: Apply values to config
        ValVoiceConfig config = ConfigManager.get();
        config.pttKey = key.toUpperCase();
        config.xttsEnabled = xttsEnabledCheckBox.isSelected();
        config.sapiFallbackEnabled = sapiFallbackCheckBox.isSelected();
        config.playbackVolume = volumeSlider.getValue();
        config.language = languageChoice.getValue();

        // Step 3: Persist to disk
        ConfigManager.save();

        logger.info("[Config] Runtime configuration updated");

        // Step 4: Show confirmation dialog
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setHeaderText("Settings Saved");
        alert.setContentText("Configuration updated successfully.");
        alert.showAndWait();

        // Step 5: Close the window
        saveButton.getScene().getWindow().hide();
    }

    /**
     * Show an error dialog for invalid input.
     */
    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setHeaderText("Invalid Input");
        alert.setContentText(message);
        alert.showAndWait();
    }
}

