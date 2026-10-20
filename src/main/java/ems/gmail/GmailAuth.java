package ems.gmail;

import ems.exception.AppException;

import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import com.sun.net.httpserver.HttpServer;

/**
 * Handles Google OAuth 2.0 authorization for Gmail.
 * Supports environment variables, saved refresh tokens, and desktop browser OAuth flow.
 */
public class GmailAuth {

    private static final String DEFAULT_AUTH_URI = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final String SCOPES = "https://mail.google.com/ https://www.googleapis.com/auth/userinfo.email";
    private static final int CALLBACK_PORT = 8888;
    private static final String REDIRECT_URI = "http://localhost:" + CALLBACK_PORT + "/callback";
    private static final Path TOKEN_STORE = Paths.get(System.getProperty("user.home"), ".email_app_tokens.json");

    private final HttpClient httpClient;
    private String accessToken;
    private String refreshToken;
    private Instant tokenExpiry = Instant.EPOCH;

    public GmailAuth() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Obtains a valid access token. Reuses cached token, refreshes if expired,
     * or triggers interactive OAuth.
     */
    public synchronized String getValidAccessToken() throws AppException {
        // 1. Direct environment variable override
        String envToken = System.getenv("GMAIL_ACCESS_TOKEN");
        if (envToken != null && !envToken.isBlank()) {
            return envToken.trim();
        }

        // 2. Already in memory and not expired
        if (accessToken != null && Instant.now().isBefore(tokenExpiry.minusSeconds(60))) {
            return accessToken;
        }

        // 3. Load from disk if available
        loadSavedTokens();
        if (accessToken != null && Instant.now().isBefore(tokenExpiry.minusSeconds(60))) {
            return accessToken;
        }

        // 4. Attempt refresh with refresh token
        String clientId = getClientId();
        String clientSecret = getClientSecret();
        if (refreshToken != null && !refreshToken.isBlank() && clientId != null && clientSecret != null) {
            try {
                refreshAccessToken(clientId, clientSecret);
                return accessToken;
            } catch (Exception e) {
                // Refresh failed; fall through to browser login
            }
        }

        // 5. If credentials are fully missing, check if developer test mode is allowed
        if (clientId == null || clientSecret == null) {
            String testEmail = System.getenv("GMAIL_TEST_ACCOUNT");
            if (testEmail != null && !testEmail.isBlank()) {
                this.accessToken = "dev-mock-token-" + testEmail;
                this.tokenExpiry = Instant.now().plusSeconds(3600);
                return this.accessToken;
            }
            throw new AppException(
                    "Google OAuth credentials not configured. Please set GMAIL_CLIENT_ID and GMAIL_CLIENT_SECRET " +
                    "in environment variables, or provide GMAIL_ACCESS_TOKEN."
            );
        }

        // 6. Interactive browser login
        performBrowserOAuth(clientId, clientSecret);
        return accessToken;
    }

    public synchronized void clearTokens() {
        this.accessToken = null;
        this.refreshToken = null;
        this.tokenExpiry = Instant.EPOCH;
        try {
            Files.deleteIfExists(TOKEN_STORE);
        } catch (IOException ignored) {
        }
    }

    private void performBrowserOAuth(String clientId, String clientSecret) throws AppException {
        CompletableFuture<String> authCodeFuture = new CompletableFuture<>();
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(CALLBACK_PORT), 0);
        } catch (IOException e) {
            throw new AppException("Failed to start local OAuth callback receiver on port " + CALLBACK_PORT, e);
        }

        server.createContext("/callback", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            String code = null;
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] pair = param.split("=");
                    if (pair.length == 2 && "code".equals(pair[0])) {
                        code = pair[1];
                        break;
                    }
                }
            }

            String response;
            if (code != null) {
                authCodeFuture.complete(code);
                response = "<html><body><h2>Gmail Authorization Successful!</h2><p>You can return to Maildesk now.</p></body></html>";
            } else {
                authCodeFuture.completeExceptionally(new AppException("Authorization cancelled or missing code."));
                response = "<html><body><h2>Authorization Failed</h2><p>Please try again.</p></body></html>";
            }

            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        server.start();

        try {
            String authUrl = DEFAULT_AUTH_URI +
                    "?client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8) +
                    "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8) +
                    "&response_type=code" +
                    "&scope=" + URLEncoder.encode(SCOPES, StandardCharsets.UTF_8) +
                    "&access_type=offline" +
                    "&prompt=consent";

            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(authUrl));
            } else {
                System.out.println("Please open this URL in your browser to authorize Gmail:\n" + authUrl);
            }

            String authCode = authCodeFuture.get(120, TimeUnit.SECONDS);
            exchangeCodeForTokens(authCode, clientId, clientSecret);

        } catch (Exception e) {
            throw new AppException("Google authorization failed or timed out: " + e.getMessage(), e);
        } finally {
            server.stop(1);
        }
    }

    private void exchangeCodeForTokens(String code, String clientId, String clientSecret) throws AppException {
        String body = "code=" + URLEncoder.encode(code, StandardCharsets.UTF_8) +
                "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8) +
                "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8) +
                "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8) +
                "&grant_type=authorization_code";

        executeTokenRequest(body);
    }

    private void refreshAccessToken(String clientId, String clientSecret) throws AppException {
        String body = "refresh_token=" + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8) +
                "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8) +
                "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8) +
                "&grant_type=refresh_token";

        executeTokenRequest(body);
    }

    private void executeTokenRequest(String formBody) throws AppException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DEFAULT_TOKEN_URI))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new AppException("Token exchange failed with HTTP " + response.statusCode() + ": " + response.body());
            }

            Map<String, Object> data = SimpleJson.parseObject(response.body());
            this.accessToken = SimpleJson.getString(data, "access_token");
            String newRefresh = SimpleJson.getString(data, "refresh_token");
            if (newRefresh != null && !newRefresh.isBlank()) {
                this.refreshToken = newRefresh;
            }

            long expiresIn = 3600;
            Object exp = data.get("expires_in");
            if (exp instanceof Number n) {
                expiresIn = n.longValue();
            }
            this.tokenExpiry = Instant.now().plusSeconds(expiresIn);

            saveTokens();

        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException("Failed to communicate with Google OAuth service: " + e.getMessage(), e);
        }
    }

    private void saveTokens() {
        try {
            StringBuilder sb = new StringBuilder("{");
            sb.append("\"accessToken\":\"").append(SimpleJson.escapeString(accessToken)).append("\",");
            sb.append("\"refreshToken\":\"").append(SimpleJson.escapeString(refreshToken)).append("\",");
            sb.append("\"tokenExpiry\":").append(tokenExpiry.toEpochMilli());
            sb.append("}");
            Files.writeString(TOKEN_STORE, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private void loadSavedTokens() {
        if (!Files.exists(TOKEN_STORE)) return;
        try {
            String content = Files.readString(TOKEN_STORE, StandardCharsets.UTF_8);
            Map<String, Object> data = SimpleJson.parseObject(content);
            this.accessToken = SimpleJson.getString(data, "accessToken");
            this.refreshToken = SimpleJson.getString(data, "refreshToken");
            Object exp = data.get("tokenExpiry");
            if (exp instanceof Number n) {
                this.tokenExpiry = Instant.ofEpochMilli(n.longValue());
            }
        } catch (Exception ignored) {
        }
    }

    private String getClientId() {
        String id = System.getenv("GMAIL_CLIENT_ID");
        if (id == null || id.isBlank()) id = System.getenv("GOOGLE_CLIENT_ID");
        if (id != null && !id.isBlank()) return id.trim();
        return readFromCredentialsFile("client_id");
    }

    private String getClientSecret() {
        String secret = System.getenv("GMAIL_CLIENT_SECRET");
        if (secret == null || secret.isBlank()) secret = System.getenv("GOOGLE_CLIENT_SECRET");
        if (secret != null && !secret.isBlank()) return secret.trim();
        return readFromCredentialsFile("client_secret");
    }

    private String readFromCredentialsFile(String key) {
        for (String filename : new String[] {"credentials.json", "client_secret.json", "client_secrets.json"}) {
            Path p = Paths.get(filename);
            if (Files.exists(p)) {
                try {
                    String content = Files.readString(p, StandardCharsets.UTF_8);
                    Map<String, Object> root = SimpleJson.parseObject(content);
                    Map<String, Object> installed = SimpleJson.getObject(root, "installed");
                    if (installed.isEmpty()) {
                        installed = SimpleJson.getObject(root, "web");
                    }
                    String val = SimpleJson.getString(installed, key);
                    if (val != null && !val.isBlank()) {
                        return val.trim();
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }
}
