package ems.service;

import ems.config.AppConfig;
import ems.exception.AppException;
import ems.gmail.GmailGateway;
import ems.model.*;
import ems.storage.JdbcMailStore;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real MySQL round trip; Gmail remains a fake so no real email is sent. */
@EnabledIfSystemProperty(named = "maildesk.mysqlTest", matches = "true")
class RealMySqlTest {
    @Test void realDatabasePersistsTheCompleteServiceFlow() throws Exception {
        AppConfig config = AppConfig.load();
        JdbcMailStore store = new JdbcMailStore(() -> connect(config));
        String email = "maildesk-check-" + UUID.randomUUID() + "@example.test";
        TestGateway gateway = new TestGateway(email);
        GmailEmailService service = new GmailEmailService(gateway, store);
        Account account = null;
        try {
            account = service.connectGmail();
            service.refresh();
            assertEquals(1, service.getMessages(Mailbox.INBOX, "café").size());
            assertTrue(service.getSpamResult("incoming").isSuspectedSpam());
            assertFalse(service.openMessage("incoming").getGmailLabelIds().contains("UNREAD"));
            assertEquals("outgoing", service.sendMessage("friend@external.test", "Project update café", "MySQL integration fixture."));
            assertEquals(1, service.getMessages(Mailbox.SENT, "café").size());
            service.markAsNotSpam("incoming"); service.refresh();
            assertFalse(service.getSpamResult("incoming").isSuspectedSpam());
            service.markAsSpam("incoming");
            assertEquals(1, service.getMessages(Mailbox.SPAM, "").size());
            assertTrue(service.getMessages(Mailbox.SUSPECTED, "").isEmpty());
            JdbcMailStore reopened = new JdbcMailStore(() -> connect(config));
            assertEquals(Set.of("SPAM"), reopened.findMessage(account.getAccountId(), "incoming").orElseThrow().getGmailLabelIds());
            assertTrue(reopened.getSpamResult(account.getAccountId(), "incoming").isSuspectedSpam());
            gateway.messages.remove("incoming"); service.refresh();
            assertTrue(service.getMessages(Mailbox.SPAM, "").isEmpty());
        } finally {
            service.disconnect();
            if (account != null) removeFixture(config, account, email);
        }
    }

    private static Connection connect(AppConfig config) throws SQLException {
        return DriverManager.getConnection(config.getDatabaseUrl(), config.getDatabaseUser(), config.getDatabasePassword());
    }

    private static void removeFixture(AppConfig config, Account account, String expectedEmail) throws SQLException {
        // Every cleanup statement is scoped to this newly created random fixture account.
        try (Connection connection = connect(config)) {
            connection.setAutoCommit(false);
            try {
                for (String table : List.of("spam_feedback", "blocked_senders", "messages")) {
                    try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table + " WHERE account_id=?")) {
                        statement.setLong(1, account.getAccountId()); statement.executeUpdate();
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("DELETE FROM accounts WHERE account_id=? AND email_address=?")) {
                    statement.setLong(1, account.getAccountId()); statement.setString(2, expectedEmail); statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException exception) { connection.rollback(); throw exception; }
        }
    }

    private static final class TestGateway implements GmailGateway {
        private final String email;
        private final Map<String, EmailMessage> messages = new LinkedHashMap<>();
        TestGateway(String email) {
            this.email = email;
            messages.put("incoming", new EmailMessage(0, "incoming", "sender@external.test", List.of(email),
                    "URGENT: Claim your prize café", "Verify your account https://a.test https://b.test https://c.test https://d.test https://e.test",
                    Instant.parse("2026-10-20T06:00:00.123456Z"), Set.of("INBOX", "UNREAD")));
        }
        @Override public String connect() { return email; }
        @Override public void disconnect() { }
        @Override public List<EmailMessage> fetchMessages(long accountId) {
            return messages.values().stream().map(m -> copy(m, accountId, m.getGmailLabelIds())).toList();
        }
        @Override public EmailMessage getMessage(long accountId, String id) throws AppException {
            EmailMessage message = messages.get(id);
            if (message == null) throw new AppException("Fixture missing");
            return copy(message, accountId, message.getGmailLabelIds());
        }
        @Override public String sendMessage(String sender, String recipient, String subject, String body) {
            messages.put("outgoing", new EmailMessage(0, "outgoing", sender, List.of(recipient), subject, body, Instant.now(), Set.of("SENT")));
            return "outgoing";
        }
        @Override public void setRead(String id) {
            Set<String> labels = new HashSet<>(messages.get(id).getGmailLabelIds()); labels.remove("UNREAD");
            messages.put(id, copy(messages.get(id), 0, labels));
        }
        @Override public void setSpam(String id, boolean spam) {
            Set<String> labels = new HashSet<>(messages.get(id).getGmailLabelIds());
            if (spam) { labels.add("SPAM"); labels.remove("INBOX"); } else { labels.remove("SPAM"); labels.add("INBOX"); }
            messages.put(id, copy(messages.get(id), 0, labels));
        }
        private static EmailMessage copy(EmailMessage message, long accountId, Set<String> labels) {
            return new EmailMessage(accountId, message.getGmailMessageId(), message.getSenderEmail(), message.getRecipientEmails(),
                    message.getSubject(), message.getBodyText(), message.getReceivedAt(), labels);
        }
    }
}
