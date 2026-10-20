package ems.storage;

import com.emailapp.database.DatabaseConnection;
import com.emailapp.spam.SpamClassifier;
import ems.model.Account;
import ems.model.EmailMessage;
import ems.model.Mailbox;
import ems.model.SpamResult;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;

/** JDBC cache with atomic snapshot replacement and persistent account-scoped feedback. */
public final class JdbcMailStore implements MailStore {
    private final ConnectionFactory connections;
    private final SpamClassifier classifier = new SpamClassifier();

    public JdbcMailStore() {
        this(DatabaseConnection::getConnection);
    }

    public JdbcMailStore(ConnectionFactory connections) {
        this.connections = java.util.Objects.requireNonNull(connections);
    }

    @Override
    public void initialize() throws SQLException {
        try (Connection connection = connections.open(); Statement statement = connection.createStatement()) {
            for (String sql : Schema.TABLES) statement.execute(sql);
        }
    }

    @Override
    public Account findOrCreateAccount(String email) throws SQLException {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) throw new SQLException("The Gmail account has no email address.");
        try (Connection connection = connections.open()) {
            Optional<Account> existing = findAccount(connection, normalized);
            if (existing.isPresent()) return existing.get();
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO accounts (email_address) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
                insert.setString(1, normalized);
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    if (keys.next()) return new Account(keys.getLong(1), normalized);
                }
            } catch (SQLException exception) {
                // Another connection may have created this account since the SELECT.
                Optional<Account> concurrent = findAccount(connection, normalized);
                if (concurrent.isPresent()) return concurrent.get();
                throw exception;
            }
            return findAccount(connection, normalized).orElseThrow(() -> new SQLException("Account ID was not returned."));
        }
    }

    private Optional<Account> findAccount(Connection connection, String email) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT account_id, email_address FROM accounts WHERE email_address = ?")) {
            query.setString(1, email);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? Optional.of(new Account(rows.getLong(1), rows.getString(2))) : Optional.empty();
            }
        }
    }

    @Override
    public void saveMessages(long accountId, List<EmailMessage> messages) throws SQLException {
        checkAccount(accountId, messages);
        transaction(connection -> saveBatch(connection, accountId, messages));
    }

    @Override
    public void replaceSnapshot(long accountId, List<EmailMessage> messages) throws SQLException {
        checkAccount(accountId, messages);
        transaction(connection -> {
            saveBatch(connection, accountId, messages);
            Set<String> present = new LinkedHashSet<>();
            for (EmailMessage message : messages) present.add(message.getGmailMessageId());
            List<String> removed = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("SELECT gmail_message_id FROM messages WHERE account_id=?")) {
                query.setLong(1, accountId);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) if (!present.contains(rows.getString(1))) removed.add(rows.getString(1));
                }
            }
            deleteBatch(connection, accountId, removed);
        });
    }

    private static void checkAccount(long accountId, List<EmailMessage> messages) throws SQLException {
        for (EmailMessage message : messages) {
            if (message.getAccountId() != accountId) throw new SQLException("Message belongs to another account.");
        }
    }

    private void saveBatch(Connection connection, long accountId, List<EmailMessage> messages) throws SQLException {
        Set<String> blocked = blockedSenders(connection, accountId);
        for (EmailMessage message : messages) {
            upsertMessage(connection, message);
            replaceChildren(connection, message, "message_recipients", "recipient_email",
                    distinctRecipients(message.getRecipientEmails()));
            replaceChildren(connection, message, "message_labels", "gmail_label_id", message.getGmailLabelIds());
            SpamResult automatic = classifier.analyze(message,
                    blocked.contains(message.getSenderEmail().trim().toLowerCase(Locale.ROOT)));
            saveSpamResult(connection, accountId, message.getGmailMessageId(), automatic);
        }
    }

    private static Collection<String> distinctRecipients(List<String> recipients) {
        // Gmail may repeat a recipient in To/Cc with different casing. MySQL's default
        // collation also treats those addresses as equal; retain the first spelling.
        Map<String, String> distinct = new LinkedHashMap<>();
        for (String recipient : recipients) {
            distinct.putIfAbsent(recipient.toLowerCase(Locale.ROOT), recipient);
        }
        return distinct.values();
    }

    private void upsertMessage(Connection connection, EmailMessage message) throws SQLException {
        String fields = "sender_email=?, subject=?, body_text=?, received_at=?, is_read=?";
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE messages SET " + fields + " WHERE account_id=? AND gmail_message_id=?")) {
            bindMessage(update, message);
            if (update.executeUpdate() > 0) return;
        }
        // MySQL may return zero when an UPDATE has identical values (useAffectedRows=true).
        if (messageExists(connection, message.getAccountId(), message.getGmailMessageId())) return;
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO messages "
                + "(sender_email,subject,body_text,received_at,is_read,account_id,gmail_message_id) VALUES (?,?,?,?,?,?,?)")) {
            bindMessage(insert, message);
            insert.executeUpdate();
        }
    }

    private static void bindMessage(PreparedStatement statement, EmailMessage message) throws SQLException {
        statement.setString(1, message.getSenderEmail());
        statement.setString(2, message.getSubject());
        statement.setString(3, message.getBodyText());
        statement.setTimestamp(4, Timestamp.from(message.getReceivedAt()), utc());
        statement.setBoolean(5, !message.getGmailLabelIds().contains("UNREAD"));
        statement.setLong(6, message.getAccountId());
        statement.setString(7, message.getGmailMessageId());
    }

    private void replaceChildren(Connection connection, EmailMessage message, String table, String column,
            Collection<String> values) throws SQLException {
        deleteOne(connection, table, message.getAccountId(), message.getGmailMessageId());
        if (values.isEmpty()) return;
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO " + table
                + " (account_id,gmail_message_id," + column + ") VALUES (?,?,?)")) {
            for (String value : values) {
                insert.setLong(1, message.getAccountId());
                insert.setString(2, message.getGmailMessageId());
                insert.setString(3, value);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    @Override
    public List<EmailMessage> getMessages(long accountId, Mailbox mailbox, String query) throws SQLException {
        String base = "SELECT m.account_id,m.gmail_message_id,m.sender_email,m.subject,m.body_text,m.received_at "
                + "FROM messages m WHERE m.account_id=? AND NOT EXISTS (SELECT 1 FROM message_labels t "
                + "WHERE t.account_id=m.account_id AND t.gmail_message_id=m.gmail_message_id AND t.gmail_label_id='TRASH') ";
        if (mailbox == Mailbox.SUSPECTED) {
            // Review only incoming mail that Gmail has not already placed in Spam.
            base += "AND NOT EXISTS (SELECT 1 FROM message_labels excluded WHERE excluded.account_id=m.account_id "
                    + "AND excluded.gmail_message_id=m.gmail_message_id AND excluded.gmail_label_id IN ('SPAM','SENT')) "
                    + "AND ((EXISTS (SELECT 1 FROM spam_feedback f WHERE f.account_id=m.account_id "
                    + "AND f.gmail_message_id=m.gmail_message_id AND f.user_marked_spam=TRUE)) OR "
                    + "(NOT EXISTS (SELECT 1 FROM spam_feedback f WHERE f.account_id=m.account_id "
                    + "AND f.gmail_message_id=m.gmail_message_id) AND EXISTS (SELECT 1 FROM spam_results s "
                    + "WHERE s.account_id=m.account_id AND s.gmail_message_id=m.gmail_message_id AND s.suspected_spam=TRUE))) ";
        } else {
            base += "AND EXISTS (SELECT 1 FROM message_labels l WHERE l.account_id=m.account_id "
                    + "AND l.gmail_message_id=m.gmail_message_id AND l.gmail_label_id=?) ";
            if (mailbox == Mailbox.INBOX) base += "AND NOT EXISTS (SELECT 1 FROM message_labels s "
                    + "WHERE s.account_id=m.account_id AND s.gmail_message_id=m.gmail_message_id AND s.gmail_label_id='SPAM') ";
        }
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (!normalized.isEmpty()) base += "AND (LOWER(m.sender_email) LIKE ? ESCAPE '!' "
                + "OR LOWER(m.subject) LIKE ? ESCAPE '!' OR LOWER(m.body_text) LIKE ? ESCAPE '!' "
                + "OR EXISTS (SELECT 1 FROM message_recipients r WHERE r.account_id=m.account_id "
                + "AND r.gmail_message_id=m.gmail_message_id AND LOWER(r.recipient_email) LIKE ? ESCAPE '!')) ";
        base += "ORDER BY m.received_at DESC,m.gmail_message_id DESC";
        try (Connection connection = connections.open(); PreparedStatement statement = connection.prepareStatement(base)) {
            int index = 1;
            statement.setLong(index++, accountId);
            if (mailbox != Mailbox.SUSPECTED) statement.setString(index++, mailbox.name());
            if (!normalized.isEmpty()) {
                String pattern = "%" + normalized.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
                for (int i = 0; i < 4; i++) statement.setString(index++, pattern);
            }
            return readMessages(connection, statement);
        }
    }

    @Override
    public Optional<EmailMessage> findMessage(long accountId, String gmailMessageId) throws SQLException {
        try (Connection connection = connections.open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT account_id,gmail_message_id,sender_email,subject,body_text,received_at FROM messages "
                        + "WHERE account_id=? AND gmail_message_id=?")) {
            statement.setLong(1, accountId);
            statement.setString(2, gmailMessageId);
            return readMessages(connection, statement).stream().findFirst();
        }
    }

    /** Legacy repository support, with bounded bulk child reads rather than N+1 connections. */
    public List<EmailMessage> findAll(long accountId) throws SQLException {
        try (Connection connection = connections.open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT account_id,gmail_message_id,sender_email,subject,body_text,received_at FROM messages "
                        + "WHERE account_id=? ORDER BY received_at DESC,gmail_message_id DESC")) {
            statement.setLong(1, accountId);
            return readMessages(connection, statement);
        }
    }

    private List<EmailMessage> readMessages(Connection connection, PreparedStatement query) throws SQLException {
        List<EmailMessage> bases = new ArrayList<>();
        try (ResultSet rows = query.executeQuery()) {
            while (rows.next()) bases.add(new EmailMessage(rows.getLong("account_id"), rows.getString("gmail_message_id"),
                    rows.getString("sender_email"), List.of(), rows.getString("subject"), rows.getString("body_text"),
                    rows.getTimestamp("received_at", utc()).toInstant(), Set.of()));
        }
        if (bases.isEmpty()) return List.of();
        Map<String, List<String>> recipients = readChildren(connection, bases, "message_recipients", "recipient_email");
        Map<String, List<String>> labels = readChildren(connection, bases, "message_labels", "gmail_label_id");
        List<EmailMessage> result = new ArrayList<>(bases.size());
        for (EmailMessage base : bases) result.add(new EmailMessage(base.getAccountId(), base.getGmailMessageId(),
                base.getSenderEmail(), recipients.getOrDefault(base.getGmailMessageId(), List.of()),
                base.getSubject(), base.getBodyText(), base.getReceivedAt(),
                new LinkedHashSet<>(labels.getOrDefault(base.getGmailMessageId(), List.of()))));
        return result;
    }

    private Map<String, List<String>> readChildren(Connection connection, List<EmailMessage> messages,
            String table, String column) throws SQLException {
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (int start = 0; start < messages.size(); start += 500) {
            List<EmailMessage> batch = messages.subList(start, Math.min(start + 500, messages.size()));
            String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
            try (PreparedStatement query = connection.prepareStatement("SELECT gmail_message_id," + column
                    + " FROM " + table + " WHERE account_id=? AND gmail_message_id IN (" + placeholders + ") ORDER BY " + column)) {
                query.setLong(1, messages.get(0).getAccountId());
                for (int i = 0; i < batch.size(); i++) query.setString(i + 2, batch.get(i).getGmailMessageId());
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) values.computeIfAbsent(rows.getString(1), unused -> new ArrayList<>()).add(rows.getString(2));
                }
            }
        }
        return values;
    }

    @Override
    public SpamResult getSpamResult(long accountId, String gmailMessageId) throws SQLException {
        try (Connection connection = connections.open()) {
            Optional<Boolean> feedback = feedback(connection, accountId, gmailMessageId);
            if (feedback.isPresent()) return corrected(feedback.get());
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT score,suspected_spam,reasons FROM spam_results WHERE account_id=? AND gmail_message_id=?")) {
                query.setLong(1, accountId);
                query.setString(2, gmailMessageId);
                try (ResultSet rows = query.executeQuery()) {
                    if (rows.next()) return new SpamResult(rows.getInt(1), rows.getBoolean(2), ReasonJson.decode(rows.getString(3)));
                }
            }
            return new SpamResult(0, false, List.of("This message has not been analyzed yet."));
        }
    }

    @Override
    public void saveFeedback(long accountId, String gmailMessageId, boolean spam) throws SQLException {
        transaction(connection -> {
            if (!messageExists(connection, accountId, gmailMessageId)) throw new SQLException("Message was not found in this account.");
            try (PreparedStatement update = connection.prepareStatement("UPDATE spam_feedback SET user_marked_spam=?,corrected_at=? "
                    + "WHERE account_id=? AND gmail_message_id=?")) {
                bindFeedback(update, accountId, gmailMessageId, spam);
                if (update.executeUpdate() > 0) return;
            }
            if (feedback(connection, accountId, gmailMessageId).isPresent()) return;
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO spam_feedback "
                    + "(user_marked_spam,corrected_at,account_id,gmail_message_id) VALUES (?,?,?,?)")) {
                bindFeedback(insert, accountId, gmailMessageId, spam);
                insert.executeUpdate();
            }
        });
    }

    private static void bindFeedback(PreparedStatement statement, long accountId, String id, boolean spam) throws SQLException {
        statement.setBoolean(1, spam);
        statement.setTimestamp(2, Timestamp.from(Instant.now()), utc());
        statement.setLong(3, accountId);
        statement.setString(4, id);
    }

    private static Optional<Boolean> feedback(Connection connection, long accountId, String id) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT user_marked_spam FROM spam_feedback WHERE account_id=? AND gmail_message_id=?")) {
            query.setLong(1, accountId);
            query.setString(2, id);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? Optional.of(rows.getBoolean(1)) : Optional.empty();
            }
        }
    }

    private static SpamResult corrected(boolean spam) {
        return new SpamResult(spam ? 100 : 0, spam, List.of(spam ? "You marked this message as spam." : "You marked this message as not spam."));
    }

    /** Compatibility entry point for Member 3's repository; automatic scores never overwrite feedback. */
    public void saveSpamResult(long accountId, String id, SpamResult result) throws SQLException {
        transaction(connection -> saveSpamResult(connection, accountId, id, result));
    }

    private void saveSpamResult(Connection connection, long accountId, String id, SpamResult result) throws SQLException {
        String reasons = ReasonJson.encode(result.getReasons());
        try (PreparedStatement update = connection.prepareStatement("UPDATE spam_results SET score=?,suspected_spam=?,reasons=?,analyzed_at=? "
                + "WHERE account_id=? AND gmail_message_id=?")) {
            bindSpam(update, accountId, id, result, reasons);
            if (update.executeUpdate() > 0) return;
        }
        try (PreparedStatement exists = connection.prepareStatement("SELECT 1 FROM spam_results WHERE account_id=? AND gmail_message_id=?")) {
            exists.setLong(1, accountId);
            exists.setString(2, id);
            try (ResultSet rows = exists.executeQuery()) { if (rows.next()) return; }
        }
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO spam_results "
                + "(score,suspected_spam,reasons,analyzed_at,account_id,gmail_message_id) VALUES (?,?,?,?,?,?)")) {
            bindSpam(insert, accountId, id, result, reasons);
            insert.executeUpdate();
        }
    }

    private static void bindSpam(PreparedStatement statement, long accountId, String id, SpamResult result, String reasons) throws SQLException {
        statement.setInt(1, result.getScore());
        statement.setBoolean(2, result.isSuspectedSpam());
        statement.setString(3, reasons);
        statement.setTimestamp(4, Timestamp.from(Instant.now()), utc());
        statement.setLong(5, accountId);
        statement.setString(6, id);
    }

    @Override
    public void deleteMessages(long accountId, Collection<String> gmailMessageIds) throws SQLException {
        transaction(connection -> deleteBatch(connection, accountId, gmailMessageIds));
    }

    private void deleteBatch(Connection connection, long accountId, Collection<String> gmailMessageIds) throws SQLException {
        for (String id : gmailMessageIds) {
            for (String table : List.of("message_recipients", "message_labels", "spam_results", "messages"))
                deleteOne(connection, table, accountId, id);
            // Feedback deliberately survives cache eviction and later re-downloads.
        }
    }

    public boolean isSenderBlocked(long accountId, String email) throws SQLException {
        try (Connection connection = connections.open()) {
            return blockedSenders(connection, accountId).contains(email.trim().toLowerCase(Locale.ROOT));
        }
    }

    public void setSenderBlocked(long accountId, String email, boolean blocked) throws SQLException {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) throw new SQLException("A sender email is required.");
        transaction(connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM blocked_senders WHERE account_id=? AND sender_email=?")) {
                delete.setLong(1, accountId);
                delete.setString(2, normalized);
                delete.executeUpdate();
            }
            if (blocked) try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO blocked_senders (account_id,sender_email) VALUES (?,?)")) {
                insert.setLong(1, accountId);
                insert.setString(2, normalized);
                insert.executeUpdate();
            }
        });
    }

    private Set<String> blockedSenders(Connection connection, long accountId) throws SQLException {
        Set<String> senders = new LinkedHashSet<>();
        try (PreparedStatement query = connection.prepareStatement("SELECT sender_email FROM blocked_senders WHERE account_id=?")) {
            query.setLong(1, accountId);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) senders.add(rows.getString(1).trim().toLowerCase(Locale.ROOT));
            }
        }
        return senders;
    }

    public void updateReadStatus(long accountId, String id, boolean read) throws SQLException {
        transaction(connection -> {
            try (PreparedStatement update = connection.prepareStatement("UPDATE messages SET is_read=? WHERE account_id=? AND gmail_message_id=?")) {
                update.setBoolean(1, read);
                update.setLong(2, accountId);
                update.setString(3, id);
                if (update.executeUpdate() == 0 && !messageExists(connection, accountId, id)) return;
            }
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM message_labels WHERE account_id=? AND gmail_message_id=? AND gmail_label_id='UNREAD'")) {
                delete.setLong(1, accountId);
                delete.setString(2, id);
                delete.executeUpdate();
            }
            if (!read) try (PreparedStatement insert = connection.prepareStatement("INSERT INTO message_labels (account_id,gmail_message_id,gmail_label_id) VALUES (?,?,'UNREAD')")) {
                insert.setLong(1, accountId);
                insert.setString(2, id);
                insert.executeUpdate();
            }
        });
    }

    private static boolean messageExists(Connection connection, long accountId, String id) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT 1 FROM messages WHERE account_id=? AND gmail_message_id=?")) {
            query.setLong(1, accountId);
            query.setString(2, id);
            try (ResultSet rows = query.executeQuery()) { return rows.next(); }
        }
    }

    private static void deleteOne(Connection connection, String table, long accountId, String id) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM " + table + " WHERE account_id=? AND gmail_message_id=?")) {
            delete.setLong(1, accountId);
            delete.setString(2, id);
            delete.executeUpdate();
        }
    }

    @FunctionalInterface private interface Transaction { void run(Connection connection) throws SQLException; }

    private void transaction(Transaction action) throws SQLException {
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            try {
                action.run(connection);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                try { connection.rollback(); } catch (SQLException rollback) { exception.addSuppressed(rollback); }
                throw exception;
            }
        }
    }

    private static Calendar utc() { return Calendar.getInstance(TimeZone.getTimeZone("UTC")); }
}
