package ems.gmail;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ems.exception.AppException;
import ems.model.EmailMessage;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GmailApiClientTest {
    private HttpServer server;
    private GmailApiClient gateway;
    private final FakeTokens tokens = new FakeTokens();
    private final AtomicReference<Handler> handler = new AtomicReference<>();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try { handler.get().handle(exchange); }
            catch (Throwable failure) { reply(exchange, 500, "{}"); }
        });
        server.start();
        gateway = new GmailApiClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                HttpClient.newHttpClient(), tokens, milliseconds -> { });
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void connectsToProfileWithoutExposingCredentials() throws Exception {
        handler.set(exchange -> {
            assertEquals("Bearer initial", exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("/profile", exchange.getRequestURI().getPath());
            reply(exchange, 200, "{\"emailAddress\":\"alice@gmail.com\"}");
        });
        assertEquals("alice@gmail.com", gateway.connect());
        assertTrue(tokens.authorized);
        gateway.disconnect();
        assertTrue(tokens.disconnected);
    }

    @Test void loadsAllPagesDeduplicatesIdsAndSkipsOnlyDeletedMessages() throws Exception {
        AtomicInteger rawReads = new AtomicInteger();
        handler.set(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/messages")) {
                var query = GmailAuth.parseQuery(exchange.getRequestURI().getRawQuery());
                assertEquals("true", query.get("includeSpamTrash"));
                assertEquals("-in:trash -in:drafts", query.get("q"));
                reply(exchange, 200, query.containsKey("pageToken")
                        ? "{\"messages\":[{\"id\":\"first\"},{\"id\":\"deleted\"},{\"id\":\"second\"}]}"
                        : "{\"messages\":[{\"id\":\"first\"}],\"nextPageToken\":\"page 2\"}");
            } else if (path.endsWith("deleted")) reply(exchange, 404, "{}");
            else {
                rawReads.incrementAndGet();
                reply(exchange, 200, rawMessage("Subject: Hello\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nReal body"));
            }
        });
        List<EmailMessage> messages = gateway.fetchMessages(42);
        assertEquals(List.of("first", "second"), messages.stream().map(EmailMessage::getGmailMessageId).toList());
        assertEquals(2, rawReads.get());
        assertEquals(42, messages.get(0).getAccountId());
        assertEquals(Instant.ofEpochMilli(1700000000000L), messages.get(0).getReceivedAt());
        assertTrue(messages.get(0).getGmailLabelIds().contains("INBOX"));
    }

    @Test void doesNotReturnPartialSnapshotWhenLaterFetchFails() {
        handler.set(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/messages")) {
                reply(exchange, 200, "{\"messages\":[{\"id\":\"first\"},{\"id\":\"second\"}]}");
            } else if (exchange.getRequestURI().getPath().endsWith("first")) {
                reply(exchange, 200, rawMessage("Subject: Fine\r\n\r\nFirst"));
            } else reply(exchange, 503, "{}");
        });
        assertThrows(AppException.class, () -> gateway.fetchMessages(1));
    }

    @Test void startupReadsBoundedRecentWindowsFromAllThreeFolders() throws Exception {
        java.util.Set<String> folders = new java.util.HashSet<>();
        AtomicInteger reads = new AtomicInteger();
        handler.set(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/messages")) {
                var query = GmailAuth.parseQuery(exchange.getRequestURI().getRawQuery());
                assertEquals("6", query.get("maxResults"));
                assertFalse(query.containsKey("pageToken"));
                folders.add(query.get("labelIds"));
                reply(exchange, 200, "{\"messages\":[{\"id\":\"shared\"}],\"nextPageToken\":\"older-mail\"}");
            } else {
                reads.incrementAndGet();
                reply(exchange, 200, rawMessage("Subject: Recent\r\n\r\nBody"));
            }
        });
        assertEquals(1, gateway.fetchRecentMessages(42, 6).size());
        assertEquals(java.util.Set.of("INBOX", "SENT", "SPAM"), folders);
        assertEquals(1, reads.get(), "Messages in multiple folders are read once");
    }

    @Test void refreshesOnceOnUnauthorizedResponse() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        handler.set(exchange -> {
            if (calls.incrementAndGet() == 1) reply(exchange, 401, "{}");
            else {
                assertEquals("Bearer refreshed", exchange.getRequestHeaders().getFirst("Authorization"));
                reply(exchange, 200, "{\"emailAddress\":\"alice@gmail.com\"}");
            }
        });
        assertEquals("alice@gmail.com", gateway.connect());
        assertEquals(1, tokens.refreshes);
        assertEquals(2, calls.get());
    }

    @Test void neverRetriesAnUnconfirmedSend() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        handler.set(exchange -> {
            calls.incrementAndGet();
            reply(exchange, 503, "{\"error\":\"private provider information\"}");
        });
        AppException failure = assertThrows(AppException.class,
                () -> gateway.sendMessage("alice@gmail.com", "bob@example.org", "Hi", "Text"));
        assertEquals(1, calls.get());
        assertFalse(failure.getMessage().contains("private provider"));
        assertTrue(failure.getMessage().contains("Check Gmail Sent before retrying"));
    }

    @Test void sendsUnicodeMimeAndUpdatesReadAndSpamLabels() throws Exception {
        AtomicReference<JsonObject> sent = new AtomicReference<>();
        AtomicReference<JsonObject> modification = new AtomicReference<>();
        handler.set(exchange -> {
            JsonObject json = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            if (exchange.getRequestURI().getPath().endsWith("/send")) sent.set(json);
            else modification.set(json);
            reply(exchange, 200, "{\"id\":\"newmail\"}");
        });
        assertEquals("newmail", gateway.sendMessage("alice@gmail.com", "bob@example.org", "Meeting – தமிழ்", "Hello\nவணக்கம்"));
        MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(Base64.getUrlDecoder().decode(sent.get().get("raw").getAsString())));
        assertEquals("Meeting – தமிழ்", mime.getSubject());
        assertEquals("Hello\r\nவணக்கம்", mime.getContent().toString().replace("\r\n", "\n").replace("\n", "\r\n"));
        assertEquals("bob@example.org", mime.getAllRecipients()[0].toString());
        gateway.setRead("newmail");
        assertTrue(modification.get().getAsJsonArray("removeLabelIds").toString().contains("UNREAD"));
        gateway.setSpam("newmail", true);
        assertTrue(modification.get().getAsJsonArray("addLabelIds").toString().contains("SPAM"));
        assertTrue(modification.get().getAsJsonArray("removeLabelIds").toString().contains("INBOX"));
        gateway.setSpam("newmail", false);
        assertTrue(modification.get().getAsJsonArray("addLabelIds").toString().contains("INBOX"));
        assertTrue(modification.get().getAsJsonArray("removeLabelIds").toString().contains("SPAM"));
    }

    @Test void rejectsHeaderInjectionAndPathInjectionBeforeNetwork() {
        assertThrows(AppException.class,
                () -> gateway.sendMessage("alice@gmail.com", "bob@example.org\r\nBcc: victim@example.org", "Hi", "Text"));
        assertThrows(AppException.class,
                () -> gateway.sendMessage("alice@gmail.com", "bob@example.org", "Hi\r\nBcc: victim@example.org", "Text"));
        assertThrows(AppException.class, () -> gateway.getMessage(1, "../profile"));
    }

    @Test void choosesPlainAlternativeAndExcludesAttachments() throws Exception {
        String raw = "From: Alice <alice@gmail.com>\r\nTo: bob@example.org\r\n"
                + "Subject: MIME test\r\nMIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=mix\r\n\r\n"
                + "--mix\r\nContent-Type: multipart/alternative; boundary=alt\r\n\r\n"
                + "--alt\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nPlain body\r\n"
                + "--alt\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n<p>HTML duplicate</p>\r\n--alt--\r\n"
                + "--mix\r\nContent-Type: text/plain\r\nContent-Disposition: attachment; filename=private.txt\r\n\r\n"
                + "ATTACHMENT SHOULD NOT APPEAR\r\n--mix--\r\n";
        EmailMessage message = MimeMessages.parse(1, "id", encode(raw), Instant.EPOCH, java.util.Set.of());
        assertEquals("alice@gmail.com", message.getSenderEmail());
        assertEquals(List.of("bob@example.org"), message.getRecipientEmails());
        assertEquals("Plain body", message.getBodyText().strip());
    }

    @Test void convertsHtmlToInertTextAndDecodesEntities() throws Exception {
        String raw = "Content-Type: text/html; charset=UTF-8\r\n\r\n"
                + "<html><body><p>Hello &amp; welcome</p><script>alert('bad')</script>"
                + "<img src='https://tracker.invalid/pixel'><p>Second line</p></body></html>";
        String body = MimeMessages.parse(1, "id", encode(raw), Instant.EPOCH, java.util.Set.of()).getBodyText();
        assertTrue(body.contains("Hello & welcome"));
        assertTrue(body.contains("Second line"));
        assertFalse(body.contains("alert"));
        assertFalse(body.contains("tracker"));
    }

    @Test void repeatedPageTokenAbortsInsteadOfLoopingForever() {
        handler.set(exchange -> reply(exchange, 200, "{\"nextPageToken\":\"same\"}"));
        assertThrows(AppException.class, () -> gateway.fetchMessages(1));
    }

    @Test void providerErrorsAreActionableAndDoNotEchoResponse() {
        handler.set(exchange -> reply(exchange, 403, "{\"error\":\"secret-token-value\"}"));
        AppException failure = assertThrows(AppException.class, gateway::connect);
        assertTrue(failure.getMessage().contains("Enable the Gmail API"));
        assertFalse(failure.getMessage().contains("secret-token-value"));
    }

    @Test void retriesRateLimitedReadsWithoutRefreshingAuthorization() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        java.util.ArrayList<Long> delays = new java.util.ArrayList<>();
        gateway = new GmailApiClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                HttpClient.newHttpClient(), tokens, delays::add);
        handler.set(exchange -> {
            if (calls.incrementAndGet() <= 2) reply(exchange, 403,
                    "{\"error\":{\"errors\":[{\"reason\":\"userRateLimitExceeded\"}]}}");
            else reply(exchange, 200, "{\"emailAddress\":\"alice@gmail.com\"}");
        });
        assertEquals("alice@gmail.com", gateway.connect());
        assertEquals(List.of(1000L, 2000L), delays);
        assertEquals(0, tokens.refreshes);
    }

    @Test void exhaustedQuotaIsNotMisreportedAsMissingGmailSetup() {
        AtomicInteger calls = new AtomicInteger();
        handler.set(exchange -> {
            calls.incrementAndGet();
            reply(exchange, 403, "{\"error\":{\"errors\":[{\"reason\":\"dailyLimitExceeded\"}],"
                    + "\"message\":\"private-account-information\"}}");
        });
        AppException error = assertThrows(AppException.class, gateway::connect);
        assertTrue(error.getMessage().contains("quota"));
        assertFalse(error.getMessage().contains("private-account"));
        assertEquals(1, calls.get());
    }

    @Test void rateLimitedSendIsNotAutomaticallyReplayed() {
        AtomicInteger calls = new AtomicInteger();
        handler.set(exchange -> {
            calls.incrementAndGet();
            reply(exchange, 403, "{\"error\":{\"errors\":[{\"reason\":\"userRateLimitExceeded\"}]}}");
        });
        assertThrows(AppException.class, () -> gateway.sendMessage("alice@gmail.com", "bob@example.org", "Hi", "Text"));
        assertEquals(1, calls.get());
    }

    @Test void persistentRateLimitStopsAfterBoundedBackoffAndKeepsAuthorization() {
        java.util.ArrayList<Long> delays = new java.util.ArrayList<>();
        gateway = new GmailApiClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                HttpClient.newHttpClient(), tokens, delays::add);
        handler.set(exchange -> reply(exchange, 403,
                "{\"error\":{\"errors\":[{\"reason\":\"rateLimitExceeded\"}]}}"));
        AppException error = assertThrows(AppException.class, gateway::connect);
        assertEquals(List.of(1000L, 2000L, 4000L, 8000L, 16000L, 32000L), delays);
        assertTrue(error.getMessage().contains("temporarily limited"));
        assertFalse(error.getMessage().contains("Enable the Gmail API"));
        assertFalse(tokens.disconnected);
        assertEquals(0, tokens.refreshes);
    }

    private static String rawMessage(String content) {
        JsonObject json = new JsonObject();
        json.addProperty("raw", encode(content));
        json.addProperty("internalDate", "1700000000000");
        json.add("labelIds", JsonParser.parseString("[\"INBOX\",\"UNREAD\"]"));
        return json.toString();
    }

    private static String encode(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static void reply(HttpExchange exchange, int status, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    @FunctionalInterface private interface Handler { void handle(HttpExchange exchange) throws Exception; }

    private static final class FakeTokens implements AccessTokenProvider {
        boolean authorized;
        boolean disconnected;
        int refreshes;
        @Override public void authorize() { authorized = true; }
        @Override public String accessToken() { return refreshes == 0 ? "initial" : "refreshed"; }
        @Override public String refreshAccessToken() { refreshes++; return "refreshed"; }
        @Override public void disconnect() { disconnected = true; }
    }
}
