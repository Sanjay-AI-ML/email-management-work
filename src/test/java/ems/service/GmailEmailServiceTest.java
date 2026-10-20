package ems.service;

import ems.exception.AppException;
import ems.gmail.GmailGateway;
import ems.model.*;
import ems.storage.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Service orchestration uses a real JDBC store and a deterministic remote mailbox. */
class GmailEmailServiceTest {
    private JdbcMailStore store;
    private FakeGmail gmail;
    private GmailEmailService service;

    @BeforeEach void setup() {
        String url = "jdbc:h2:mem:service-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        store = new JdbcMailStore(() -> DriverManager.getConnection(url));
        gmail = new FakeGmail();
        gmail.messages.put("received", message(0, "received", Set.of("INBOX", "UNREAD")));
        service = new GmailEmailService(gmail, store);
    }

    @Test void receiveSearchReadSendAndCorrectThroughRealJdbc() throws Exception {
        service.connectGmail(); service.refresh();
        assertEquals(1, service.getMessages(Mailbox.INBOX, "prize").size());
        assertEquals(0, gmail.modifications, "Automatic spam analysis must not change Gmail labels");
        assertTrue(service.getSpamResult("received").isSuspectedSpam());
        assertFalse(service.openMessage("received").getGmailLabelIds().contains("UNREAD"));
        assertEquals(1, gmail.modifications);
        String sentId = service.sendMessage("friend@external.test", "Hello", "A real outbound message contract.");
        assertEquals(sentId, service.getMessages(Mailbox.SENT, "Hello").get(0).getGmailMessageId());
        service.markAsNotSpam("received"); service.refresh();
        assertFalse(service.getSpamResult("received").isSuspectedSpam(), "User correction survives analysis on refresh");
        service.markAsSpam("received");
        assertEquals(1, service.getMessages(Mailbox.SPAM, "").size());
        assertEquals(0, service.getMessages(Mailbox.INBOX, "").size());
    }

    @Test void networkFailurePreservesCacheAndSuccessfulSnapshotRemovesDeletedMessages() throws Exception {
        service.connectGmail(); service.refresh(); gmail.failFetch = true;
        assertThrows(AppException.class, service::refresh);
        assertEquals(1, service.getMessages(Mailbox.INBOX, "").size());
        gmail.failFetch = false; gmail.messages.clear(); service.refresh();
        assertTrue(service.getMessages(Mailbox.INBOX, "").isEmpty());
    }

    @Test void remoteFailuresDoNotSaveUnconfirmedCorrections() throws Exception {
        service.connectGmail(); service.refresh(); gmail.failModification = true;
        assertThrows(AppException.class, () -> service.markAsNotSpam("received"));
        assertTrue(service.getSpamResult("received").isSuspectedSpam());
        assertTrue(service.getMessages(Mailbox.SPAM, "").isEmpty());
    }

    @Test void databaseFailureAfterDeliveryReturnsSuccessAndWarningWithoutAnotherSend() throws Exception {
        AtomicBoolean failWrites = new AtomicBoolean();
        MailStore unreliable = (MailStore) Proxy.newProxyInstance(MailStore.class.getClassLoader(),
                new Class<?>[] {MailStore.class}, (proxy, method, arguments) -> {
                    if (failWrites.get() && method.getName().equals("saveMessages")) throw new SQLException("Test unavailable");
                    try { return method.invoke(store, arguments); }
                    catch (InvocationTargetException exception) { throw exception.getCause(); }
                });
        service = new GmailEmailService(gmail, unreliable);
        service.connectGmail(); service.refresh(); failWrites.set(true);
        assertEquals("sent1", service.sendMessage("friend@external.test", "Hello", "Sent once."));
        assertEquals(1, gmail.sends);
        assertTrue(service.getLastWarning().contains("was sent"));
    }

    @Test void missingMessagesAndAccountSwitchCannotAccessAnotherCachedAccount() throws Exception {
        Account first = service.connectGmail(); service.refresh();
        assertThrows(AppException.class, () -> service.openMessage("someone-elses-message"));
        assertEquals(0, gmail.modifications);
        service.disconnect();
        assertThrows(AppException.class, () -> service.getMessages(Mailbox.INBOX, ""));
        gmail.email = "second@external.test"; gmail.messages.clear();
        Account second = service.connectGmail(); service.refresh();
        assertNotEquals(first.getAccountId(), second.getAccountId());
        assertTrue(service.getMessages(Mailbox.INBOX, "").isEmpty());
        assertThrows(AppException.class, () -> service.openMessage("received"));
        assertTrue(store.findMessage(first.getAccountId(), "received").isPresent());
    }

    @Test void validatesRecipientAndHeaderInjectionBeforeSending() throws Exception {
        service.connectGmail();
        assertThrows(AppException.class, () -> service.sendMessage("a@test.test,b@test.test", "Hi", "body"));
        assertThrows(AppException.class, () -> service.sendMessage("a@test.test\r\nBcc: b@test.test", "Hi", "body"));
        assertThrows(AppException.class, () -> service.sendMessage("a@test.test", "Hi\r\nBcc: b@test.test", "body"));
        assertEquals(0, gmail.sends);
    }

    private static EmailMessage message(long accountId, String id, Set<String> labels) {
        return new EmailMessage(accountId, id, "sender@external.test", List.of("student@example.test"),
                "URGENT: Claim your prize", "Verify your account https://1.test https://2.test https://3.test https://4.test https://5.test",
                Instant.parse("2026-10-20T06:00:00Z"), labels);
    }

    private static final class FakeGmail implements GmailGateway {
        String email = "student@example.test";
        final Map<String, EmailMessage> messages = new LinkedHashMap<>();
        int modifications;
        int sends;
        boolean failFetch;
        boolean failModification;
        @Override public String connect() { return email; }
        @Override public void disconnect() { }
        @Override public List<EmailMessage> fetchMessages(long accountId) throws AppException {
            if (failFetch) throw new AppException("Test network unavailable");
            return messages.values().stream().map(m -> copy(m, accountId, m.getGmailLabelIds())).toList();
        }
        @Override public EmailMessage getMessage(long accountId, String id) throws AppException {
            if (!messages.containsKey(id)) throw new AppException("Test message missing");
            EmailMessage message = messages.get(id);
            return copy(message, accountId, message.getGmailLabelIds());
        }
        @Override public String sendMessage(String sender, String recipient, String subject, String body) {
            String id = "sent" + ++sends;
            messages.put(id, new EmailMessage(0, id, sender, List.of(recipient), subject, body, Instant.now(), Set.of("SENT")));
            return id;
        }
        @Override public void setRead(String id) throws AppException {
            checkModification();
            Set<String> labels = new HashSet<>(messages.get(id).getGmailLabelIds()); labels.remove("UNREAD");
            messages.put(id, copy(messages.get(id), 0, labels));
        }
        @Override public void setSpam(String id, boolean spam) throws AppException {
            checkModification();
            Set<String> labels = new HashSet<>(messages.get(id).getGmailLabelIds());
            if (spam) { labels.add("SPAM"); labels.remove("INBOX"); }
            else { labels.remove("SPAM"); labels.add("INBOX"); }
            messages.put(id, copy(messages.get(id), 0, labels));
        }
        private void checkModification() throws AppException {
            if (failModification) throw new AppException("Test change rejected");
            modifications++;
        }
        private static EmailMessage copy(EmailMessage message, long accountId, Set<String> labels) {
            return new EmailMessage(accountId, message.getGmailMessageId(), message.getSenderEmail(), message.getRecipientEmails(),
                    message.getSubject(), message.getBodyText(), message.getReceivedAt(), labels);
        }
    }
}
