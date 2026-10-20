
package com.emailapp.database;

import com.emailapp.model.EmailMessage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class MessageRepository {

    // Save an email message
    public void save(EmailMessage message) throws SQLException {

        String sql = """
                INSERT INTO messages
                (account_id, gmail_message_id, sender_email,
                 subject, body_text, received_at, is_read)
                VALUES (?, ?, ?, ?, ?, ?, FALSE)
                ON DUPLICATE KEY UPDATE
                    sender_email = VALUES(sender_email),
                    subject = VALUES(subject),
                    body_text = VALUES(body_text),
                    received_at = VALUES(received_at)
                """;

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setLong(1, message.getAccountId());
            statement.setString(2, message.getGmailMessageId());
            statement.setString(3, message.getSenderEmail());
            statement.setString(4, message.getSubject());
            statement.setString(5, message.getBodyText());
            statement.setTimestamp(
                    6, Timestamp.from(message.getReceivedAt()));

            statement.executeUpdate();
        }
    }

    // Save email recipients
    public void saveRecipients(EmailMessage message)
            throws SQLException {

        String sql = """
                INSERT INTO message_recipients
                (account_id, gmail_message_id, recipient_email)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    recipient_email = VALUES(recipient_email)
                """;

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            for (String recipient : message.getRecipientEmails()) {
                statement.setLong(1, message.getAccountId());
                statement.setString(2, message.getGmailMessageId());
                statement.setString(3, recipient);
                statement.executeUpdate();
            }
        }
    }

    // Save email labels
    public void saveLabels(EmailMessage message) throws SQLException {

        String sql = """
                INSERT INTO message_labels
                (account_id, gmail_message_id, gmail_label_id)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    gmail_label_id = VALUES(gmail_label_id)
                """;

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            for (String labelId : message.getGmailLabelIds()) {
                statement.setLong(1, message.getAccountId());
                statement.setString(2, message.getGmailMessageId());
                statement.setString(3, labelId);
                statement.executeUpdate();
            }
        }
    }

    // Retrieve all messages belonging to an account

public List<EmailMessage> findByAccount(long accountId)
        throws SQLException {

    List<EmailMessage> messages = new ArrayList<>();

    String sql = """
            SELECT account_id, gmail_message_id, sender_email,
                   subject, body_text, received_at
            FROM messages
            WHERE account_id = ?
            ORDER BY received_at DESC
            """;

    try (Connection connection = DatabaseConnection.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {

        statement.setLong(1, accountId);

        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {

                String messageId =
                        resultSet.getString("gmail_message_id");

                List<String> recipients =
                        findRecipients(accountId, messageId);

                java.util.Set<String> labels =
                        findLabels(accountId, messageId);

                Timestamp timestamp =
                        resultSet.getTimestamp("received_at");

                EmailMessage message = new EmailMessage(
                        resultSet.getLong("account_id"),
                        messageId,
                        resultSet.getString("sender_email"),
                        recipients,
                        resultSet.getString("subject"),
                        resultSet.getString("body_text"),
                        timestamp != null
                                ? timestamp.toInstant()
                                : java.time.Instant.now(),
                        labels
                );

                messages.add(message);
            }
        }
    }

    return messages;
}

    
public List<String> findRecipients(
        long accountId,
        String gmailMessageId) throws SQLException {

    List<String> recipients = new ArrayList<>();

    String sql = """
            SELECT recipient_email
            FROM message_recipients
            WHERE account_id = ?
              AND gmail_message_id = ?
            """;

    try (Connection connection = DatabaseConnection.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {

        statement.setLong(1, accountId);
        statement.setString(2, gmailMessageId);

        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                recipients.add(resultSet.getString("recipient_email"));
            }
        }
    }

    return recipients;
}

public java.util.Set<String> findLabels(
        long accountId,
        String gmailMessageId) throws SQLException {

    java.util.Set<String> labels = new java.util.HashSet<>();

    String sql = """
            SELECT gmail_label_id
            FROM message_labels
            WHERE account_id = ?
              AND gmail_message_id = ?
            """;

    try (Connection connection = DatabaseConnection.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {

        statement.setLong(1, accountId);
        statement.setString(2, gmailMessageId);

        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                labels.add(resultSet.getString("gmail_label_id"));
            }
        }
    }

    return labels;
}


public void updateReadStatus(
        long accountId,
        String gmailMessageId,
        boolean isRead) throws SQLException {

    String sql = """
            UPDATE messages
            SET is_read = ?
            WHERE account_id = ?
              AND gmail_message_id = ?
            """;

    try (Connection connection = DatabaseConnection.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {

        statement.setBoolean(1, isRead);
        statement.setLong(2, accountId);
        statement.setString(3, gmailMessageId);

        int rowsUpdated = statement.executeUpdate();

        if (rowsUpdated > 0) {
            System.out.println("Email read status updated successfully!");
        } else {
            System.out.println("Email not found.");
        }
    }
}

}
