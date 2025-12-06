package com.someone.valvoicebackend;

import com.google.gson.annotations.SerializedName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Record class for handling Riot Games authentication responses.
 * Used in the XMPP bridge authentication flow.
 */
public record EntitlementsTokenResponse(
    @SerializedName("accessToken") String accessToken,
    @SerializedName("entitlements") String[] entitlements,
    @SerializedName("issuer") String issuer,
    @SerializedName("sub") String subject,
    @SerializedName("token") String token
) {
    private static final Logger logger = LoggerFactory.getLogger(EntitlementsTokenResponse.class);

    /**
     * Compact constructor for validation and defaults
     */
    public EntitlementsTokenResponse {
        if (accessToken == null) {
            logger.warn("Access token is null, using empty string");
            accessToken = "";
        }
        if (entitlements == null) {
            logger.debug("No entitlements provided, using empty array");
            entitlements = new String[0];
        }
        if (issuer == null) {
            logger.debug("No issuer specified, using default");
            issuer = "riot-games";
        }
        if (subject == null) {
            logger.warn("No subject provided in token response");
            subject = "";
        }
        if (token == null) {
            logger.warn("Entitlements token is null");
            token = "";
        }
    }

    /**
     * Checks if this token response is valid for authentication
     */
    public boolean isValid() {
        return !accessToken.isEmpty() && !token.isEmpty();
    }

    /**
     * Gets a bearer token header value for API requests
     */
    public String getBearerToken() {
        return "Bearer " + accessToken;
    }

    /**
     * Gets the Riot entitlements token header value
     */
    public String getEntitlementsToken() {
        return token;
    }
}
