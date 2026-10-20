
package com.emailapp.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

public class SpamFeedbackRepository {

    public void saveFeedback(
            long accountId,
            String gmailMessageId,
            boolean userMarkedSpam) throws SQLException {

        String sql = """
                INSERT INTO spam_feedback
                (account_id, gmail_message_id,
                 user_marked_spam, corrected_at)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    user_marked_spam = VALUES(user_marked_spam),
                    corrected_at = VALUES(corrected_at)
                """;

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setLong(1, accountId);
            statement.setString(2, gmailMessageId);
            statement.setBoolean(3, userMarkedSpam);
            statement.setTimestamp(4, Timestamp.from(Instant.now()));

            statement.executeUpdate();
        }
    }
}
