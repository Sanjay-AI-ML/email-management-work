package ems.gmail;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ems.exception.AppException;
import java.awt.Desktop;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** OAuth Authorization Code + PKCE using the system browser and a loopback-only callback. */
public final class OAuthDesktopClient implements AccessTokenProvider {
    static final String SCOPE = "https://www.googleapis.com/auth/gmail.modify";
    private static final URI GOOGLE_AUTHORIZE = URI.create("https://accounts.google.com/o/oauth2/v2/auth");
    private static final URI GOOGLE_TOKEN = URI.create("https://oauth2.googleapis.com/token");
    private final Path credentialsFile;
    private final TokenStore store;
    private final HttpClient http;
    private final URI authorizeEndpoint;
    private final URI tokenEndpoint;
    private final BrowserOpener browser;
    private final Duration timeout;
    private String clientId;
    private String clientSecret;
    private String accessToken;
    private String refreshToken;
    private Instant expiresAt = Instant.EPOCH;
    private boolean ignoreSavedTokens;

    public OAuthDesktopClient(Path credentialsFile, Path tokenDirectory) {
        this(credentialsFile, tokenDirectory,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build(),
                GOOGLE_AUTHORIZE, GOOGLE_TOKEN, OAuthDesktopClient::openBrowser, Duration.ofMinutes(3));
    }

    OAuthDesktopClient(Path credentialsFile, Path tokenDirectory, HttpClient http,
            URI authorizeEndpoint, URI tokenEndpoint, BrowserOpener browser, Duration timeout) {
        this.credentialsFile = credentialsFile;
        this.store = new TokenStore(tokenDirectory);
        this.http = http;
        this.authorizeEndpoint = authorizeEndpoint;
        this.tokenEndpoint = tokenEndpoint;
        this.browser = browser;
        this.timeout = timeout;
    }

    @Override public synchronized void authorize() throws AppException {
        readCredentials();
        if (accessToken != null && !expiresAt.isBefore(Instant.now().plusSeconds(60))) return;
        if (!ignoreSavedTokens && accessToken == null) loadTokens();
        if (accessToken != null && !expiresAt.isBefore(Instant.now().plusSeconds(60))) return;
        if (refreshToken != null) {
            try {
                refreshAccessToken();
                return;
            } catch (AuthorizationExpired expired) {
                // Revoked/expired grants require a new explicit authorization in the browser.
            }
        }
        authorizeInBrowser();
    }

    @Override public synchronized String accessToken() throws AppException {
        if (accessToken == null) throw new AppException("Connect a Gmail account first.");
        if (expiresAt.isBefore(Instant.now().plusSeconds(60))) return refreshAccessToken();
        return accessToken;
    }

    @Override public synchronized String refreshAccessToken() throws AppException {
        if (refreshToken == null) {
            forgetTokens();
            throw new AuthorizationExpired();
        }
        Map<String, String> form = clientFields();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        applyTokens(exchangeToken(form));
        return accessToken;
    }

    @Override public synchronized void disconnect() { forgetTokens(); }

    private void readCredentials() throws AppException {
        if (!Files.isRegularFile(credentialsFile)) {
            throw new AppException("Gmail setup is required. Enable the Gmail API, create a Desktop app "
                    + "OAuth client in Google Cloud, and save its downloaded JSON as "
                    + credentialsFile.toAbsolutePath() + ". See README.md for setup steps.");
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(credentialsFile,
                    StandardCharsets.UTF_8)).getAsJsonObject();
            if (!root.has("installed")) {
                throw new AppException("Use Google OAuth credentials for a Desktop app, not a Web application.");
            }
            JsonObject installed = root.getAsJsonObject("installed");
            clientId = installed.get("client_id").getAsString();
            clientSecret = installed.has("client_secret") ? installed.get("client_secret").getAsString() : "";
            if (clientId.isBlank()) throw new IllegalArgumentException("Missing client ID");
        } catch (AppException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new AppException("The Gmail credentials file could not be read. Download new Desktop app credentials.", e);
        }
    }

    private void loadTokens() throws AppException {
        try {
            JsonObject data = store.load();
            if (data == null || !clientId.equals(string(data, "client_id"))) return;
            accessToken = string(data, "access_token");
            refreshToken = string(data, "refresh_token");
            expiresAt = data.has("expires_at") ? Instant.ofEpochMilli(data.get("expires_at").getAsLong())
                    : Instant.EPOCH;
        } catch (IOException | RuntimeException e) {
            throw new AppException("Could not read the protected Gmail sign-in cache. Check permissions on the token directory.", e);
        }
    }

    private void authorizeInBrowser() throws AppException {
        HttpServer server = null;
        try {
            String state = randomToken();
            String verifier = randomToken();
            String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            URI redirect = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/oauth2callback");
            CompletableFuture<String> code = new CompletableFuture<>();
            server.createContext("/oauth2callback", exchange -> handleCallback(exchange, state, code));
            server.start();
            Map<String, String> form = new LinkedHashMap<>();
            form.put("client_id", clientId);
            form.put("redirect_uri", redirect.toString());
            form.put("response_type", "code");
            form.put("scope", SCOPE);
            form.put("access_type", "offline");
            form.put("prompt", "consent select_account");
            form.put("state", state);
            form.put("code_challenge", challenge);
            form.put("code_challenge_method", "S256");
            browser.open(URI.create(authorizeEndpoint + "?" + formEncode(form)));
            String authCode = code.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            Map<String, String> tokenForm = clientFields();
            tokenForm.put("grant_type", "authorization_code");
            tokenForm.put("code", authCode);
            tokenForm.put("redirect_uri", redirect.toString());
            tokenForm.put("code_verifier", verifier);
            applyTokens(exchangeToken(tokenForm));
        } catch (AppException e) {
            throw e;
        } catch (TimeoutException e) {
            throw new AppException("Google sign-in timed out. Click Connect Gmail and complete sign-in within three minutes.", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AppException app) throw app;
            throw new AppException("Google sign-in could not be completed. Please try again.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException("Google sign-in was interrupted.", e);
        } catch (Exception e) {
            throw new AppException("Could not open Google sign-in. Check that a default web browser is available "
                    + "and local loopback connections are allowed.", e);
        } finally {
            if (server != null) server.stop(0);
        }
    }

    private static void handleCallback(HttpExchange exchange, String state, CompletableFuture<String> result)
            throws IOException {
        int status = 400;
        String message = "Invalid sign-in response. Return to the application and try again.";
        String authorizationCode = null;
        AppException authorizationFailure = null;
        try {
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            String returnedState = query.getOrDefault("state", "");
            boolean valid = exchange.getRequestMethod().equals("GET")
                    && exchange.getRequestURI().getPath().equals("/oauth2callback")
                    && MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),
                            returnedState.getBytes(StandardCharsets.UTF_8));
            if (valid && query.containsKey("error")) {
                authorizationFailure = new AppException("Google sign-in was cancelled or access was denied.");
                message = "Sign-in was not completed. You can close this tab and return to the application.";
            } else if (valid && !query.getOrDefault("code", "").isBlank()) {
                status = 200;
                message = "Authorization received. You can close this tab and return to the application.";
                authorizationCode = query.get("code");
            }
        } catch (IllegalArgumentException malformedQuery) {
            // Reject malformed callbacks without revealing query data or cancelling a legitimate response.
        }
        byte[] page = ("<!doctype html><html><head><meta charset='utf-8'><title>Gmail connection</title>"
                + "</head><body><p>" + message + "</p></body></html>").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'");
        try {
            exchange.sendResponseHeaders(status, page.length);
            try (var output = exchange.getResponseBody()) { output.write(page); }
        } finally {
            // Complete only after the browser response is written; otherwise a fast denial
            // can stop the listener while it is still displaying the return-to-app page.
            if (authorizationCode != null) result.complete(authorizationCode);
            else if (authorizationFailure != null) result.completeExceptionally(authorizationFailure);
        }
    }

    private JsonObject exchangeToken(Map<String, String> form) throws AppException {
        HttpRequest request = HttpRequest.newBuilder(tokenEndpoint).timeout(Duration.ofSeconds(45))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formEncode(form))).build();
        try {
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                if (response.body().contains("invalid_grant")) {
                    forgetTokens();
                    throw new AuthorizationExpired();
                }
                throw new AppException("Google could not authorize Gmail (HTTP " + response.statusCode()
                        + "). Check your OAuth client configuration and test-user access.");
            }
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (IOException e) {
            throw new AppException("Could not reach Google's sign-in server. Check your internet connection.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException("Google sign-in was interrupted.", e);
        } catch (RuntimeException e) {
            throw new AppException("Google returned an unreadable sign-in response.", e);
        }
    }

    private void applyTokens(JsonObject data) throws AppException {
        try {
            String newToken = string(data, "access_token");
            if (newToken == null || newToken.isBlank()) throw new IllegalArgumentException("Missing token");
            if (data.has("scope") && !java.util.Arrays.asList(data.get("scope").getAsString().split(" "))
                    .contains(SCOPE)) {
                throw new AppException("Gmail permission was not granted. Connect again and allow Gmail access.");
            }
            accessToken = newToken;
            String newRefresh = string(data, "refresh_token");
            if (newRefresh != null && !newRefresh.isBlank()) refreshToken = newRefresh;
            long expires = data.has("expires_in") ? data.get("expires_in").getAsLong() : 3600;
            expiresAt = Instant.now().plusSeconds(Math.max(1, expires));
            JsonObject cache = new JsonObject();
            cache.addProperty("client_id", clientId);
            cache.addProperty("access_token", accessToken);
            if (refreshToken != null) cache.addProperty("refresh_token", refreshToken);
            cache.addProperty("expires_at", expiresAt.toEpochMilli());
            store.save(cache);
            ignoreSavedTokens = false;
        } catch (AppException e) {
            throw e;
        } catch (IOException e) {
            throw new AppException("Gmail authorized, but the protected sign-in cache could not be saved. "
                    + "Check permissions on the token directory.", e);
        } catch (RuntimeException e) {
            throw new AppException("Google returned incomplete sign-in credentials. Connect again.", e);
        }
    }

    private Map<String, String> clientFields() {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", clientId);
        if (clientSecret != null && !clientSecret.isBlank()) form.put("client_secret", clientSecret);
        return form;
    }

    private void forgetTokens() {
        accessToken = null;
        refreshToken = null;
        expiresAt = Instant.EPOCH;
        ignoreSavedTokens = true;
        try { store.clear(); } catch (IOException ignored) {
            // The current process will never reuse a cache after disconnect, even if deletion fails.
        }
    }

    static Map<String, String> parseQuery(String query) {
        Map<String, String> values = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return values;
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = URLDecoder.decode(parts.length == 2 ? parts[1] : "", StandardCharsets.UTF_8);
            if (values.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Duplicate parameter");
        }
        return values;
    }

    static String formEncode(Map<String, String> form) {
        return form.entrySet().stream().map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.joining("&"));
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null;
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void openBrowser(URI uri) throws IOException {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            throw new IOException("No default browser is available.");
        }
        Desktop.getDesktop().browse(uri);
    }

    @FunctionalInterface
    interface BrowserOpener { void open(URI uri) throws Exception; }

    private static final class AuthorizationExpired extends AppException {
        private static final long serialVersionUID = 1L;
        private AuthorizationExpired() { super("Gmail authorization expired or was revoked. Connect Gmail again."); }
    }
}
