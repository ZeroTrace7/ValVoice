package com.someone.valvoice;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

public class ValVoiceApplication extends Application {
    private static final Logger logger = LoggerFactory.getLogger(ValVoiceApplication.class);
    private boolean firstTime = true;
    private TrayIcon trayIcon;

    /**
     * Shows a dialog before JavaFX is initialized (uses Swing)
     */
    public static void showPreStartupDialog(String headerText, String contentText, MessageType messageType) {
        JOptionPane.showMessageDialog(null, contentText, headerText, messageType.getValue());
    }

    /**
     * Shows an information alert
     */
    public static void showInformation(String headerText, String contentText) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setHeaderText(headerText);
        alert.setContentText(contentText);
        alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        alert.show();
    }

    /**
     * Shows an error alert
     */
    public static void showAlert(String headerText, String contentText) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setHeaderText(headerText);
        alert.setContentText(contentText);
        alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        Toolkit.getDefaultToolkit().beep();
        alert.show();
    }

    /**
     * Shows an error alert and waits for user response
     */
    public static void showAlertAndWait(String headerText, String contentText) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setHeaderText(headerText);
        alert.setContentText(contentText);
        alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        Toolkit.getDefaultToolkit().beep();
        alert.showAndWait();
    }

    /**
     * Shows a confirmation dialog and returns true if user clicks Yes
     */
    public static boolean showConfirmationAlertAndWait(String headerText, String contentText) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("ValVoice");
        alert.setHeaderText(headerText);
        alert.setContentText(contentText);

        ButtonType yesButton = new ButtonType("Yes");
        ButtonType noButton = new ButtonType("No");
        alert.getButtonTypes().setAll(yesButton, noButton);

        Optional<ButtonType> result = alert.showAndWait();
        return (result.isPresent() && result.get() == yesButton);
    }

    @Override
    public void start(Stage stage) throws IOException, AWTException {
        logger.info("Initializing JavaFX Application");

        // Load FXML
        FXMLLoader fxmlLoader = new FXMLLoader(
                ValVoiceApplication.class.getResource("mainApplication.fxml")
        );

        // Configure stage
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setTitle("ValVoice");

        // Create system tray icon
        createTrayIcon(stage);

        // Keep app running when window is closed
        Platform.setImplicitExit(false);

        // Create scene with transparent background
        Scene scene = new Scene(fxmlLoader.load());
        scene.setFill(Color.TRANSPARENT);

        stage.setScene(scene);
        stage.setResizable(false);
        stage.show();

        logger.info("Application window displayed");
    }

    /**
     * Creates system tray icon for minimize to tray functionality
     */
    public void createTrayIcon(final Stage stage) throws IOException, AWTException {
        if (SystemTray.isSupported()) {
            SystemTray tray = SystemTray.getSystemTray();

            // Load icon (create a simple default icon if appIcon.png doesn't exist)
            Image image = null;
            try {
                java.net.URL iconURL = ValVoiceApplication.class.getResource("appIcon.png");
                if (iconURL != null) {
                    image = ImageIO.read(iconURL);
                } else {
                    // Create a simple default icon (16x16 blue square)
                    logger.warn("appIcon.png not found, using default icon");
                    java.awt.image.BufferedImage defaultIcon = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB);
                    java.awt.Graphics2D g = defaultIcon.createGraphics();
                    g.setColor(java.awt.Color.BLUE);
                    g.fillRect(0, 0, 16, 16);
                    g.dispose();
                    image = defaultIcon;
                }
            } catch (Exception e) {
                logger.error("Failed to load icon", e);
                // Create fallback icon
                java.awt.image.BufferedImage defaultIcon = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = defaultIcon.createGraphics();
                g.setColor(java.awt.Color.BLUE);
                g.fillRect(0, 0, 16, 16);
                g.dispose();
                image = defaultIcon;
            }

            // Hide to tray on close
            stage.setOnCloseRequest(t -> hide(stage));

            // Create popup menu
            PopupMenu popup = new PopupMenu();

            // Show menu item
            MenuItem showItem = new MenuItem("Show");
            showItem.addActionListener(e -> Platform.runLater(stage::show));
            popup.add(showItem);

            // Close menu item
            MenuItem closeItem = new MenuItem("Close");
            closeItem.addActionListener(e -> System.exit(0));
            popup.add(closeItem);

            // Create and add tray icon
            trayIcon = new TrayIcon(image, "ValVoice", popup);
            trayIcon.addActionListener(e -> Platform.runLater(stage::show));
            tray.add(trayIcon);

            logger.info("System tray icon created");
        } else {
            logger.warn("System tray not supported on this system!");
        }
    }

    /**
     * Shows notification when minimized to tray (first time only)
     */
    public void showProgramIsMinimizedMsg() {
        if (firstTime) {
            trayIcon.displayMessage(
                    "Minimized to tray",
                    "ValVoice is still running.",
                    TrayIcon.MessageType.INFO
            );
            firstTime = false;
        }
    }

    /**
     * Hides the window to system tray
     */
    private void hide(final Stage stage) {
        Platform.runLater(() -> {
            if (SystemTray.isSupported()) {
                stage.hide();
                showProgramIsMinimizedMsg();
            }
        });
    }
}