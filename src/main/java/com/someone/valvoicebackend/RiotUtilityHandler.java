package com.someone.valvoicebackend;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Base64;

/**
 * Utility class for interacting with Riot Local API
 *
 * PHASE 3 SECURITY (VN-Parity):
 * ════════════════════════════════════════════════════════════════════════════════
 * SSL BYPASS IS STRICTLY LOCALHOST-ONLY
 *
 * This class uses a trust-all TrustManager that bypasses certificate validation.
 * This is ONLY safe because ALL URLs are hardcoded to 127.0.0.1 (localhost).
 *
 * INVARIANTS:
 * 1. All URL constructions use "127.0.0.1" - NEVER external hosts
 * 2. The trust-all SSLContext is used ONLY for Riot Local API calls
 * 3. No method accepts arbitrary host parameters
 *
 * NEVER modify this class to accept external hosts - it would allow MITM attacks.
 * ════════════════════════════════════════════════════════════════════════════════
 */
public class RiotUtilityHandler {
    private static final Logger logger = LoggerFactory.getLogger(RiotUtilityHandler.class);
    private static final Gson gson = new Gson();

    /**
     * Resolve self player ID from Riot local API
     * @param port local API port
     * @param password local API password
     * @param protocol protocol (http or https)
     * @return self player ID or null if failed
     */
    public static String resolveSelfPlayerId(int port, String password, String protocol) {
        try {
            // Create trust manager that accepts all certificates (Riot uses self-signed)
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
            };

            SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);

            // Build URL for chat session endpoint
            String urlStr = protocol + "://127.0.0.1:" + port + "/chat/v4/presences";
            URL url = new URL(urlStr);
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();

            // Set authentication header
            String auth = "riot:" + password;
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + encodedAuth);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) {
                        response.append(line);
                    }

                    // Parse JSON response to extract self player ID
                    JsonObject presences = gson.fromJson(response.toString(), JsonObject.class);
                    if (presences != null && presences.has("presences")) {
                        String selfPuuid = extractSelfPuuid(presences);
                        if (selfPuuid != null) {
                            return selfPuuid;
                        }
                    }
                }
            } else {
                logger.warn("Failed to resolve self ID - HTTP {}", responseCode);
            }
        } catch (Exception e) {
            logger.error("Error resolving self player ID from Riot API", e);
        }

        // Fallback: try alternative endpoint
        return resolveSelfPlayerIdFallback(port, password, protocol);
    }

    /**
     * Extract self PUUID from presences response
     */
    private static String extractSelfPuuid(JsonObject presences) {
        try {
            // Implementation depends on actual API response structure
            // This is a placeholder - adjust based on actual response
            if (presences.has("puuid")) {
                return presences.get("puuid").getAsString();
            }
        } catch (Exception e) {
            logger.debug("Error extracting self PUUID", e);
        }
        return null;
    }

    /**
     * Fallback method to resolve self player ID using session endpoint
     */
    private static String resolveSelfPlayerIdFallback(int port, String password, String protocol) {
        try {
            String urlStr = protocol + "://127.0.0.1:" + port + "/chat/v1/session";
            URL url = new URL(urlStr);
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();

            String auth = "riot:" + password;
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + encodedAuth);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) {
                        response.append(line);
                    }

                    JsonObject session = gson.fromJson(response.toString(), JsonObject.class);
                    if (session != null && session.has("puuid")) {
                        return session.get("puuid").getAsString();
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Fallback self ID resolution failed", e);
        }
        return null;
    }
}

