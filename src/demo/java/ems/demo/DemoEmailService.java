package ems.demo;

import ems.exception.AppException;
import ems.model.Account;
import ems.model.EmailMessage;
import ems.model.Mailbox;
import ems.model.SpamResult;
import ems.service.EmailService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * In-memory demonstration only. This class never authenticates with Google,
 * contacts a mail server, sends an actual email, or stores data on disk.
 */
public final class DemoEmailService implements EmailService {
    private static final Account ACCOUNT = new Account(1L, "student@example.test");
    private static final Pattern EMAIL_ADDRESS = Pattern.compile(
            "^[^\\s@,;]+@[^\\s@,;]+\\.[^\\s@,;]+$");
    private static final Instant SAMPLE_TIME = Instant.parse("2026-10-20T06:00:00Z");

    private final Map<String, EmailMessage> messages = new LinkedHashMap<>();
    private final Map<String, SpamResult> spamResults = new LinkedHashMap<>();
    private final Map<String, Boolean> spamOverrides = new LinkedHashMap<>();
    private boolean connected;
    private long nextSentId = 1L;

    public DemoEmailService() {
        seedMessages();
    }

    @Override
    public synchronized Account connectGmail() {
        connected = true;
        return ACCOUNT;
    }

    @Override
    public synchronized void disconnect() {
        connected = false;
    }

    @Override
    public synchronized void refresh() throws AppException {
        requireConnected();
        // Demo data is local. Explicit corrections survive refresh/reconnect.
        for (String messageId : messages.keySet()) {
            Boolean override = spamOverrides.get(messageId);
            if (override != null) {
                spamResults.put(messageId, correctionResult(override));
            }
        }
    }

    @Override
    public synchronized List<EmailMessage> getMessages(Mailbox mailbox, String query)
            throws AppException {
        requireConnected();
        if (mailbox == null) {
            throw new AppException("Choose a mailbox first.");
        }
        String search = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        List<EmailMessage> result = new ArrayList<>();
        for (EmailMessage message : messages.values()) {
            if (belongsToMailbox(message, mailbox) && matchesSearch(message, search)) {
                result.add(message);
            }
        }
        result.sort(Comparator.comparing(EmailMessage::getReceivedAt)
                .reversed().thenComparing(EmailMessage::getGmailMessageId));
        return List.copyOf(result);
    }

    @Override
    public synchronized EmailMessage openMessage(String gmailMessageId) throws AppException {
        EmailMessage message = requireMessage(gmailMessageId);
        if (message.getGmailLabelIds().contains("UNREAD")) {
            Set<String> labels = new LinkedHashSet<>(message.getGmailLabelIds());
            labels.remove("UNREAD");
            message = withLabels(message, labels);
            messages.put(gmailMessageId, message);
        }
        return message;
    }

    @Override
    public synchronized String sendMessage(String recipientEmail, String subject, String body)
            throws AppException {
        requireConnected();
        String recipient = recipientEmail == null ? "" : recipientEmail.strip();
        String cleanSubject = subject == null ? "" : subject.strip();
        if (recipient.length() > 254 || !EMAIL_ADDRESS.matcher(recipient).matches()) {
            throw new AppException("Enter one valid recipient email address.");
        }
        if (cleanSubject.isEmpty() || cleanSubject.length() > 200) {
            throw new AppException("The subject must contain 1 to 200 characters.");
        }
        if (body == null || body.isBlank() || body.length() > 10_000) {
            throw new AppException("The message must contain 1 to 10,000 characters.");
        }
        String messageId = "demo-sent-" + nextSentId++;
        Set<String> labels = new LinkedHashSet<>();
        labels.add("SENT");
        if (recipient.equalsIgnoreCase(ACCOUNT.getEmailAddress())) {
            labels.add("INBOX");
        }
        EmailMessage message = new EmailMessage(ACCOUNT.getAccountId(), messageId,
                ACCOUNT.getEmailAddress(), List.of(recipient), cleanSubject, body,
                Instant.now(), Set.copyOf(labels));
        messages.put(messageId, message);
        spamResults.put(messageId, new SpamResult(0, false,
                List.of("Created locally in demo mode; no email was delivered.")));
        return messageId;
    }

    @Override
    public synchronized SpamResult getSpamResult(String gmailMessageId) throws AppException {
        requireMessage(gmailMessageId);
        return spamResults.get(gmailMessageId);
    }

    @Override
    public synchronized void markAsSpam(String gmailMessageId) throws AppException {
        EmailMessage message = requireReceivedMessage(gmailMessageId);
        Set<String> labels = new LinkedHashSet<>(message.getGmailLabelIds());
        labels.remove("INBOX");
        labels.add("SPAM");
        messages.put(gmailMessageId, withLabels(message, labels));
        spamOverrides.put(gmailMessageId, true);
        spamResults.put(gmailMessageId, correctionResult(true));
    }

    @Override
    public synchronized void markAsNotSpam(String gmailMessageId) throws AppException {
        EmailMessage message = requireReceivedMessage(gmailMessageId);
        Set<String> labels = new LinkedHashSet<>(message.getGmailLabelIds());
        labels.remove("SPAM");
        labels.add("INBOX");
        messages.put(gmailMessageId, withLabels(message, labels));
        spamOverrides.put(gmailMessageId, false);
        spamResults.put(gmailMessageId, correctionResult(false));
    }

    private void requireConnected() throws AppException {
        if (!connected) {
            throw new AppException("Connect the demo account before opening your mailbox.");
        }
    }

    private EmailMessage requireMessage(String messageId) throws AppException {
        requireConnected();
        EmailMessage message = messages.get(messageId);
        if (message == null || message.getAccountId() != ACCOUNT.getAccountId()) {
            throw new AppException("This message is unavailable in the connected account.");
        }
        return message;
    }

    private EmailMessage requireReceivedMessage(String messageId) throws AppException {
        EmailMessage message = requireMessage(messageId);
        if (message.getGmailLabelIds().contains("SENT")
                && !message.getGmailLabelIds().contains("INBOX")
                && !message.getGmailLabelIds().contains("SPAM")) {
            throw new AppException("Spam corrections apply to received messages.");
        }
        return message;
    }

    private boolean belongsToMailbox(EmailMessage message, Mailbox mailbox) {
        Set<String> labels = message.getGmailLabelIds();
        return switch (mailbox) {
            case INBOX -> labels.contains("INBOX") && !labels.contains("SPAM");
            case SENT -> labels.contains("SENT");
            case SPAM -> labels.contains("SPAM");
            case SUSPECTED -> !labels.contains("SPAM") && !labels.contains("SENT")
                    && spamResults.get(message.getGmailMessageId()).isSuspectedSpam();
        };
    }

    private boolean matchesSearch(EmailMessage message, String query) {
        if (query.isEmpty()) {
            return true;
        }
        String searchable = message.getSenderEmail() + "\n"
                + String.join("\n", message.getRecipientEmails()) + "\n"
                + message.getSubject() + "\n" + message.getBodyText();
        return searchable.toLowerCase(Locale.ROOT).contains(query);
    }

    private static EmailMessage withLabels(EmailMessage message, Set<String> labels) {
        return new EmailMessage(message.getAccountId(), message.getGmailMessageId(),
                message.getSenderEmail(), message.getRecipientEmails(), message.getSubject(),
                message.getBodyText(), message.getReceivedAt(), Set.copyOf(labels));
    }

    private static SpamResult correctionResult(boolean spam) {
        return new SpamResult(spam ? 100 : 0, spam,
                List.of(spam ? "You marked this message as spam."
                        : "You marked this message as not spam."));
    }

    private void seedMessages() {
        add("demo-meeting", "maya@campus.test", "Project catch-up at 4 PM",
                "Hi! Let's meet in the library at 4 PM to review the email management project.\n\n"
                        + "Please bring your screen sketches and the shared method contract.\n\nMaya",
                0, Set.of("INBOX", "UNREAD"), 0, false,
                "A familiar project update with no matching warning rules.");
        add("demo-build", "build@team.test", "The interface branch is ready to review",
                "The new mailbox screens are ready for review.\n\n"
                        + "Please check keyboard navigation, compose validation, and the empty state.\n\n"
                        + "This fictional notification is part of the demo mailbox.",
                45, Set.of("INBOX", "UNREAD"), 0, false,
                "No suspicious demo rules matched.");
        add("demo-invoice", "billing@invoice-review.test", "Action needed: verify your payment today",
                "Your payment is on hold. Verify your billing details immediately to avoid suspension.\n\n"
                        + "Open https://billing-check.example.test/verify and enter your account details.\n\n"
                        + "This is a fictional suspicious message for testing. Do not enter real details.",
                110, Set.of("INBOX", "UNREAD"), 78, true,
                "Urgent payment language.", "Requests sensitive billing details.",
                "Sender and link domains differ.");
        add("demo-library", "library@campus.test", "Your book reservation is available",
                "Your reserved database systems book is available at the circulation desk.\n\n"
                        + "Please collect it by Friday. Your library card is all you need.",
                180, Set.of("INBOX"), 0, false,
                "No suspicious demo rules matched.");
        add("demo-internship", "careers@campus.test", "Campus internship briefing — Thursday",
                "The careers team is holding a briefing on Thursday at 11 AM.\n\n"
                        + "We will cover application timelines and interview preparation. Attendance is optional.",
                260, Set.of("INBOX", "UNREAD"), 5, false,
                "An informational campus announcement.");
        add("demo-password", "security@account-alert.test", "Your account will close in 24 hours",
                "Confirm your password now or your mailbox will be closed.\n\n"
                        + "Reply with your password and verification code to keep access.\n\n"
                        + "Fictional phishing example: legitimate services should not request passwords by email.",
                370, Set.of("INBOX"), 94, true,
                "Requests a password and verification code.", "Threatens account closure.",
                "Uses urgency to pressure the recipient.");
        add("demo-newsletter", "newsletter@coding-club.test", "This week at the coding club",
                "This week's events:\n\nMonday: Java study group\nWednesday: SQL practice\n"
                        + "Friday: student project demonstrations\n\nSee you there!",
                500, Set.of("INBOX"), 0, false,
                "An ordinary fictional newsletter.");
        add("demo-prize", "offers@prize-draw.test", "You won! Claim a surprise reward",
                "You have been selected for a large cash reward. Pay a small processing fee to claim it today.\n\n"
                        + "This fictional spam example is already filed in the demo Gmail Spam folder.",
                600, Set.of("SPAM", "UNREAD"), 96, true,
                "Unsolicited prize claim.", "Requests an upfront processing fee.");
        add("demo-travel", "deals@travel-promo.test", "Last chance: exclusive holiday discount",
                "A limited offer is waiting. Book immediately to unlock your special discount.\n\n"
                        + "This fictional promotion is already in the demo Gmail Spam folder.",
                750, Set.of("SPAM", "UNREAD"), 62, true,
                "Promotional urgency.", "Unsolicited offer from an unfamiliar sender.");
        add("demo-welcome", "hello@maildesk.test", "Welcome to your demo mailbox",
                "Welcome! This is an in-memory preview of the email management interface.\n\n"
                        + "All messages are fictional. Sending saves a message in demo Sent only; "
                        + "no email leaves this application. No Google sign-in, database, or network is used.\n\n"
                        + "Try searching, composing, and reviewing the two suspected spam examples.",
                1_440, Set.of("INBOX"), 0, false,
                "A local demonstration message.");
        addSent("demo-sent-report", "maya@campus.test", "Project progress and next steps",
                "Hi Maya,\n\nThe member assignments and shared method signatures are ready. "
                        + "I will finish the Swing screens next.\n\nThanks!", 200);
        addSent("demo-sent-review", "dev@team.test", "Please review the compose screen",
                "The compose form now checks recipient, subject, and body before sending.\n\n"
                        + "Please try the keyboard shortcuts when you review it.", 1_600);
    }

    private void add(String id, String sender, String subject, String body, long minutesAgo,
                     Set<String> labels, int score, boolean suspected, String... reasons) {
        messages.put(id, new EmailMessage(ACCOUNT.getAccountId(), id, sender,
                List.of(ACCOUNT.getEmailAddress()), subject, body,
                SAMPLE_TIME.minus(minutesAgo, ChronoUnit.MINUTES), labels));
        spamResults.put(id, new SpamResult(score, suspected, List.of(reasons)));
    }

    private void addSent(String id, String recipient, String subject, String body, long minutesAgo) {
        messages.put(id, new EmailMessage(ACCOUNT.getAccountId(), id, ACCOUNT.getEmailAddress(),
                List.of(recipient), subject, body,
                SAMPLE_TIME.minus(minutesAgo, ChronoUnit.MINUTES), Set.of("SENT")));
        spamResults.put(id, new SpamResult(0, false,
                List.of("A fictional sent message in the demo mailbox.")));
    }
}
