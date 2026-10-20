package ems.storage;

import ems.model.Account;
import ems.model.EmailMessage;
import ems.model.Mailbox;
import ems.model.SpamResult;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JdbcMailStoreTest {
    private JdbcMailStore store;
    private String url;
    private Account alice;
    private Account bob;

    @BeforeEach void prepareDatabase() throws SQLException {
        url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        store = new JdbcMailStore(() -> DriverManager.getConnection(url));
        store.initialize();
        alice = store.findOrCreateAccount("alice@example.com");
        bob = store.findOrCreateAccount("bob@example.com");
    }

    private EmailMessage message(Account account, String id, String subject, String body, Set<String> labels) {
        return new EmailMessage(account.getAccountId(), id, "sender@example.com", List.of("recipient@example.com"),
                subject, body, Instant.parse("2026-10-20T10:15:00.123456Z"), labels);
    }

    private EmailMessage normal(Account account, String id) {
        return message(account, id, "Meeting notes", "Project update", Set.of("INBOX", "UNREAD"));
    }

    private EmailMessage suspicious(Account account, String id) {
        return message(account, id, "URGENT: Claim your prize", "You have won! Verify your account now.", Set.of("INBOX"));
    }

    @Test void initializerIsIdempotentAndAccountAddressesAreNormalized() throws SQLException {
        store.initialize();
        Account same = store.findOrCreateAccount("  ALICE@EXAMPLE.COM ");
        assertEquals(alice.getAccountId(), same.getAccountId());
        assertEquals("alice@example.com", same.getEmailAddress());
        assertNotEquals(alice.getAccountId(), bob.getAccountId());
    }

    @Test void refreshUpsertHasNoDuplicatesAndReplacesRecipientsAndLabels() throws SQLException {
        EmailMessage original = normal(alice, "same");
        store.saveMessages(alice.getAccountId(), List.of(original));
        store.saveMessages(alice.getAccountId(), List.of(original));
        EmailMessage moved = new EmailMessage(alice.getAccountId(), "same", "sender@example.com",
                List.of("new@example.com", "new@example.com"), "Changed", "New body", original.getReceivedAt(), Set.of("SPAM"));
        store.saveMessages(alice.getAccountId(), List.of(moved));

        assertEquals(1, store.findAll(alice.getAccountId()).size());
        EmailMessage saved = store.findMessage(alice.getAccountId(), "same").orElseThrow();
        assertEquals(List.of("new@example.com"), saved.getRecipientEmails());
        assertEquals(Set.of("SPAM"), saved.getGmailLabelIds());
        assertEquals("Changed", saved.getSubject());
        assertTrue(store.getMessages(alice.getAccountId(), Mailbox.INBOX, "").isEmpty());
        assertEquals(1, store.getMessages(alice.getAccountId(), Mailbox.SPAM, "").size());
        assertTrue(readColumn(alice.getAccountId(), "same"));
    }

    @Test void timestampRoundTripsAsAnInstant() throws SQLException {
        EmailMessage original = normal(alice, "time");
        store.saveMessages(alice.getAccountId(), List.of(original));
        assertEquals(original.getReceivedAt(), store.findMessage(alice.getAccountId(), "time").orElseThrow().getReceivedAt());
    }

    @Test void repeatedRecipientsAcrossHeadersAreDeduplicatedIgnoringCase() throws SQLException {
        EmailMessage original = normal(alice, "recipients");
        EmailMessage repeated = new EmailMessage(alice.getAccountId(), original.getGmailMessageId(),
                original.getSenderEmail(), List.of("Person@example.com", "person@EXAMPLE.COM"),
                original.getSubject(), original.getBodyText(), original.getReceivedAt(), original.getGmailLabelIds());
        store.saveMessages(alice.getAccountId(), List.of(repeated));
        assertEquals(List.of("Person@example.com"), store.findMessage(alice.getAccountId(), "recipients")
                .orElseThrow().getRecipientEmails());
    }

    @Test void incomingUnreadLabelControlsReadStatusAndLegacyMethodKeepsItConsistent() throws SQLException {
        store.saveMessages(alice.getAccountId(), List.of(normal(alice, "unread")));
        assertFalse(readColumn(alice.getAccountId(), "unread"));
        store.updateReadStatus(alice.getAccountId(), "unread", true);
        assertTrue(readColumn(alice.getAccountId(), "unread"));
        assertFalse(store.findMessage(alice.getAccountId(), "unread").orElseThrow().getGmailLabelIds().contains("UNREAD"));
        store.updateReadStatus(alice.getAccountId(), "unread", false);
        store.updateReadStatus(alice.getAccountId(), "unread", false);
        assertFalse(readColumn(alice.getAccountId(), "unread"));
        assertTrue(store.findMessage(alice.getAccountId(), "unread").orElseThrow().getGmailLabelIds().contains("UNREAD"));
    }

    @Test void accountIsolationAppliesToEveryReadWriteAndFeedback() throws SQLException {
        store.saveMessages(alice.getAccountId(), List.of(normal(alice, "same-id")));
        store.saveMessages(bob.getAccountId(), List.of(suspicious(bob, "same-id")));
        store.saveFeedback(alice.getAccountId(), "same-id", false);
        assertEquals("Meeting notes", store.findMessage(alice.getAccountId(), "same-id").orElseThrow().getSubject());
        assertTrue(store.getSpamResult(bob.getAccountId(), "same-id").isSuspectedSpam());
        assertFalse(store.getSpamResult(alice.getAccountId(), "same-id").isSuspectedSpam());
        assertThrows(SQLException.class, () -> store.saveMessages(alice.getAccountId(), List.of(normal(bob, "bad"))));
        store.deleteMessages(alice.getAccountId(), List.of("same-id"));
        assertTrue(store.findMessage(alice.getAccountId(), "same-id").isEmpty());
        assertTrue(store.findMessage(bob.getAccountId(), "same-id").isPresent());
        assertThrows(SQLException.class, () -> store.saveFeedback(alice.getAccountId(), "missing", true));
    }

    @Test void accountWithNoMessagesReturnsEmptyLists() throws SQLException {
        for (Mailbox mailbox : Mailbox.values()) assertTrue(store.getMessages(bob.getAccountId(), mailbox, "").isEmpty());
        assertTrue(store.findMessage(bob.getAccountId(), "missing").isEmpty());
    }

    @Test void localSpamAndGmailSpamAreDistinct() throws SQLException {
        store.saveMessages(alice.getAccountId(), List.of(suspicious(alice, "local"),
                message(alice, "gmail", "Meeting notes", "Project update", Set.of("SPAM"))));
        assertEquals(List.of("local"), store.getMessages(alice.getAccountId(), Mailbox.SUSPECTED, "").stream().map(EmailMessage::getGmailMessageId).toList());
        assertEquals(List.of("gmail"), store.getMessages(alice.getAccountId(), Mailbox.SPAM, "").stream().map(EmailMessage::getGmailMessageId).toList());
        assertFalse(store.getSpamResult(alice.getAccountId(), "gmail").isSuspectedSpam());
    }

    @Test void suspectedReviewExcludesGmailSpamAndAllSentMailEvenWhenClassifierFlagsThem() throws SQLException {
        String subject = "URGENT: Claim your prize";
        String body = "You have won! Verify your account now.";
        store.saveMessages(alice.getAccountId(), List.of(suspicious(alice, "review"),
                message(alice, "gmail-spam", subject, body, Set.of("SPAM")),
                message(alice, "sent", subject, body, Set.of("SENT")),
                message(alice, "self-sent", subject, body, Set.of("SENT", "INBOX"))));
        assertTrue(store.getSpamResult(alice.getAccountId(), "gmail-spam").isSuspectedSpam());
        assertTrue(store.getSpamResult(alice.getAccountId(), "sent").isSuspectedSpam());
        assertTrue(store.getSpamResult(alice.getAccountId(), "self-sent").isSuspectedSpam());
        assertEquals(List.of("review"), store.getMessages(alice.getAccountId(), Mailbox.SUSPECTED, "")
                .stream().map(EmailMessage::getGmailMessageId).toList());
        assertEquals(1, store.getMessages(alice.getAccountId(), Mailbox.SPAM, "").size());
        assertEquals(2, store.getMessages(alice.getAccountId(), Mailbox.SENT, "").size());
        store.saveFeedback(alice.getAccountId(), "gmail-spam", true);
        assertEquals(1, store.getMessages(alice.getAccountId(), Mailbox.SUSPECTED, "").size());
    }

    @Test void userFeedbackSurvivesRefreshAndNewStoreInstance() throws SQLException {
        EmailMessage mail = suspicious(alice, "corrected");
        store.saveMessages(alice.getAccountId(), List.of(mail));
        store.saveFeedback(alice.getAccountId(), "corrected", false);
        store.saveMessages(alice.getAccountId(), List.of(mail));
        store = new JdbcMailStore(() -> DriverManager.getConnection(url));
        assertFalse(store.getSpamResult(alice.getAccountId(), "corrected").isSuspectedSpam());
        assertTrue(store.getMessages(alice.getAccountId(), Mailbox.SUSPECTED, "").isEmpty());
        store.saveFeedback(alice.getAccountId(), "corrected", true);
        store.saveMessages(alice.getAccountId(), List.of(normal(alice, "corrected")));
        assertTrue(store.getSpamResult(alice.getAccountId(), "corrected").isSuspectedSpam());
        assertEquals(1, store.getMessages(alice.getAccountId(), Mailbox.SUSPECTED, "").size());
    }

    @Test void snapshotPrunesRemovedMessagesAtomicallyAndFeedbackSurvivesRedownload() throws SQLException {
        store.saveMessages(alice.getAccountId(), List.of(normal(alice, "keep"), suspicious(alice, "remove")));
        store.saveMessages(bob.getAccountId(), List.of(normal(bob, "remove")));
        store.saveFeedback(alice.getAccountId(), "remove", false);
        store.replaceSnapshot(alice.getAccountId(), List.of(normal(alice, "keep")));
        assertTrue(store.findMessage(alice.getAccountId(), "remove").isEmpty());
        assertTrue(store.findMessage(bob.getAccountId(), "remove").isPresent());
        store.saveMessages(alice.getAccountId(), List.of(suspicious(alice, "remove")));
        assertFalse(store.getSpamResult(alice.getAccountId(), "remove").isSuspectedSpam());
        store.replaceSnapshot(alice.getAccountId(), List.of());
        assertTrue(store.findAll(alice.getAccountId()).isEmpty());
        assertFalse(store.findAll(bob.getAccountId()).isEmpty());
    }

    @Test void invalidBatchRollsBackContentRecipientsLabelsScoresAndSnapshotPruning() throws SQLException {
        store.saveMessages(alice.getAccountId(), List.of(normal(alice, "keep"), normal(alice, "existing")));
        EmailMessage invalid = new EmailMessage(alice.getAccountId(), "invalid", "sender@example.com",
                List.of("x".repeat(321)), "subject", "body", Instant.now(), Set.of("INBOX"));
        assertThrows(SQLException.class, () -> store.replaceSnapshot(alice.getAccountId(), List.of(suspicious(alice, "existing"), invalid)));
        assertEquals("Meeting notes", store.findMessage(alice.getAccountId(), "existing").orElseThrow().getSubject());
        assertEquals(Set.of("INBOX", "UNREAD"), store.findMessage(alice.getAccountId(), "existing").orElseThrow().getGmailLabelIds());
        assertFalse(store.getSpamResult(alice.getAccountId(), "existing").isSuspectedSpam());
        assertTrue(store.findMessage(alice.getAccountId(), "invalid").isEmpty());
        assertTrue(store.findMessage(alice.getAccountId(), "keep").isPresent());
    }

    @Test void searchIsCaseInsensitiveMatchesAllFieldsAndEscapesLikeWildcards() throws SQLException {
        store.saveMessages(alice.getAccountId(), List.of(
                message(alice, "literal", "100%_done!", "Project update", Set.of("INBOX")),
                normal(alice, "other")));
        assertEquals(1, store.getMessages(alice.getAccountId(), Mailbox.INBOX, "%_done!").size());
        assertEquals(2, store.getMessages(alice.getAccountId(), Mailbox.INBOX, "PROJECT UPDATE").size());
        assertEquals(2, store.getMessages(alice.getAccountId(), Mailbox.INBOX, "RECIPIENT@EXAMPLE.COM").size());
        assertEquals(2, store.getMessages(alice.getAccountId(), Mailbox.INBOX, "SENDER@EXAMPLE.COM").size());
        assertTrue(store.getMessages(alice.getAccountId(), Mailbox.INBOX, "' OR 1=1 --").isEmpty());
    }

    @Test void sentAndTrashViewsFollowGmailLabels() throws SQLException {
        store.saveMessages(alice.getAccountId(), List.of(normal(alice, "inbox"),
                message(alice, "sent", "Sent", "Hello", Set.of("SENT")),
                message(alice, "trashed", "Trash", "Hello", Set.of("INBOX", "TRASH"))));
        assertEquals(1, store.getMessages(alice.getAccountId(), Mailbox.INBOX, "").size());
        assertEquals(1, store.getMessages(alice.getAccountId(), Mailbox.SENT, "").size());
        assertEquals("sent", store.getMessages(alice.getAccountId(), Mailbox.SENT, "").get(0).getGmailMessageId());
    }

    @Test void blockedSendersAreNormalizedAndScopedToTheirAccount() throws SQLException {
        store.setSenderBlocked(alice.getAccountId(), " SENDER@EXAMPLE.COM ", true);
        store.setSenderBlocked(alice.getAccountId(), "sender@example.com", true);
        assertTrue(store.isSenderBlocked(alice.getAccountId(), "sender@example.com"));
        assertFalse(store.isSenderBlocked(bob.getAccountId(), "sender@example.com"));
        store.saveMessages(alice.getAccountId(), List.of(normal(alice, "blocked")));
        store.saveMessages(bob.getAccountId(), List.of(normal(bob, "normal")));
        assertTrue(store.getSpamResult(alice.getAccountId(), "blocked").isSuspectedSpam());
        assertFalse(store.getSpamResult(bob.getAccountId(), "normal").isSuspectedSpam());
        store.setSenderBlocked(alice.getAccountId(), "sender@example.com", false);
        store.saveMessages(alice.getAccountId(), List.of(normal(alice, "blocked")));
        assertFalse(store.getSpamResult(alice.getAccountId(), "blocked").isSuspectedSpam());
    }

    @Test void reasonJsonRoundTripsPunctuationControlCharactersAndUnicode() throws SQLException {
        store.saveMessages(alice.getAccountId(), List.of(normal(alice, "json")));
        List<String> reasons = List.of("A quote: \" and slash: \\", "line\nbreak\tand\u0001", "தமிழ் 🎉");
        store.saveSpamResult(alice.getAccountId(), "json", new SpamResult(60, true, reasons));
        assertEquals(reasons, store.getSpamResult(alice.getAccountId(), "json").getReasons());
    }

    @Test void messageBodiesMayExceedMysqlTextLimit() throws SQLException {
        String body = "Long message body. ".repeat(10_000);
        store.saveMessages(alice.getAccountId(), List.of(message(alice, "large", "Large", body, Set.of("INBOX"))));
        assertEquals(body, store.findMessage(alice.getAccountId(), "large").orElseThrow().getBodyText());
    }

    private boolean readColumn(long accountId, String id) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url);
                PreparedStatement query = connection.prepareStatement("SELECT is_read FROM messages WHERE account_id=? AND gmail_message_id=?")) {
            query.setLong(1, accountId);
            query.setString(2, id);
            try (ResultSet rows = query.executeQuery()) { assertTrue(rows.next()); return rows.getBoolean(1); }
        }
    }
}
