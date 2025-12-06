package com.someone.valvoicebackend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.swing.*;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class VoiceTokenHandler {
    private static final Logger logger = LoggerFactory.getLogger(VoiceTokenHandler.class);
    private static final int TOKEN_EXPIRATION_TIME = 895;
    private static long LAST_REFRESH_MS = 0;
    private final APIHandler apiHandler;

    public VoiceTokenHandler(APIHandler apiHandler) {
        this.apiHandler = apiHandler;
    }

    public static long getLAST_REFRESH_MS() {
        return LAST_REFRESH_MS;
    }

    public void startRefreshToken() {
        CompletableFuture.runAsync(() -> {
            while (true) {
                try {
                    apiHandler.addRequestQuota();
                } catch (IOException e) {
                    logger.warn(String.format("Failed to refresh token: %s", e.getMessage()));
                } catch (QuotaExhaustedException e) {
                    logger.warn(String.format("Quota exhausted, %s", e.getMessage()));
                    // Note: Controller reference removed as it will be handled differently
                    try {
                        Thread.sleep(TOKEN_EXPIRATION_TIME * 1000);
                    } catch (InterruptedException e1) {
                        logger.warn(String.format("Failed to sleep: %s", e.getMessage()));
                    }
                    continue;
                } catch (OutdatedVersioningException e) {
                    showVersionOutdatedDialog();
                    throw new RuntimeException(e);
                }
                LAST_REFRESH_MS = System.currentTimeMillis();
                logger.info("Token has been refreshed!");
                try {
                    Thread.sleep(TOKEN_EXPIRATION_TIME * 1000);
                } catch (InterruptedException e) {
                    logger.warn(String.format("Failed to sleep: %s", e.getMessage()));
                }
            }
        });
    }

    private void showVersionOutdatedDialog() {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(
                null,
                "Please update to the latest version to resume app functioning.",
                "Version Outdated",
                JOptionPane.WARNING_MESSAGE
            );
        });
    }
}
