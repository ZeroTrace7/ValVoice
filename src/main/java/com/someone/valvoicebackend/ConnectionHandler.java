package com.someone.valvoicebackend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * ConnectionHandler centralizes creation and reuse of a trust-all HttpClient for the
 * local Riot API (or other localhost HTTPS endpoints with self-signed certificates).
 *
 * <p><b>TODO(v1.1): VN Hybrid Identity Subsystem</b></p>
 * <p>This class is part of the ValorantNarrator reference architecture for authenticated
 * Riot Local API access. It is preserved for v1.1 Name Resolution feature (PUUID → Display Name).
 * Currently dormant - not wired into v1.0 runtime.</p>
 *
 * Current scope:
 *  - Provide a singleton trust-all HttpClient (HTTP/1.1 + HTTP/2 capable)
 *  - Helper methods to build and send basic JSON or plain text requests
 *  - Convenience for Basic auth header creation (e.g. riot:lockfilePassword)
 *
 * Future enhancements (not yet implemented):
 *  - Connection pooling / per-host clients
 *  - Rate limiting / backoff
 *  - Metrics & retry policies
 *  - WebSocket / XMPP bridging (if needed later)
 *
 * @deprecated Dormant for v1.0 Golden Build. Reserved for v1.1 Name Resolution feature.
 */
@Deprecated(since = "1.0", forRemoval = false)
public final class ConnectionHandler {
    private static final Logger logger = LoggerFactory.getLogger(ConnectionHandler.class);
    private static final ConnectionHandler INSTANCE = new ConnectionHandler();

    private volatile HttpClient insecureClient; // lazily initialized
    private volatile InstantMetadata lastInit;

    private record InstantMetadata(long epochMillis) {}

    private ConnectionHandler() {}

    public static ConnectionHandler getInstance() { return INSTANCE; }

    /**
     * Returns (and lazily creates) an HttpClient that trusts all certificates. Used only for
     * local loopback Riot endpoints where certificate pinning is not required.
     */
    public HttpClient insecureClient() {
        HttpClient c = insecureClient;
        if (c != null) return c;
        synchronized (this) {
            if (insecureClient == null) {
                insecureClient = buildTrustAllClient();
                lastInit = new InstantMetadata(System.currentTimeMillis());
                logger.info("Initialized trust-all HttpClient for local API usage");
            }
            return insecureClient;
        }
    }

    private HttpClient buildTrustAllClient() {
        try {
            TrustManager[] trustAll = new TrustManager[]{ new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new SecureRandom());
            return HttpClient.newBuilder()
                    .sslContext(ctx)
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            logger.warn("Falling back to default HttpClient (could not init trust-all): {}", e.toString());
            return HttpClient.newHttpClient();
        }
    }

    /**
     * Construct a Basic auth header value (e.g., for riot:password combos).
     */
    public String basicAuthHeader(String username, String password) {
        if (username == null) username = "";
        if (password == null) password = "";
        String token = username + ":" + password;
        String encoded = Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    /**
     * Helper to build a request with optional headers.
     */
    public HttpRequest buildGet(String url, Map<String,String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(url)).GET();
        if (headers != null) headers.forEach(b::header);
        return b.build();
    }

    public HttpRequest buildPost(String url, String body, String contentType, Map<String,String> headers) {
        if (contentType == null) contentType = "application/json";
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8))
                .header("Content-Type", contentType);
        if (headers != null) headers.forEach(b::header);
        return b.build();
    }

    /**
     * Send request returning body on 2xx else empty. Caller responsible for parsing.
     */
    public Optional<String> sendForBody(HttpRequest request) {
        try {
            HttpResponse<String> resp = insecureClient().send(request, HttpResponse.BodyHandlers.ofString());
            int sc = resp.statusCode();
            if (sc >= 200 && sc < 300) return Optional.ofNullable(resp.body());
            logger.debug("Non-success status {} for {} body={} headers={}", sc, request.uri(), abbreviate(resp.body()), resp.headers().map());
        } catch (Exception e) {
            logger.debug("HTTP send failed: {} -> {}", request.uri(), e.toString());
        }
        return Optional.empty();
    }

    /**
     * Raw response (caller inspects status code directly).
     */
    public Optional<HttpResponse<String>> send(HttpRequest request) {
        try {
            return Optional.of(insecureClient().send(request, HttpResponse.BodyHandlers.ofString()));
        } catch (Exception e) {
            logger.debug("HTTP send failed: {} -> {}", request.uri(), e.toString());
            return Optional.empty();
        }
    }

    private String abbreviate(String s) {
        if (s == null) return null;
        return s.length() <= 120 ? s : s.substring(0,117) + "...";
    }

    /**
     * Build a local Riot base URL from protocol+port (host fixed to 127.0.0.1).
     */
    public String buildLocalBase(String protocol, int port) {
        if (protocol == null || protocol.isBlank()) protocol = "https";
        return protocol + "://127.0.0.1:" + port;
    }

    /**
     * Quick probe utility (GET) returning true if a 2xx was received.
     */
    public boolean probe(String url) {
        return send(buildGet(url, Map.of())).map(r -> r.statusCode() >= 200 && r.statusCode() < 300).orElse(false);
    }
}

