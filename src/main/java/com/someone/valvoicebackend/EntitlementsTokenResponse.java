package com.someone.valvoicebackend;

import com.google.gson.annotations.SerializedName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Record class for handling Riot Games authentication responses.
 * Used in the XMPP bridge authentication flow.
 *
 * <p><b>TODO(v1.1): VN Hybrid Identity Subsystem</b></p>
 * <p>This record is part of the ValorantNarrator reference architecture for parsing
 * identity tokens during Name Resolution (PUUID → Display Name).
 * Currently dormant - not wired into v1.0 runtime.</p>
 *
 * @deprecated Dormant for v1.0 Golden Build. Reserved for v1.1 Name Resolution feature.
 */
@Deprecated(since = "1.0", forRemoval = false)
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
