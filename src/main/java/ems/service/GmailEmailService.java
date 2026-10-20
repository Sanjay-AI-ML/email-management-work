package ems.service;

import ems.exception.AppException;
import ems.config.AppConfig;
import ems.gmail.GmailApiClient;
import ems.storage.JdbcMailStore;
import java.sql.DriverManager;
import ems.gmail.GmailGateway;
import ems.model.*;
import ems.storage.MailStore;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

/** Connects the Swing contract to Gmail and the persistent account-scoped cache. */
public final class GmailEmailService implements EmailService {
    private final GmailGateway gmail;
    private final MailStore store;
    private Account account;
    private String lastWarning = "";

    /** Shared production wiring for both team launchers; constructors stay injectable for tests. */
    public static GmailEmailService createDefault() throws AppException {
        AppConfig config = AppConfig.load();
        return new GmailEmailService(new GmailApiClient(config.getGmailCredentialsPath(),
                config.getTokenDirectory()), new JdbcMailStore(() -> DriverManager.getConnection(
                config.getDatabaseUrl(), config.getDatabaseUser(), config.getDatabasePassword())));
    }

    public GmailEmailService(GmailGateway gmail, MailStore store) {
        this.gmail = Objects.requireNonNull(gmail, "gmail");
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override public synchronized Account connectGmail() throws AppException {
        lastWarning = "";
        if (account != null) return account;
        try { store.initialize(); }
        catch (SQLException exception) {
            throw databaseError("Cannot connect to MySQL. Check db.url, db.user and db.password in config/app.properties.", exception);
        }
        String email = gmail.connect();
        try {
            account = store.findOrCreateAccount(email);
            return account;
        } catch (SQLException exception) {
            gmail.disconnect();
            throw databaseError("Google sign-in succeeded, but the local account could not be saved. Check MySQL and try again.", exception);
        }
    }

    @Override public synchronized void disconnect() {
        try { gmail.disconnect(); }
        finally { account = null; lastWarning = ""; }
    }

    @Override public synchronized void refresh() throws AppException {
        Account current = connected(); lastWarning = "";
        // A failed network fetch must never be mistaken for an empty mailbox.
        List<EmailMessage> snapshot = gmail.fetchMessages(current.getAccountId());
        verifyAccount(current, snapshot);
        try { store.replaceSnapshot(current.getAccountId(), snapshot); }
        catch (SQLException exception) {
            throw databaseError("Gmail was read, but the local mailbox could not be updated. Check MySQL and refresh again.", exception);
        }
    }

    @Override public synchronized List<EmailMessage> getMessages(Mailbox mailbox, String query) throws AppException {
        Account current = connected();
        if (mailbox == null) throw new AppException("Choose a mailbox first.");
        try { return store.getMessages(current.getAccountId(), mailbox, query == null ? "" : query.strip()); }
        catch (SQLException exception) { throw databaseError("Cannot read cached messages. Check MySQL and try again.", exception); }
    }

    @Override public synchronized EmailMessage openMessage(String gmailMessageId) throws AppException {
        Account current = connected(); lastWarning = "";
        requireCached(current, gmailMessageId);
        EmailMessage message = gmail.getMessage(current.getAccountId(), gmailMessageId);
        verifyAccount(current, List.of(message));
        if (message.getGmailLabelIds().contains("UNREAD")) {
            gmail.setRead(gmailMessageId);
            Set<String> labels = new HashSet<>(message.getGmailLabelIds()); labels.remove("UNREAD");
            message = withLabels(message, labels);
        }
        try { store.saveMessages(current.getAccountId(), List.of(message)); }
        catch (SQLException exception) {
            throw databaseError("The message was opened in Gmail, but its local copy could not be updated. Refresh after checking MySQL.", exception);
        }
        return message;
    }

    @Override public synchronized String sendMessage(String recipientEmail, String subject, String body) throws AppException {
        Account current = connected(); lastWarning = "";
        String recipient = validateRecipient(recipientEmail);
        String cleanSubject = subject == null ? "" : subject.strip();
        if (cleanSubject.isBlank() || cleanSubject.length() > 200 || containsLineBreak(subject)) {
            throw new AppException("Enter a subject between 1 and 200 characters on one line.");
        }
        if (body == null || body.isBlank() || body.length() > 10_000) {
            throw new AppException("Enter a message between 1 and 10,000 characters.");
        }
        String id = gmail.sendMessage(current.getEmailAddress(), recipient, cleanSubject, body);
        // Gmail has accepted this email. Never turn a later cache error into 'send failed'.
        EmailMessage sent = new EmailMessage(current.getAccountId(), id, current.getEmailAddress(),
                List.of(recipient), cleanSubject, body, Instant.now(),
                recipient.equalsIgnoreCase(current.getEmailAddress()) ? Set.of("SENT", "INBOX") : Set.of("SENT"));
        try { sent = gmail.getMessage(current.getAccountId(), id); verifyAccount(current, List.of(sent)); }
        catch (AppException exception) {
            lastWarning = "Email was sent. Its latest Gmail copy could not be loaded; refresh Sent before sending it again.";
        }
        try { store.saveMessages(current.getAccountId(), List.of(sent)); }
        catch (SQLException exception) {
            lastWarning = "Email was sent, but its local copy could not be saved. Check Gmail Sent before sending it again.";
        }
        return id;
    }

    @Override public synchronized SpamResult getSpamResult(String gmailMessageId) throws AppException {
        Account current = connected(); requireCached(current, gmailMessageId);
        try { return store.getSpamResult(current.getAccountId(), gmailMessageId); }
        catch (SQLException exception) { throw databaseError("Cannot read this message's spam review. Check MySQL and try again.", exception); }
    }

    @Override public synchronized void markAsSpam(String gmailMessageId) throws AppException { correct(gmailMessageId, true); }
    @Override public synchronized void markAsNotSpam(String gmailMessageId) throws AppException { correct(gmailMessageId, false); }

    private void correct(String id, boolean spam) throws AppException {
        Account current = connected(); lastWarning = "";
        EmailMessage original = requireCached(current, id);
        if (original.getGmailLabelIds().contains("SENT") && !original.getGmailLabelIds().contains("INBOX")
                && !original.getGmailLabelIds().contains("SPAM")) {
            throw new AppException("Spam corrections apply to received messages.");
        }
        gmail.setSpam(id, spam);
        Set<String> labels = new HashSet<>(original.getGmailLabelIds());
        if (spam) { labels.add("SPAM"); labels.remove("INBOX"); }
        else { labels.remove("SPAM"); labels.add("INBOX"); }
        EmailMessage updated = withLabels(original, labels);
        try { updated = gmail.getMessage(current.getAccountId(), id); verifyAccount(current, List.of(updated)); }
        catch (AppException exception) {
            lastWarning = "Gmail was updated. Refresh to load its latest message details.";
        }
        try {
            store.saveMessages(current.getAccountId(), List.of(updated));
            store.saveFeedback(current.getAccountId(), id, spam);
        } catch (SQLException exception) {
            throw databaseError("Gmail's spam label was updated, but the local correction could not be saved. Check MySQL and refresh.", exception);
        }
    }

    @Override public synchronized String getLastWarning() { return lastWarning; }

    private Account connected() throws AppException {
        if (account == null) throw new AppException("Connect your Gmail account first.");
        return account;
    }

    private EmailMessage requireCached(Account current, String id) throws AppException {
        if (id == null || id.isBlank()) throw new AppException("Choose an available message first.");
        try {
            return store.findMessage(current.getAccountId(), id)
                    .orElseThrow(() -> new AppException("This message is unavailable in the connected account. Refresh your mailbox."));
        } catch (SQLException exception) { throw databaseError("Cannot access this message. Check MySQL and try again.", exception); }
    }

    private static void verifyAccount(Account current, List<EmailMessage> messages) throws AppException {
        if (messages == null || messages.stream().anyMatch(message -> message == null
                || message.getAccountId() != current.getAccountId())) {
            throw new AppException("The mailbox response did not match the connected account. Disconnect and sign in again.");
        }
    }

    private static String validateRecipient(String input) throws AppException {
        if (input == null || input.isBlank() || input.length() > 254 || containsLineBreak(input)) {
            throw new AppException("Enter one valid recipient email address.");
        }
        try {
            InternetAddress[] addresses = InternetAddress.parse(input.strip(), true);
            if (addresses.length != 1 || addresses[0].isGroup()) throw new AddressException();
            addresses[0].validate();
            String address = addresses[0].getAddress();
            if (!address.contains("@")) throw new AddressException();
            return address;
        } catch (AddressException exception) { throw new AppException("Enter one valid recipient email address."); }
    }

    private static boolean containsLineBreak(String value) { return value != null && (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0); }
    private static EmailMessage withLabels(EmailMessage message, Set<String> labels) {
        return new EmailMessage(message.getAccountId(), message.getGmailMessageId(), message.getSenderEmail(),
                message.getRecipientEmails(), message.getSubject(), message.getBodyText(), message.getReceivedAt(), labels);
    }
    private static AppException databaseError(String message, SQLException cause) { return new AppException(message, cause); }
}
