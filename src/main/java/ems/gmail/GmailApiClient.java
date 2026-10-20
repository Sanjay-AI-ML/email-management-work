package ems.gmail;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ems.exception.AppException;
import ems.model.EmailMessage;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Gmail REST client using an OAuth desktop session and standards-compliant MIME messages. */
public final class GmailApiClient implements GmailGateway {
    private static final URI GOOGLE_API = URI.create("https://gmail.googleapis.com/gmail/v1/users/me/");
    private static final Gson JSON = new Gson();
    private final URI baseUri;
    private final HttpClient http;
    private final AccessTokenProvider tokens;
    private final RetryDelay retryDelay;

    public GmailApiClient(Path credentialsFile, Path tokenDirectory) {
        this(GOOGLE_API, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build(),
                new GmailAuth(credentialsFile, tokenDirectory));
    }

    /** The injected endpoint and token provider support deterministic local HTTP tests. */
    public GmailApiClient(URI baseUri, HttpClient http, AccessTokenProvider tokens) {
        this(baseUri, http, tokens, Thread::sleep);
    }

    GmailApiClient(URI baseUri, HttpClient http, AccessTokenProvider tokens, RetryDelay retryDelay) {
        if (!baseUri.toString().endsWith("/")) {
            throw new IllegalArgumentException("Gmail API base URI must end with '/'.");
        }
        this.baseUri = baseUri;
        this.http = http;
        this.tokens = tokens;
        this.retryDelay = retryDelay;
    }

    @Override public String connect() throws AppException {
        tokens.authorize();
        JsonObject profile = request("GET", "profile", null);
        return requiredString(profile, "emailAddress");
    }

    @Override public List<EmailMessage> fetchMessages(long accountId) throws AppException {
        Set<String> ids = new LinkedHashSet<>();
        Set<String> pageTokens = new HashSet<>();
        String pageToken = "";
        do {
            String path = "messages?maxResults=500&includeSpamTrash=true&q="
                    + encode("-in:trash -in:drafts");
            if (!pageToken.isEmpty()) path += "&pageToken=" + encode(pageToken);
            JsonObject page = request("GET", path, null);
            try {
                if (page.has("messages")) {
                    for (var value : page.getAsJsonArray("messages")) {
                        ids.add(requiredString(value.getAsJsonObject(), "id"));
                    }
                }
                pageToken = page.has("nextPageToken") ? requiredString(page, "nextPageToken") : "";
            } catch (RuntimeException malformedPage) {
                throw new AppException("Gmail returned an unreadable mailbox page. Please refresh again.", malformedPage);
            }
            if (!pageToken.isEmpty() && !pageTokens.add(pageToken)) {
                throw new AppException("Gmail repeated a mailbox page. Please refresh again.");
            }
        } while (!pageToken.isEmpty());
        return readMessages(accountId, ids);
    }

    /** Bounded startup window; never presented as a complete mailbox snapshot. */
    @Override public List<EmailMessage> fetchRecentMessages(long accountId, int perMailbox) throws AppException {
        int limit = Math.max(1, Math.min(perMailbox, 100));
        Set<String> ids = new LinkedHashSet<>();
        for (String label : List.of("INBOX", "SENT", "SPAM")) {
            JsonObject page = request("GET", "messages?maxResults=" + limit
                    + "&includeSpamTrash=true&labelIds=" + label + "&q=" + encode("-in:trash -in:drafts"), null);
            try {
                if (page.has("messages")) {
                    int count = 0;
                    for (var item : page.getAsJsonArray("messages")) {
                        if (count++ >= limit) break;
                        ids.add(requiredString(item.getAsJsonObject(), "id"));
                    }
                }
            } catch (RuntimeException malformedPage) {
                throw new AppException("Gmail returned an unreadable recent-mail page. Click Refresh to try again.", malformedPage);
            }
        }
        return readMessages(accountId, ids);
    }

    private List<EmailMessage> readMessages(long accountId, Set<String> ids) throws AppException {
        List<EmailMessage> result = new ArrayList<>();
        for (String id : ids) {
            try {
                result.add(getMessage(accountId, id));
            } catch (MessageUnavailableException deletedDuringSync) {
                // A message can disappear between the list and get calls.
            }
        }
        return result;
    }

    @Override public EmailMessage getMessage(long accountId, String id) throws AppException {
        JsonObject message = request("GET", "messages/" + messageId(id) + "?format=raw", null);
        Set<String> labels = new HashSet<>();
        try {
            if (message.has("labelIds")) {
                for (var label : message.getAsJsonArray("labelIds")) labels.add(label.getAsString());
            }
        } catch (RuntimeException malformedLabels) {
            throw new AppException("Gmail returned unreadable message labels. Please refresh again.", malformedLabels);
        }
        String raw = requiredString(message, "raw");
        Instant received = null;
        if (message.has("internalDate")) {
            try { received = Instant.ofEpochMilli(message.get("internalDate").getAsLong()); }
            catch (RuntimeException invalidDate) {
                throw new AppException("Gmail returned an invalid message date.", invalidDate);
            }
        }
        return MimeMessages.parse(accountId, id, raw, received, labels);
    }

    @Override public String sendMessage(String senderEmail, String recipientEmail, String subject,
            String body) throws AppException {
        JsonObject message = new JsonObject();
        message.addProperty("raw", MimeMessages.compose(senderEmail, recipientEmail, subject, body));
        JsonObject response = request("POST", "messages/send", message);
        try {
            return requiredString(response, "id");
        } catch (AppException incompleteSendResponse) {
            throw new AppException("Gmail accepted the send request but returned no message ID. "
                    + "Check Sent before sending again.");
        }
    }

    @Override public void setRead(String id) throws AppException {
        modify(id, Set.of(), Set.of("UNREAD"));
    }

    @Override public void setSpam(String id, boolean spam) throws AppException {
        modify(id, spam ? Set.of("SPAM") : Set.of("INBOX"),
                spam ? Set.of("INBOX") : Set.of("SPAM"));
    }

    private void modify(String id, Set<String> add, Set<String> remove) throws AppException {
        JsonObject body = new JsonObject();
        body.add("addLabelIds", JSON.toJsonTree(add));
        body.add("removeLabelIds", JSON.toJsonTree(remove));
        request("POST", "messages/" + messageId(id) + "/modify", body);
    }

    @Override public void disconnect() { tokens.disconnect(); }

    private JsonObject request(String method, String path, JsonObject body) throws AppException {
        boolean sending = "POST".equals(method) && path.equals("messages/send");
        boolean refreshed = false;
        int retries = 0;
        while (true) {
            String token = tokens.accessToken();
            HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json");
            if (body == null) builder.GET();
            else builder.header("Content-Type", "application/json; charset=UTF-8")
                    .method(method, HttpRequest.BodyPublishers.ofString(JSON.toJson(body)));
            try {
                HttpResponse<String> response = http.send(builder.build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                if (status == 401 && !refreshed) {
                    tokens.refreshAccessToken();
                    refreshed = true;
                    continue;
                }
                if (status < 200 || status >= 300) {
                    Set<String> reasons = errorReasons(response.body());
                    if (method.equals("GET") && retries < 6 && transientFailure(status, reasons)) {
                        retryDelay.pause(1000L << retries++);
                        continue;
                    }
                    if (sending && status >= 500) {
                        throw new AppException("Could not confirm delivery. Check Gmail Sent before retrying.");
                    }
                    throw apiFailure(status, reasons);
                }
                if (response.body().isBlank()) return new JsonObject();
                return JsonParser.parseString(response.body()).getAsJsonObject();
            } catch (IOException e) {
                throw new AppException(sending
                        ? "Could not confirm delivery. Check Gmail Sent before retrying."
                        : "Could not reach Gmail. Check your internet connection and try again.", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AppException(sending
                        ? "Could not confirm delivery. Check Gmail Sent before retrying."
                        : "The Gmail operation was interrupted.", e);
            } catch (RuntimeException e) {
                throw new AppException(sending
                        ? "Could not confirm delivery. Check Gmail Sent before retrying."
                        : "Gmail returned an unreadable response. Please try again.", e);
            }
        }
    }

    private static boolean transientFailure(int status, Set<String> reasons) {
        return status == 429 || status == 500 || status == 502 || status == 503 || status == 504
                || status == 403 && (reasons.contains("rateLimitExceeded")
                || reasons.contains("userRateLimitExceeded"));
    }

    /** Read only machine error codes; never echo Google's response or account details. */
    private static Set<String> errorReasons(String body) {
        Set<String> result = new HashSet<>();
        try {
            JsonObject error = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("error");
            for (String field : List.of("errors", "details")) {
                if (error != null && error.has(field) && error.get(field).isJsonArray()) {
                    for (var item : error.getAsJsonArray(field)) {
                        if (item.isJsonObject() && item.getAsJsonObject().has("reason")) {
                            result.add(item.getAsJsonObject().get("reason").getAsString());
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) { /* Fall back to a safe HTTP-status explanation. */ }
        return result;
    }

    private static AppException apiFailure(int status, Set<String> reasons) {
        if (status == 403) {
            if (reasons.contains("dailyLimitExceeded") || reasons.contains("quotaExceeded")) {
                return new AppException("The Google Cloud project's Gmail API quota is exhausted. "
                        + "Check Gmail API quotas in Google Cloud or wait for the quota to reset.");
            }
            if (transientFailure(status, reasons)) {
                return new AppException("Gmail temporarily limited requests. Your sign-in is still valid. "
                        + "Wait a moment, then click Refresh.");
            }
            if (reasons.contains("domainPolicy")) {
                return new AppException("Your Google Workspace administrator blocks Gmail access for this app. "
                        + "Ask your administrator to allow it.");
            }
            if (reasons.contains("insufficientPermissions") || reasons.contains("ACCESS_TOKEN_SCOPE_INSUFFICIENT")) {
                return new AppException("The Google sign-in did not grant the required Gmail permission. "
                        + "Disconnect, connect again, and allow Maildesk to manage Gmail.");
            }
        }
        return switch (status) {
            case 401 -> new AppException("Gmail authorization expired. Disconnect and connect again.");
            case 403 -> new AppException("Gmail denied access. Enable the Gmail API in your Google Cloud "
                    + "project, add your account as a test user, and grant Gmail permissions when connecting.");
            case 404 -> new MessageUnavailableException();
            case 429 -> new AppException("Gmail's request limit was reached. Wait a moment and refresh again.");
            default -> new AppException("Gmail could not complete the request (HTTP " + status + "). Please try again.");
        };
    }

    @FunctionalInterface interface RetryDelay { void pause(long milliseconds) throws InterruptedException; }

    private static String messageId(String id) throws AppException {
        if (id == null || !id.matches("[A-Za-z0-9_-]+")) throw new AppException("Invalid Gmail message ID.");
        return id;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String requiredString(JsonObject object, String key) throws AppException {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.get(key).getAsJsonPrimitive().isString()
                || object.get(key).getAsString().isBlank()) {
            throw new AppException("Gmail returned an incomplete response. Please refresh again.");
        }
        return object.get(key).getAsString();
    }

    private static final class MessageUnavailableException extends AppException {
        private static final long serialVersionUID = 1L;
        private MessageUnavailableException() { super("This message is no longer available in Gmail. Refresh the mailbox."); }
    }
}
