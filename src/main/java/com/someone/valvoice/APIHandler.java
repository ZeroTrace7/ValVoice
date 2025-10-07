package com.someone.valvoice;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Handles authenticated requests to the Riot local client API (Valorant).
 * Uses credentials obtained via {@link LockFileHandler}.
 * <p>
 * Typical flow:
 *  1. loadLockfile(path)
 *  2. resolveSelfPlayerId() / getAuthToken() / getEntitlementsToken()
 */
public class APIHandler {
    private static final Logger logger = LoggerFactory.getLogger(APIHandler.class);
    private static final APIHandler INSTANCE = new APIHandler();

    private final Gson gson = new Gson();
    private final LockFileHandler lockFileHandler = new LockFileHandler();
    private HttpClient client;
    private String baseUrl; // e.g., https://127.0.0.1:12345

    private RiotClientDetails clientDetails;

    private APIHandler() {}

    public static APIHandler getInstance() { return INSTANCE; }

    /**
     * Attempt to read lockfile and prepare HTTP client.
     * @param lockFilePath path to Riot lockfile (usually %LOCALAPPDATA%/Riot Games/Riot Client/Config/lockfile)
     * @return true if loaded successfully.
     */
    public boolean loadLockfile(String lockFilePath) {
        if (!lockFileHandler.readLockFile(lockFilePath)) {
            logger.warn("Failed to read lockfile at {}", lockFilePath);
            return false;
        }
        baseUrl = lockFileHandler.getProtocol() + "://127.0.0.1:" + lockFileHandler.getPort();
        client = buildInsecureClient();
        logger.info("Lockfile loaded. Base URL: {}", baseUrl);
        return true;
    }

    private HttpClient buildInsecureClient() {
        try {
            TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new SecureRandom());
            return HttpClient.newBuilder()
                    .sslContext(ctx)
                    .build();
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            logger.error("Failed to initialize insecure SSL context", e);
            return HttpClient.newHttpClient();
        }
    }

    private String basicAuthHeader() {
        // Username is always 'riot' per Riot local API
        String token = "riot:" + lockFileHandler.getPassword();
        String encoded = Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    private HttpRequest.Builder baseRequest(String path) {
        if (client == null || baseUrl == null) throw new IllegalStateException("Lockfile not loaded");
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", basicAuthHeader())
                .header("Accept", "application/json");
    }

    private Optional<String> send(HttpRequest request) {
        try {
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return Optional.ofNullable(resp.body());
            }
            logger.debug("Request {} failed with status {} body {}", request.uri(), resp.statusCode(), resp.body());
            return Optional.empty();
        } catch (IOException | InterruptedException e) {
            logger.debug("HTTP request failed: {}", request.uri(), e);
            return Optional.empty();
        }
    }

    /**
     * Retrieve the local auth token (access token) from Riot client.
     */
    public Optional<String> getAuthToken() {
        return send(baseRequest("/riotclient/auth-token").GET().build())
                .flatMap(body -> {
                    try {
                        JsonObject o = gson.fromJson(body, JsonObject.class);
                        if (o != null && o.has("accessToken")) return Optional.of(o.get("accessToken").getAsString());
                    } catch (Exception e) { logger.debug("Failed parsing auth token JSON", e); }
                    return Optional.empty();
                });
    }

    /**
     * Retrieve entitlements token (used for many platform endpoints) via local API.
     */
    public Optional<String> getEntitlementsToken() {
        return send(baseRequest("/entitlements/v1/token").POST(HttpRequest.BodyPublishers.noBody()).build())
                .flatMap(body -> {
                    try {
                        JsonObject o = gson.fromJson(body, JsonObject.class);
                        if (o != null && o.has("entitlements_token")) return Optional.of(o.get("entitlements_token").getAsString());
                    } catch (Exception e) { logger.debug("Failed parsing entitlements token JSON", e); }
                    return Optional.empty();
                });
    }

    /**
     * Attempt to resolve the local player's PUUID from chat session endpoint.
     * If successful, returns it. (Endpoint may change in future patches.)
     */
    public Optional<String> resolveSelfPlayerId() {
        // Known local chat session endpoint (may return: {"puuid":"..."})
        Optional<String> body = send(baseRequest("/chat/v1/session").GET().build());
        if (body.isEmpty()) return Optional.empty();
        try {
            JsonObject o = gson.fromJson(body.get(), JsonObject.class);
            if (o != null && o.has("puuid")) return Optional.of(o.get("puuid").getAsString());
        } catch (Exception e) {
            logger.debug("Failed parsing chat session JSON", e);
        }
        return Optional.empty();
    }

    public Optional<String> rawGet(String path) {
        return send(baseRequest(path).GET().build());
    }

    /**
     * Fetch and cache Riot client / chat session info (puuid, region, client version if available).
     */
    public Optional<RiotClientDetails> fetchClientDetails() {
        JsonObject session = send(baseRequest("/chat/v1/session").GET().build())
                .flatMap(body -> {
                    try { return Optional.ofNullable(gson.fromJson(body, JsonObject.class)); }
                    catch (Exception e) { logger.debug("Failed parsing /chat/v1/session JSON", e); return Optional.empty(); }
                }).orElse(null);
        if (session == null) return Optional.empty();
        String puuid = getAsString(session, "puuid");
        String region = getAsString(session, "region");
        if (region == null && session.has("affinities")) {
            JsonObject aff = session.getAsJsonObject("affinities");
            region = getAsString(aff, "live");
        }
        String version = fetchClientVersion().orElse("unknown");
        clientDetails = new RiotClientDetails(puuid, region, version, Instant.now());
        return Optional.of(clientDetails);
    }

    private Optional<String> fetchClientVersion() {
        // Try known local endpoint; ignore failures.
        return send(baseRequest("/product-session/v1/external-sessions").GET().build())
                .flatMap(body -> {
                    try {
                        JsonObject root = gson.fromJson(body, JsonObject.class);
                        // Heuristic: iterate entries to find valorant version field
                        for (String key : root.keySet()) {
                            JsonObject app = root.getAsJsonObject(key);
                            if (app.has("productId") && "valorant".equalsIgnoreCase(getAsString(app, "productId"))) {
                                String ver = getAsString(app, "version");
                                if (ver != null) return Optional.of(ver);
                            }
                        }
                    } catch (Exception e) { logger.debug("Could not parse product-session version", e); }
                    return Optional.empty();
                });
    }

    private String getAsString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) return null;
        JsonElement el = obj.get(key);
        if (el.isJsonNull()) return null;
        return el.getAsString();
    }

    public Optional<RiotClientDetails> getClientDetails() { return Optional.ofNullable(clientDetails); }

    public boolean isReady() { return client != null; }

    public LockFileHandler getLockFileHandler() { return lockFileHandler; }
}
