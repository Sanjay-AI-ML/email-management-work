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

    public GmailApiClient(Path credentialsFile, Path tokenDirectory) {
        this(GOOGLE_API, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build(),
                new GmailAuth(credentialsFile, tokenDirectory));
    }

    /** The injected endpoint and token provider support deterministic local HTTP tests. */
    public GmailApiClient(URI baseUri, HttpClient http, AccessTokenProvider tokens) {
        if (!baseUri.toString().endsWith("/")) {
            throw new IllegalArgumentException("Gmail API base URI must end with '/'.");
        }
        this.baseUri = baseUri;
        this.http = http;
        this.tokens = tokens;
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
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = attempt == 0 ? tokens.accessToken() : tokens.refreshAccessToken();
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
                if (status == 401 && attempt == 0) continue;
                if (status < 200 || status >= 300) {
                    if (sending && status >= 500) {
                        throw new AppException("Could not confirm delivery. Check Gmail Sent before retrying.");
                    }
                    throw apiFailure(status);
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
        throw new AppException("Gmail authorization expired. Disconnect and connect again.");
    }

    private static AppException apiFailure(int status) {
        return switch (status) {
            case 401 -> new AppException("Gmail authorization expired. Disconnect and connect again.");
            case 403 -> new AppException("Gmail denied access. Enable the Gmail API in your Google Cloud "
                    + "project, add your account as a test user, and grant Gmail permissions when connecting.");
            case 404 -> new MessageUnavailableException();
            case 429 -> new AppException("Gmail's request limit was reached. Wait a moment and refresh again.");
            default -> new AppException("Gmail could not complete the request (HTTP " + status + "). Please try again.");
        };
    }

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
