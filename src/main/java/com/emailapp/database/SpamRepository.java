
package com.emailapp.database;

import com.emailapp.model.EmailMessage;
import com.emailapp.model.SpamResult;

import java.sql.ResultSet;
import java.util.ArrayList;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.sql.Timestamp;
import java.time.Instant;

public class SpamRepository {

    public void save(
            EmailMessage message,
            SpamResult result) throws SQLException {

        String sql = """
                INSERT INTO spam_results
                (account_id, gmail_message_id, score,
                 suspected_spam, reasons, analyzed_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    score = VALUES(score),
                    suspected_spam = VALUES(suspected_spam),
                    reasons = VALUES(reasons),
                    analyzed_at = VALUES(analyzed_at)
                """;

        String reasonsJson = toJsonArray(result.getReasons());

        try (Connection connection =
                     DatabaseConnection.getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setLong(1, message.getAccountId());
            statement.setString(2, getMessageId(message));
            statement.setInt(3, result.getScore());
            statement.setBoolean(4, result.isSuspectedSpam());
            statement.setString(5, reasonsJson);
            statement.setTimestamp(
                    6, Timestamp.from(Instant.now()));

            statement.executeUpdate();
        }
    }

    private String getMessageId(EmailMessage message) {
        return message.getGmailMessageId();
    }

    private String toJsonArray(
            java.util.List<String> reasons) {

        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < reasons.size(); i++) {
            if (i > 0) {
                json.append(",");
            }

            String escaped = reasons.get(i)
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");

            json.append("\"").append(escaped).append("\"");
        }

        json.append("]");
        return json.toString();
    }
    
public SpamResult findByMessage(
        long accountId,
        String gmailMessageId) throws SQLException {

    String sql = """
            SELECT score, suspected_spam, reasons
            FROM spam_results
            WHERE account_id = ?
              AND gmail_message_id = ?
            """;

    try (Connection connection = DatabaseConnection.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {

        statement.setLong(1, accountId);
        statement.setString(2, gmailMessageId);

        try (ResultSet resultSet = statement.executeQuery()) {

            if (!resultSet.next()) {
                return null;
            }

            int score = resultSet.getInt("score");
            boolean suspectedSpam =
                    resultSet.getBoolean("suspected_spam");
            String reasonsJson = resultSet.getString("reasons");

            java.util.List<String> reasons =
                    parseReasons(reasonsJson);

            return new SpamResult(score, suspectedSpam, reasons);
        }
    }
}

private java.util.List<String> parseReasons(String json) {
    java.util.List<String> reasons = new ArrayList<>();

    if (json == null || json.length() < 2) {
        return reasons;
    }

    // The saved reasons are a JSON array of strings.
    // Decode common escaped characters.
    String content = json.substring(1, json.length() - 1);
    StringBuilder item = new StringBuilder();
    boolean insideString = false;
    boolean escaped = false;

    for (int i = 0; i < content.length(); i++) {
        char c = content.charAt(i);

        if (escaped) {
            switch (c) {
                case 'n' -> item.append('\n');
                case 'r' -> item.append('\r');
                case 't' -> item.append('\t');
                default -> item.append(c);
            }
            escaped = false;
        } else if (c == '\\' && insideString) {
            escaped = true;
        } else if (c == '"') {
            if (insideString) {
                reasons.add(item.toString());
                item.setLength(0);
            }
            insideString = !insideString;
        } else if (c == ',' && !insideString) {
            // Move to the next JSON string.
        } else if (insideString) {
            item.append(c);
        }
    }

    return reasons;
}

}
