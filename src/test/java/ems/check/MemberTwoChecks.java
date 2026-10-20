package ems.check;

import ems.exception.AppException;
import ems.gmail.SimpleJson;
import ems.model.Account;
import ems.model.EmailMessage;
import ems.model.Mailbox;
import ems.model.SpamResult;
import ems.service.EmailService;
import ems.service.GmailEmailService;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Headless automated test suite verifying Member 2 implementation contracts.
 */
public final class MemberTwoChecks {

    private static int assertions = 0;

    private MemberTwoChecks() {
    }

    public static void main(String[] args) throws Exception {
        testModelContracts();
        testJsonParser();
        testServiceDisconnectSafety();
        System.out.println("PASS: " + assertions + " Member 2 contract assertions (headless).");
    }

    private static void testModelContracts() {
        // Account contract
        Account account = new Account(101L, "user@example.test");
        check(account.getAccountId() == 101L, "Account accountId should match constructor.");
        check("user@example.test".equals(account.getEmailAddress()), "Account emailAddress should match.");

        // EmailMessage contract
        Instant now = Instant.now();
        EmailMessage msg = new EmailMessage(
                101L,
                "msg-1",
                "sender@example.test",
                List.of("recipient@example.test"),
                "Hello World",
                "This is test body text",
                now,
                Set.of("INBOX", "UNREAD")
        );
        check(msg.getAccountId() == 101L, "EmailMessage accountId should match.");
        check("msg-1".equals(msg.getGmailMessageId()), "EmailMessage messageId should match.");
        check("sender@example.test".equals(msg.getSenderEmail()), "EmailMessage sender should match.");
        check(msg.getRecipientEmails().contains("recipient@example.test"), "EmailMessage recipient should match.");
        check("Hello World".equals(msg.getSubject()), "EmailMessage subject should match.");
        check("This is test body text".equals(msg.getBodyText()), "EmailMessage body should match.");
        check(msg.getReceivedAt().equals(now), "EmailMessage receivedAt should match.");
        check(msg.getGmailLabelIds().contains("INBOX"), "EmailMessage labels should match.");

        // SpamResult contract
        SpamResult spam = new SpamResult(85, true, List.of("Phishing phrase detected"));
        check(spam.getScore() == 85, "SpamResult score should match.");
        check(spam.isSuspectedSpam(), "SpamResult suspectedSpam should match.");
        check(spam.getReasons().size() == 1, "SpamResult reasons should match.");

        // Mailbox enum
        check(Mailbox.valueOf("INBOX") == Mailbox.INBOX, "Mailbox INBOX should exist.");
        check(Mailbox.valueOf("SENT") == Mailbox.SENT, "Mailbox SENT should exist.");
        check(Mailbox.valueOf("SPAM") == Mailbox.SPAM, "Mailbox SPAM should exist.");
        check(Mailbox.valueOf("SUSPECTED") == Mailbox.SUSPECTED, "Mailbox SUSPECTED should exist.");
    }

    private static void testJsonParser() {
        String json = "{\"id\":\"12345\",\"labelIds\":[\"INBOX\",\"UNREAD\"],\"snippet\":\"Hi there\",\"valid\":true,\"count\":42}";
        Map<String, Object> parsed = SimpleJson.parseObject(json);
        check("12345".equals(SimpleJson.getString(parsed, "id")), "Parsed string ID should match.");
        check("Hi there".equals(SimpleJson.getString(parsed, "snippet")), "Parsed snippet should match.");
        List<Object> labels = SimpleJson.getList(parsed, "labelIds");
        check(labels.size() == 2, "Parsed labels count should match.");
        check("INBOX".equals(labels.get(0)), "First label should be INBOX.");
        check(Boolean.TRUE.equals(parsed.get("valid")), "Parsed boolean should match.");
        check(Integer.valueOf(42).equals(((Number) parsed.get("count")).intValue()), "Parsed number should match.");
    }

    private static void testServiceDisconnectSafety() throws AppException {
        EmailService service = GmailEmailService.createDefault();

        expectAppException(() -> service.getMessages(Mailbox.INBOX, ""),
                "Calls before connecting must fail with AppException.");
        expectAppException(() -> service.openMessage("msg-1"),
                "Opening message before connecting must fail with AppException.");
        expectAppException(() -> service.sendMessage("test@example.com", "Subj", "Body"),
                "Sending message before connecting must fail with AppException.");
        expectAppException(() -> service.getSpamResult("msg-1"),
                "Getting spam result before connecting must fail with AppException.");
        expectAppException(() -> service.markAsSpam("msg-1"),
                "Marking spam before connecting must fail with AppException.");
        expectAppException(() -> service.markAsNotSpam("msg-1"),
                "Marking not spam before connecting must fail with AppException.");
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expectAppException(ThrowingRunnable action, String message) {
        assertions++;
        try {
            action.run();
            throw new AssertionError("Expected AppException, but none was thrown: " + message);
        } catch (AppException expected) {
            // Success
        } catch (Exception e) {
            throw new AssertionError("Expected AppException, but got: " + e.getClass().getName() + " - " + message, e);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
