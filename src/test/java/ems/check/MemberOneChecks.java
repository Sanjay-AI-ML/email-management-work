package ems.check;

import ems.demo.DemoEmailService;
import ems.exception.AppException;
import ems.model.Account;
import ems.model.EmailMessage;
import ems.model.Mailbox;
import ems.model.SpamResult;
import ems.service.EmailService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Dependency-free contract checks. No real Gmail account, database, or window is used. */
public final class MemberOneChecks {
    private static int assertions;

    private MemberOneChecks() {
    }

    public static void main(String[] args) throws Exception {
        checkModelBoundaries();
        checkDemoFlow();
        System.out.println("PASS: " + assertions + " contract/demo assertions (headless).");
    }

    private static void checkModelBoundaries() {
        List<String> recipients = new ArrayList<>(List.of("reader@example.test"));
        Set<String> labels = new HashSet<>(Set.of("INBOX", "UNREAD"));
        EmailMessage message = new EmailMessage(1L, "boundary-test", "writer@example.test",
                recipients, "Subject", "Body", Instant.parse("2026-01-01T12:00:00Z"), labels);
        recipients.clear();
        labels.clear();
        check(message.getRecipientEmails().equals(List.of("reader@example.test")),
                "Message recipients must not change when the caller mutates its input list.");
        check(message.getGmailLabelIds().equals(Set.of("INBOX", "UNREAD")),
                "Message labels must not change when the caller mutates its input set.");
        expectUnsupported(() -> message.getRecipientEmails().clear(),
                "Recipient lists must not permit callers to change cached state.");
        expectUnsupported(() -> message.getGmailLabelIds().clear(),
                "Label sets must not permit callers to change cached state.");

        List<String> reasons = new ArrayList<>(List.of("Blocked sender"));
        SpamResult result = new SpamResult(80, true, reasons);
        reasons.clear();
        check(result.getReasons().equals(List.of("Blocked sender")),
                "Spam reasons must be copied before they cross the UI/backend boundary.");
        expectUnsupported(() -> result.getReasons().clear(),
                "Spam reasons must not permit callers to change cached state.");
    }

    private static void checkDemoFlow() throws AppException {
        EmailService service = new DemoEmailService();
        expectAppException(() -> service.getMessages(Mailbox.INBOX, ""),
                "Mailbox access must require a connection.");
        expectAppException(service::refresh, "Refresh must require a connection.");

        Account account = service.connectGmail();
        check(account.getAccountId() > 0 && account.getEmailAddress().endsWith(".test"),
                "The demo must connect to a fictional account.");
        List<EmailMessage> inbox = service.getMessages(Mailbox.INBOX, "");
        check(!inbox.isEmpty(), "Connected demo must provide inbox fixtures.");
        checkNewestFirst(inbox);
        check(inbox.stream().allMatch(message -> message.getAccountId() == account.getAccountId()),
                "Inbox messages must belong to the connected account.");

        EmailMessage meeting = find(inbox, "demo-meeting");
        check(meeting.getGmailLabelIds().contains("UNREAD"), "Meeting fixture should begin unread.");
        check(contains(service.getMessages(Mailbox.INBOX,
                        meeting.getSubject().toUpperCase(Locale.ROOT)), meeting.getGmailMessageId()),
                "Search should match subjects without case sensitivity.");
        check(contains(service.getMessages(Mailbox.INBOX,
                        meeting.getSenderEmail().toUpperCase(Locale.ROOT)), meeting.getGmailMessageId()),
                "Search should match the sender.");
        check(contains(service.getMessages(Mailbox.INBOX, meeting.getBodyText()),
                        meeting.getGmailMessageId()),
                "Search should match the message body.");
        check(contains(service.getMessages(Mailbox.INBOX, meeting.getRecipientEmails().get(0)),
                        meeting.getGmailMessageId()),
                "Search should match recipients.");
        check(service.getMessages(Mailbox.INBOX, "this-search-has-no-match-90453").isEmpty(),
                "Search should return an empty list when nothing matches.");

        EmailMessage opened = service.openMessage(meeting.getGmailMessageId());
        check(!opened.getGmailLabelIds().contains("UNREAD"), "Opening an email must mark it read.");
        check(!find(service.getMessages(Mailbox.INBOX, ""), meeting.getGmailMessageId())
                        .getGmailLabelIds().contains("UNREAD"),
                "Read status must persist within the demo session.");

        int sentCount = service.getMessages(Mailbox.SENT, "").size();
        String sentId = service.sendMessage("outside@example.test", "Project update", "The UI is ready.");
        check(sentId != null && !sentId.isBlank(), "Sending must return a message ID.");
        List<EmailMessage> sent = service.getMessages(Mailbox.SENT, "");
        check(sent.size() == sentCount + 1, "Sending must add exactly one Sent entry.");
        EmailMessage delivered = find(sent, sentId);
        check(delivered.getSenderEmail().equals(account.getEmailAddress()),
                "The sender must be the connected account.");
        check(delivered.getRecipientEmails().contains("outside@example.test"),
                "The demo must accept an external recipient.");
        check(delivered.getSubject().equals("Project update")
                        && delivered.getBodyText().equals("The UI is ready."),
                "Sent content must match what the compose form supplied.");
        check(!contains(service.getMessages(Mailbox.INBOX, ""), sentId),
                "Sending to an external recipient should not add it to the demo Inbox.");

        EmailMessage untouched = find(service.getMessages(Mailbox.INBOX, ""), "demo-library");
        EmailMessage suspicious = find(service.getMessages(Mailbox.SUSPECTED, ""), "demo-invoice");
        SpamResult originalScore = service.getSpamResult(suspicious.getGmailMessageId());
        check(originalScore.isSuspectedSpam() && !originalScore.getReasons().isEmpty(),
                "Suspected messages must provide a classification and reasons.");
        check(contains(service.getMessages(Mailbox.SPAM, ""), "demo-prize"),
                "Gmail Spam fixtures must be available in the separate Spam mailbox.");
        check(!contains(service.getMessages(Mailbox.SUSPECTED, ""), "demo-prize"),
                "Confirmed Spam should not also be queued for local suspicion review.");

        service.markAsSpam(suspicious.getGmailMessageId());
        EmailMessage spam = find(service.getMessages(Mailbox.SPAM, ""), suspicious.getGmailMessageId());
        check(spam.getGmailLabelIds().contains("SPAM") && !spam.getGmailLabelIds().contains("INBOX"),
                "Spam correction must move the message from Inbox to Spam.");
        Set<String> otherLabels = new HashSet<>(suspicious.getGmailLabelIds());
        otherLabels.remove("INBOX");
        otherLabels.remove("SPAM");
        check(spam.getGmailLabelIds().containsAll(otherLabels),
                "Spam correction must preserve unrelated Gmail labels.");

        service.markAsNotSpam(suspicious.getGmailMessageId());
        EmailMessage restored = find(service.getMessages(Mailbox.INBOX, ""), suspicious.getGmailMessageId());
        check(restored.getGmailLabelIds().contains("INBOX") && !restored.getGmailLabelIds().contains("SPAM"),
                "Not-spam correction must return the message to Inbox.");
        check(!service.getSpamResult(suspicious.getGmailMessageId()).isSuspectedSpam(),
                "Not-spam correction must override local suspicion.");
        check(!contains(service.getMessages(Mailbox.SUSPECTED, ""), suspicious.getGmailMessageId()),
                "Corrected messages must leave the suspicion review queue.");
        check(contains(service.getMessages(Mailbox.SUSPECTED, ""), "demo-password"),
                "Correcting one message must not clear another message's classification.");
        check(find(service.getMessages(Mailbox.INBOX, ""), untouched.getGmailMessageId())
                        .getGmailLabelIds().equals(untouched.getGmailLabelIds()),
                "Correcting one message must preserve other messages' labels.");

        expectAppException(() -> service.openMessage("another-account-message"),
                "Opening an unknown or unavailable message must fail.");
        expectAppException(() -> service.getSpamResult("another-account-message"),
                "Spam details for an unavailable message must fail.");
        expectAppException(() -> service.markAsSpam("another-account-message"),
                "Spam corrections for an unavailable message must fail.");
        expectAppException(() -> service.markAsNotSpam("another-account-message"),
                "Not-spam corrections for an unavailable message must fail.");
        expectAppException(() -> service.markAsSpam(sentId),
                "A sent message must not be moved into received Spam.");

        Set<String> inboxBeforeRefresh = ids(service.getMessages(Mailbox.INBOX, ""));
        Set<String> sentBeforeRefresh = ids(service.getMessages(Mailbox.SENT, ""));
        service.refresh();
        List<EmailMessage> refreshedInbox = service.getMessages(Mailbox.INBOX, "");
        check(ids(refreshedInbox).equals(inboxBeforeRefresh)
                        && refreshedInbox.size() == inboxBeforeRefresh.size(),
                "Repeated demo refresh must not lose or duplicate inbox messages.");
        check(ids(service.getMessages(Mailbox.SENT, "")).equals(sentBeforeRefresh),
                "Refresh must preserve sent messages.");
        check(!service.getSpamResult(suspicious.getGmailMessageId()).isSuspectedSpam(),
                "Refresh must preserve a not-spam correction.");

        service.disconnect();
        expectAppException(() -> service.getMessages(Mailbox.INBOX, ""),
                "Disconnect must prevent subsequent mailbox access.");
        expectAppException(() -> service.openMessage(meeting.getGmailMessageId()),
                "Disconnect must prevent message opening.");
        expectAppException(() -> service.sendMessage("outside@example.test", "Subject", "Body"),
                "Disconnect must prevent sending.");
    }

    private static void checkNewestFirst(List<EmailMessage> messages) {
        for (int index = 1; index < messages.size(); index++) {
            check(!messages.get(index).getReceivedAt().isAfter(messages.get(index - 1).getReceivedAt()),
                    "Mailbox messages must be ordered newest first.");
        }
    }

    private static EmailMessage find(List<EmailMessage> messages, String messageId) {
        return messages.stream().filter(message -> message.getGmailMessageId().equals(messageId))
                .findFirst().orElseThrow(() -> new AssertionError("Missing message: " + messageId));
    }

    private static boolean contains(List<EmailMessage> messages, String messageId) {
        return messages.stream().anyMatch(message -> message.getGmailMessageId().equals(messageId));
    }

    private static Set<String> ids(List<EmailMessage> messages) {
        return messages.stream().map(EmailMessage::getGmailMessageId).collect(Collectors.toSet());
    }

    private static void check(boolean condition, String description) {
        assertions++;
        if (!condition) {
            throw new AssertionError(description);
        }
    }

    private static void expectAppException(CheckedAction action, String description) {
        assertions++;
        try {
            action.run();
        } catch (AppException expected) {
            return;
        }
        throw new AssertionError(description);
    }

    private static void expectUnsupported(Runnable action, String description) {
        assertions++;
        try {
            action.run();
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError(description);
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws AppException;
    }
}
