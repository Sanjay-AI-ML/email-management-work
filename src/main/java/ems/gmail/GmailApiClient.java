package ems.gmail;

import ems.exception.AppException;
import ems.model.EmailMessage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Standard HTTP REST client for Google's Gmail API v1.
 * Uses Java 17 standard HttpClient with no third-party libraries.
 */
public class GmailApiClient {

    private static final String GMAIL_BASE_URI = "https://gmail.googleapis.com/gmail/v1/users/me/";

    private final HttpClient httpClient;

    public GmailApiClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Retrieves the connected account's email address from Google.
     */
    public String getUserEmailAddress(String accessToken) throws AppException {
        String url = GMAIL_BASE_URI + "profile";
        Map<String, Object> response = getJson(url, accessToken);
        String email = SimpleJson.getString(response, "emailAddress");
        if (email == null || email.isBlank()) {
            throw new AppException("Failed to retrieve user email address from Gmail profile.");
        }
        return email;
    }

    /**
     * Lists recent message IDs in the mailbox.
     */
    public List<String> listMessageIds(String accessToken, int maxResults) throws AppException {
        String url = GMAIL_BASE_URI + "messages?maxResults=" + Math.max(1, Math.min(maxResults, 100));
        Map<String, Object> response = getJson(url, accessToken);
        List<Object> rawList = SimpleJson.getList(response, "messages");
        List<String> messageIds = new ArrayList<>();
        for (Object item : rawList) {
            if (item instanceof Map<?, ?> map) {
                Object id = map.get("id");
                if (id != null) {
                    messageIds.add(id.toString());
                }
            }
        }
        return messageIds;
    }

    /**
     * Retrieves full email message content and metadata by Gmail message ID.
     */
    public EmailMessage fetchMessage(String accessToken, long accountId, String gmailMessageId) throws AppException {
        String url = GMAIL_BASE_URI + "messages/" + gmailMessageId + "?format=full";
        Map<String, Object> data = getJson(url, accessToken);

        // Parse labels
        Set<String> labels = new HashSet<>();
        List<Object> rawLabels = SimpleJson.getList(data, "labelIds");
        for (Object l : rawLabels) {
            if (l != null) labels.add(l.toString());
        }

        // Parse internal timestamp
        Instant receivedAt = Instant.now();
        Object internalDateObj = data.get("internalDate");
        if (internalDateObj instanceof Number n) {
            receivedAt = Instant.ofEpochMilli(n.longValue());
        } else if (internalDateObj instanceof String s) {
            try {
                receivedAt = Instant.ofEpochMilli(Long.parseLong(s));
            } catch (NumberFormatException ignored) {
            }
        }

        // Parse payload headers & body
        Map<String, Object> payload = SimpleJson.getObject(data, "payload");
        List<Object> headersList = SimpleJson.getList(payload, "headers");

        String sender = "";
        String subject = "";
        List<String> recipients = new ArrayList<>();

        for (Object h : headersList) {
            if (h instanceof Map<?, ?> headerMap) {
                String name = SimpleJson.getStringOrDefault((Map<String, Object>) headerMap, "name", "");
                String value = SimpleJson.getStringOrDefault((Map<String, Object>) headerMap, "value", "");
                if ("From".equalsIgnoreCase(name)) {
                    sender = extractEmailAddress(value);
                } else if ("Subject".equalsIgnoreCase(name)) {
                    subject = value;
                } else if ("To".equalsIgnoreCase(name) || "Cc".equalsIgnoreCase(name)) {
                    for (String part : value.split(",")) {
                        String clean = extractEmailAddress(part);
                        if (!clean.isBlank()) recipients.add(clean);
                    }
                }
            }
        }

        if (sender.isBlank()) {
            sender = "unknown@sender.com";
        }
        if (recipients.isEmpty()) {
            recipients.add("me");
        }

        String body = extractBodyText(payload);
        if (body.isBlank()) {
            String snippet = SimpleJson.getString(data, "snippet");
            body = (snippet != null) ? snippet : "";
        }

        return new EmailMessage(
                accountId,
                gmailMessageId,
                sender,
                recipients,
                subject,
                body,
                receivedAt,
                labels
        );
    }

    /**
     * Sends an email via Gmail API and returns the created message ID.
     */
    public String sendMessage(String accessToken, String from, String recipientEmail, String subject, String bodyText)
            throws AppException {
        String rawRfc2822 = "From: " + from + "\r\n" +
                "To: " + recipientEmail + "\r\n" +
                "Subject: " + subject + "\r\n" +
                "Date: " + DateTimeFormatter.RFC_1123_DATE_TIME.format(java.time.ZonedDateTime.now()) + "\r\n" +
                "Content-Type: text/plain; charset=UTF-8\r\n\r\n" +
                bodyText;

        String encodedRaw = Base64.getUrlEncoder().withoutPadding().encodeToString(rawRfc2822.getBytes(StandardCharsets.UTF_8));
        String jsonPayload = "{\"raw\":\"" + SimpleJson.escapeString(encodedRaw) + "\"}";

        String url = GMAIL_BASE_URI + "messages/send";
        Map<String, Object> result = postJson(url, jsonPayload, accessToken);
        String newId = SimpleJson.getString(result, "id");
        if (newId == null || newId.isBlank()) {
            throw new AppException("Gmail did not return a valid message ID after sending.");
        }
        return newId;
    }

    /**
     * Removes the UNREAD label in Gmail.
     */
    public void markAsRead(String accessToken, String gmailMessageId) throws AppException {
        String url = GMAIL_BASE_URI + "messages/" + gmailMessageId + "/modify";
        String body = "{\"removeLabelIds\":[\"UNREAD\"]}";
        postJson(url, body, accessToken);
    }

    /**
     * Adds the SPAM label and removes INBOX in Gmail.
     */
    public void markAsSpam(String accessToken, String gmailMessageId) throws AppException {
        String url = GMAIL_BASE_URI + "messages/" + gmailMessageId + "/modify";
        String body = "{\"addLabelIds\":[\"SPAM\"],\"removeLabelIds\":[\"INBOX\"]}";
        postJson(url, body, accessToken);
    }

    /**
     * Removes the SPAM label and restores INBOX in Gmail.
     */
    public void markAsNotSpam(String accessToken, String gmailMessageId) throws AppException {
        String url = GMAIL_BASE_URI + "messages/" + gmailMessageId + "/modify";
        String body = "{\"removeLabelIds\":[\"SPAM\"],\"addLabelIds\":[\"INBOX\"]}";
        postJson(url, body, accessToken);
    }

    private String extractBodyText(Map<String, Object> payload) {
        if (payload == null) return "";

        // Direct body data
        Map<String, Object> bodyMap = SimpleJson.getObject(payload, "body");
        String data = SimpleJson.getString(bodyMap, "data");
        if (data != null && !data.isBlank()) {
            return decodeBase64(data);
        }

        // Search recursively inside parts
        List<Object> parts = SimpleJson.getList(payload, "parts");
        for (Object p : parts) {
            if (p instanceof Map<?, ?> partMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> part = (Map<String, Object>) partMap;
                String mimeType = SimpleJson.getString(part, "mimeType");
                if ("text/plain".equalsIgnoreCase(mimeType)) {
                    Map<String, Object> pBody = SimpleJson.getObject(part, "body");
                    String pData = SimpleJson.getString(pBody, "data");
                    if (pData != null && !pData.isBlank()) {
                        return decodeBase64(pData);
                    }
                }
            }
        }

        // Fallback to any text part
        for (Object p : parts) {
            if (p instanceof Map<?, ?> partMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> part = (Map<String, Object>) partMap;
                String nested = extractBodyText(part);
                if (!nested.isBlank()) return nested;
            }
        }

        return "";
    }

    private String decodeBase64(String base64Url) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(base64Url.trim());
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            try {
                byte[] bytes = Base64.getDecoder().decode(base64Url.trim());
                return new String(bytes, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                return "";
            }
        }
    }

    private String extractEmailAddress(String header) {
        if (header == null) return "";
        int start = header.indexOf('<');
        int end = header.indexOf('>');
        if (start != -1 && end != -1 && end > start) {
            return header.substring(start + 1, end).trim();
        }
        return header.trim();
    }

    private Map<String, Object> getJson(String url, String accessToken) throws AppException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .GET()
                .build();

        return execute(request);
    }

    private Map<String, Object> postJson(String url, String jsonBody, String accessToken) throws AppException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        return execute(request);
    }

    private Map<String, Object> execute(HttpRequest request) throws AppException {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code >= 200 && code < 300) {
                return SimpleJson.parseObject(response.body());
            } else if (code == 401) {
                throw new AppException("Gmail authorization has expired. Please reconnect.");
            } else {
                throw new AppException("Gmail API error (HTTP " + code + "): " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException("Failed to communicate with Gmail service: " + e.getMessage(), e);
        }
    }
}
