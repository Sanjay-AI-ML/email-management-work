
package com.emailapp.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

public class BlockedSenderRepository {

    public boolean isBlocked(long accountId, String senderEmail)
            throws SQLException {

        String sql = """
                SELECT 1
                FROM blocked_senders
                WHERE account_id = ?
                  AND LOWER(sender_email) = ?
                LIMIT 1
                """;

        try (Connection connection =
                     DatabaseConnection.getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setLong(1, accountId);
            statement.setString(
                    2,
                    senderEmail.trim().toLowerCase(Locale.ROOT)
            );

            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }
}
