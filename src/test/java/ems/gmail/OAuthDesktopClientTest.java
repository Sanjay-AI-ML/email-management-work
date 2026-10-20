package ems.gmail;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ems.exception.AppException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OAuthDesktopClientTest {
    @TempDir Path temp;
    private HttpServer server;
    private URI tokenUri;
    private final HttpClient http = HttpClient.newHttpClient();
    private Path credentials;
    private Path tokens;
    private final AtomicReference<TokenHandler> tokenHandler = new AtomicReference<>();

    @BeforeEach void start() throws Exception {
        credentials = temp.resolve("credentials.json");
        tokens = temp.resolve("tokens");
        Files.writeString(credentials, "{\"installed\":{\"client_id\":\"desktop-test-client\",\"client_secret\":\"test-secret\"}}");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            try { tokenHandler.get().handle(exchange); }
            catch (Throwable failure) { reply(exchange, 500, "{}"); }
        });
        server.start();
        tokenUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/token");
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void authorizationUsesPkceRejectsWrongStateAndReusesProtectedCache() throws Exception {
        AtomicReference<Map<String, String>> authorization = new AtomicReference<>();
        AtomicInteger browserCalls = new AtomicInteger();
        tokenHandler.set(exchange -> {
            Map<String, String> form = OAuthDesktopClient.parseQuery(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("authorization_code", form.get("grant_type"));
            assertEquals("auth-code", form.get("code"));
            assertEquals("desktop-test-client", form.get("client_id"));
            String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(form.get("code_verifier").getBytes(StandardCharsets.US_ASCII)));
            assertEquals(authorization.get().get("code_challenge"), challenge);
            reply(exchange, 200, "{\"access_token\":\"initial-access\",\"refresh_token\":\"refresh-value\",\"expires_in\":3600}");
        });
        OAuthDesktopClient client = client(uri -> {
            browserCalls.incrementAndGet();
            Map<String, String> query = OAuthDesktopClient.parseQuery(uri.getRawQuery());
            authorization.set(query);
            assertEquals("S256", query.get("code_challenge_method"));
            assertEquals(OAuthDesktopClient.SCOPE, query.get("scope"));
            assertEquals("offline", query.get("access_type"));
            assertTrue(query.get("prompt").contains("select_account"));
            URI callback = URI.create(query.get("redirect_uri"));
            assertEquals("127.0.0.1", callback.getHost());
            assertEquals(400, callback(callback, "state=wrong&code=attack").statusCode());
            assertEquals(200, callback(callback, "state=" + query.get("state") + "&code=auth-code").statusCode());
        });
        client.authorize();
        assertEquals("initial-access", client.accessToken());
        assertTrue(Files.exists(tokens.resolve("gmail-token.json")));
        OAuthDesktopClient restarted = client(uri -> fail("Valid stored token should not reopen browser"));
        restarted.authorize();
        assertEquals("initial-access", restarted.accessToken());
        assertEquals(1, browserCalls.get());
        tokenHandler.set(exchange -> {
            Map<String, String> form = OAuthDesktopClient.parseQuery(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("refresh_token", form.get("grant_type"));
            assertEquals("refresh-value", form.get("refresh_token"));
            reply(exchange, 200, "{\"access_token\":\"renewed-access\",\"expires_in\":3600}");
        });
        assertEquals("renewed-access", restarted.refreshAccessToken());
        assertTrue(Files.readString(tokens.resolve("gmail-token.json")).contains("refresh-value"));
        restarted.disconnect();
        assertFalse(Files.exists(tokens.resolve("gmail-token.json")));
        assertThrows(AppException.class, restarted::accessToken);
    }

    @Test void deniedAuthorizationDoesNotSaveTokens() {
        OAuthDesktopClient client = client(uri -> {
            Map<String, String> query = OAuthDesktopClient.parseQuery(uri.getRawQuery());
            callback(URI.create(query.get("redirect_uri")), "state=" + query.get("state") + "&error=access_denied");
        });
        AppException failure = assertThrows(AppException.class, client::authorize);
        assertTrue(failure.getMessage().contains("denied"));
        assertFalse(Files.exists(tokens.resolve("gmail-token.json")));
    }

    @Test void missingCredentialsExplainsDesktopSetup() throws Exception {
        Files.delete(credentials);
        AppException failure = assertThrows(AppException.class, () -> client(uri -> fail()).authorize());
        assertTrue(failure.getMessage().contains("Desktop app"));
        assertTrue(failure.getMessage().contains("README.md"));
    }

    @Test void rejectsWebClientCredentials() throws Exception {
        Files.writeString(credentials, "{\"web\":{\"client_id\":\"not-desktop\"}}");
        AppException failure = assertThrows(AppException.class, () -> client(uri -> fail()).authorize());
        assertTrue(failure.getMessage().contains("Desktop app"));
    }

    @Test void timesOutAndClosesLoopbackListener() {
        AtomicReference<URI> callback = new AtomicReference<>();
        OAuthDesktopClient client = new OAuthDesktopClient(credentials, tokens, http, tokenUri, tokenUri,
                uri -> callback.set(URI.create(OAuthDesktopClient.parseQuery(uri.getRawQuery()).get("redirect_uri"))),
                Duration.ofMillis(100));
        AppException failure = assertThrows(AppException.class, client::authorize);
        assertTrue(failure.getMessage().contains("timed out"));
        assertThrows(IOException.class, () -> http.send(HttpRequest.newBuilder(callback.get()).GET().build(),
                HttpResponse.BodyHandlers.ofString()));
    }

    @Test void revokedRefreshTokenIsForgottenWithoutEchoingProviderPayload() throws Exception {
        Files.createDirectories(tokens);
        Files.writeString(tokens.resolve("gmail-token.json"), "{\"client_id\":\"desktop-test-client\","
                + "\"access_token\":\"old\",\"refresh_token\":\"revoked\",\"expires_at\":0}");
        tokenHandler.set(exchange -> reply(exchange, 400, "{\"error\":\"invalid_grant\",\"private\":\"secret-value\"}"));
        OAuthDesktopClient client = client(uri -> {
            assertFalse(Files.exists(tokens.resolve("gmail-token.json")));
            Map<String, String> query = OAuthDesktopClient.parseQuery(uri.getRawQuery());
            callback(URI.create(query.get("redirect_uri")), "state=" + query.get("state") + "&error=access_denied");
        });
        AppException failure = assertThrows(AppException.class, client::authorize);
        assertFalse(failure.getMessage().contains("secret-value"));
        assertFalse(Files.exists(tokens.resolve("gmail-token.json")));
    }

    private OAuthDesktopClient client(OAuthDesktopClient.BrowserOpener browser) {
        return new OAuthDesktopClient(credentials, tokens, http, tokenUri, tokenUri, browser, Duration.ofSeconds(3));
    }

    private HttpResponse<String> callback(URI uri, String query) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(uri + "?" + query)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void reply(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
    }

    @FunctionalInterface private interface TokenHandler { void handle(HttpExchange exchange) throws Exception; }
}
