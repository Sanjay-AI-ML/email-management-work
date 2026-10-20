package ems.service;

import ems.exception.AppException;
import ems.gmail.GmailApiClient;
import ems.gmail.GmailAuth;
import ems.model.Account;
import ems.model.EmailMessage;
import ems.model.Mailbox;
import ems.model.SpamResult;

import com.emailapp.database.DatabaseConnection;
import com.emailapp.database.MessageRepository;
import com.emailapp.database.SpamFeedbackRepository;
import com.emailapp.database.SpamRepository;
import com.emailapp.spam.SpamClassifier;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Production implementation of {@link EmailService} connecting Gmail API,
 * Member 3's MySQL database, and the local spam classifier.
 */
public class GmailEmailService implements EmailService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@,;]+@[^\\s@,;]+\\.[^\\s@,;]+$");

    private final GmailAuth gmailAuth;
    private final GmailApiClient gmailApiClient;
    private final MessageRepository messageRepository;
    private final SpamRepository spamRepository;
    private final SpamFeedbackRepository spamFeedbackRepository;
    private final SpamClassifier spamClassifier;

    private Account currentAccount;
    private final Map<String, EmailMessage> cachedMessages = new LinkedHashMap<>();
    private final Map<String, SpamResult> cachedSpamResults = new LinkedHashMap<>();
    private final Map<String, Boolean> userSpamOverrides = new LinkedHashMap<>();

    public GmailEmailService() {
        this.gmailAuth = new GmailAuth();
        this.gmailApiClient = new GmailApiClient();
        this.messageRepository = new MessageRepository();
        this.spamRepository = new SpamRepository();
        this.spamFeedbackRepository = new SpamFeedbackRepository();
        this.spamClassifier = new SpamClassifier();
    }

    // Constructor allowing custom auth/client injection for testing
    public GmailEmailService(GmailAuth auth, GmailApiClient client) {
        this.gmailAuth = auth;
        this.gmailApiClient = client;
        this.messageRepository = new MessageRepository();
        this.spamRepository = new SpamRepository();
        this.spamFeedbackRepository = new SpamFeedbackRepository();
        this.spamClassifier = new SpamClassifier();
    }

    @Override
    public synchronized Account connectGmail() throws AppException {
        String token = gmailAuth.getValidAccessToken();
        String email = gmailApiClient.getUserEmailAddress(token);

        long accountId = resolveAccountId(email);
        this.currentAccount = new Account(accountId, email);

        // Load existing cached messages from MySQL or memory
        loadDatabaseCache();

        // Perform initial refresh
        try {
            refresh();
        } catch (Exception e) {
            System.err.println("Notice: Initial Gmail sync encountered: " + e.getMessage());
        }

        return this.currentAccount;
    }

    @Override
    public synchronized void disconnect() {
        this.currentAccount = null;
        this.cachedMessages.clear();
        this.cachedSpamResults.clear();
        this.userSpamOverrides.clear();
        this.gmailAuth.clearTokens();
    }

    @Override
    public synchronized void refresh() throws AppException {
        requireConnected();
        String token = gmailAuth.getValidAccessToken();

        try {
            List<String> messageIds = gmailApiClient.listMessageIds(token, 40);
            for (String messageId : messageIds) {
                EmailMessage emsMessage = gmailApiClient.fetchMessage(token, currentAccount.getAccountId(), messageId);
                cachedMessages.put(messageId, emsMessage);

                // Convert to Member 3 model for database persistence & spam analysis
                com.emailapp.model.EmailMessage comMessage = toComMessage(emsMessage);

                // Save to MySQL database if available
                saveToDatabase(comMessage);

                // Run Member 3 spam classifier
                com.emailapp.model.SpamResult comSpam = spamClassifier.analyze(comMessage);
                SpamResult emsSpam = toEmsSpam(comSpam);

                // Check if user previously marked this message as spam/not spam
                Boolean userOverride = userSpamOverrides.get(messageId);
                if (userOverride != null) {
                    emsSpam = buildOverrideSpamResult(userOverride);
                }

                cachedSpamResults.put(messageId, emsSpam);
                saveSpamToDatabase(comMessage, toComSpam(emsSpam));
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException("Failed to refresh Gmail messages: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized List<EmailMessage> getMessages(Mailbox mailbox, String query) throws AppException {
        requireConnected();
        if (mailbox == null) {
            throw new AppException("Choose a mailbox first.");
        }

        String search = (query == null) ? "" : query.strip().toLowerCase(Locale.ROOT);
        List<EmailMessage> matched = new ArrayList<>();

        for (EmailMessage message : cachedMessages.values()) {
            if (matchesMailbox(message, mailbox) && matchesQuery(message, search)) {
                matched.add(message);
            }
        }

        // Sort newest first
        matched.sort(Comparator.comparing(EmailMessage::getReceivedAt).reversed()
                .thenComparing(EmailMessage::getGmailMessageId));

        return List.copyOf(matched);
    }

    @Override
    public synchronized EmailMessage openMessage(String gmailMessageId) throws AppException {
        requireConnected();
        EmailMessage existing = cachedMessages.get(gmailMessageId);
        if (existing == null) {
            throw new AppException("Message not found: " + gmailMessageId);
        }

        // If message has UNREAD label, remove it via Gmail API
        if (existing.getGmailLabelIds().contains("UNREAD")) {
            String token = gmailAuth.getValidAccessToken();
            try {
                gmailApiClient.markAsRead(token, gmailMessageId);
            } catch (Exception e) {
                System.err.println("Could not update read status in Gmail: " + e.getMessage());
            }

            // Update in MySQL database
            try {
                messageRepository.updateReadStatus(currentAccount.getAccountId(), gmailMessageId, true);
            } catch (Exception ignored) {
            }

            // Update in-memory snapshot
            Set<String> updatedLabels = new HashSet<>(existing.getGmailLabelIds());
            updatedLabels.remove("UNREAD");

            EmailMessage updated = new EmailMessage(
                    existing.getAccountId(),
                    existing.getGmailMessageId(),
                    existing.getSenderEmail(),
                    existing.getRecipientEmails(),
                    existing.getSubject(),
                    existing.getBodyText(),
                    existing.getReceivedAt(),
                    updatedLabels
            );
            cachedMessages.put(gmailMessageId, updated);
            return updated;
        }

        return existing;
    }

    @Override
    public synchronized String sendMessage(String recipientEmail, String subject, String body) throws AppException {
        requireConnected();

        if (recipientEmail == null || !EMAIL_PATTERN.matcher(recipientEmail.trim()).matches()) {
            throw new AppException("Enter a valid recipient email address.");
        }
        if (subject == null || subject.isBlank()) {
            throw new AppException("Email subject cannot be empty.");
        }
        if (body == null || body.isBlank()) {
            throw new AppException("Email body cannot be empty.");
        }

        String token = gmailAuth.getValidAccessToken();
        String sentId = gmailApiClient.sendMessage(token, currentAccount.getEmailAddress(), recipientEmail.trim(), subject, body);

        // Record sent message locally
        EmailMessage sentMsg = new EmailMessage(
                currentAccount.getAccountId(),
                sentId,
                currentAccount.getEmailAddress(),
                List.of(recipientEmail.trim()),
                subject,
                body,
                Instant.now(),
                Set.of("SENT")
        );
        cachedMessages.put(sentId, sentMsg);
        saveToDatabase(toComMessage(sentMsg));

        return sentId;
    }

    @Override
    public synchronized SpamResult getSpamResult(String gmailMessageId) throws AppException {
        requireConnected();

        Boolean override = userSpamOverrides.get(gmailMessageId);
        if (override != null) {
            return buildOverrideSpamResult(override);
        }

        SpamResult cached = cachedSpamResults.get(gmailMessageId);
        if (cached != null) {
            return cached;
        }

        // Try database lookup
        try {
            com.emailapp.model.SpamResult dbSpam = spamRepository.findByMessage(currentAccount.getAccountId(), gmailMessageId);
            if (dbSpam != null) {
                SpamResult emsSpam = toEmsSpam(dbSpam);
                cachedSpamResults.put(gmailMessageId, emsSpam);
                return emsSpam;
            }
        } catch (Exception ignored) {
        }

        // Fallback: analyze current message
        EmailMessage msg = cachedMessages.get(gmailMessageId);
        if (msg != null) {
            com.emailapp.model.SpamResult result = spamClassifier.analyze(toComMessage(msg));
            SpamResult emsResult = toEmsSpam(result);
            cachedSpamResults.put(gmailMessageId, emsResult);
            return emsResult;
        }

        return new SpamResult(0, false, List.of());
    }

    @Override
    public synchronized void markAsSpam(String gmailMessageId) throws AppException {
        requireConnected();
        String token = gmailAuth.getValidAccessToken();

        // 1. Update Gmail labels
        try {
            gmailApiClient.markAsSpam(token, gmailMessageId);
        } catch (Exception e) {
            System.err.println("Could not update spam label on Gmail server: " + e.getMessage());
        }

        // 2. Persist feedback locally in MySQL
        try {
            spamFeedbackRepository.saveFeedback(currentAccount.getAccountId(), gmailMessageId, true);
        } catch (Exception ignored) {
        }

        // 3. Update memory state
        userSpamOverrides.put(gmailMessageId, Boolean.TRUE);
        cachedSpamResults.put(gmailMessageId, buildOverrideSpamResult(true));

        EmailMessage existing = cachedMessages.get(gmailMessageId);
        if (existing != null) {
            Set<String> newLabels = new HashSet<>(existing.getGmailLabelIds());
            newLabels.add("SPAM");
            newLabels.remove("INBOX");
            cachedMessages.put(gmailMessageId, new EmailMessage(
                    existing.getAccountId(), existing.getGmailMessageId(), existing.getSenderEmail(),
                    existing.getRecipientEmails(), existing.getSubject(), existing.getBodyText(),
                    existing.getReceivedAt(), newLabels
            ));
        }
    }

    @Override
    public synchronized void markAsNotSpam(String gmailMessageId) throws AppException {
        requireConnected();
        String token = gmailAuth.getValidAccessToken();

        // 1. Update Gmail labels
        try {
            gmailApiClient.markAsNotSpam(token, gmailMessageId);
        } catch (Exception e) {
            System.err.println("Could not remove spam label on Gmail server: " + e.getMessage());
        }

        // 2. Persist feedback locally in MySQL
        try {
            spamFeedbackRepository.saveFeedback(currentAccount.getAccountId(), gmailMessageId, false);
        } catch (Exception ignored) {
        }

        // 3. Update memory state
        userSpamOverrides.put(gmailMessageId, Boolean.FALSE);
        cachedSpamResults.put(gmailMessageId, buildOverrideSpamResult(false));

        EmailMessage existing = cachedMessages.get(gmailMessageId);
        if (existing != null) {
            Set<String> newLabels = new HashSet<>(existing.getGmailLabelIds());
            newLabels.remove("SPAM");
            newLabels.add("INBOX");
            cachedMessages.put(gmailMessageId, new EmailMessage(
                    existing.getAccountId(), existing.getGmailMessageId(), existing.getSenderEmail(),
                    existing.getRecipientEmails(), existing.getSubject(), existing.getBodyText(),
                    existing.getReceivedAt(), newLabels
            ));
        }
    }

    private void requireConnected() throws AppException {
        if (currentAccount == null) {
            throw new AppException("Connect to Gmail first.");
        }
    }

    private boolean matchesMailbox(EmailMessage message, Mailbox mailbox) {
        Set<String> labels = message.getGmailLabelIds();
        SpamResult spam = cachedSpamResults.get(message.getGmailMessageId());
        boolean isSuspected = (spam != null && spam.isSuspectedSpam());

        return switch (mailbox) {
            case INBOX -> labels.contains("INBOX") && !labels.contains("SPAM");
            case SENT -> labels.contains("SENT");
            case SPAM -> labels.contains("SPAM");
            case SUSPECTED -> isSuspected && !labels.contains("SPAM");
        };
    }

    private boolean matchesQuery(EmailMessage message, String query) {
        if (query.isBlank()) return true;
        if (message.getSubject().toLowerCase(Locale.ROOT).contains(query)) return true;
        if (message.getBodyText().toLowerCase(Locale.ROOT).contains(query)) return true;
        if (message.getSenderEmail().toLowerCase(Locale.ROOT).contains(query)) return true;
        for (String r : message.getRecipientEmails()) {
            if (r.toLowerCase(Locale.ROOT).contains(query)) return true;
        }
        return false;
    }

    private SpamResult buildOverrideSpamResult(boolean userMarkedSpam) {
        if (userMarkedSpam) {
            return new SpamResult(100, true, List.of("User marked as spam"));
        } else {
            return new SpamResult(0, false, List.of("User marked as not spam"));
        }
    }

    private void saveToDatabase(com.emailapp.model.EmailMessage comMessage) {
        try {
            messageRepository.save(comMessage);
            messageRepository.saveRecipients(comMessage);
            messageRepository.saveLabels(comMessage);
        } catch (Exception e) {
            // DB error is non-fatal for UI operation
            System.err.println("Database save warning: " + e.getMessage());
        }
    }

    private void saveSpamToDatabase(com.emailapp.model.EmailMessage comMessage, com.emailapp.model.SpamResult spamResult) {
        try {
            spamRepository.save(comMessage, spamResult);
        } catch (Exception e) {
            System.err.println("Database spam save warning: " + e.getMessage());
        }
    }

    private void loadDatabaseCache() {
        if (currentAccount == null) return;
        try {
            List<com.emailapp.model.EmailMessage> dbList = messageRepository.findByAccount(currentAccount.getAccountId());
            for (com.emailapp.model.EmailMessage comMsg : dbList) {
                EmailMessage emsMsg = toEmsMessage(comMsg);
                cachedMessages.put(emsMsg.getGmailMessageId(), emsMsg);

                com.emailapp.model.SpamResult s = spamRepository.findByMessage(currentAccount.getAccountId(), emsMsg.getGmailMessageId());
                if (s != null) {
                    cachedSpamResults.put(emsMsg.getGmailMessageId(), toEmsSpam(s));
                }
            }
        } catch (Exception e) {
            System.err.println("Note: Could not preload from MySQL: " + e.getMessage());
        }
    }

    private long resolveAccountId(String email) {
        // Query or insert into MySQL accounts table
        try (Connection conn = DatabaseConnection.getConnection()) {
            String selectSql = "SELECT account_id FROM accounts WHERE email_address = ?";
            try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                ps.setString(1, email);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getLong("account_id");
                    }
                }
            }

            String insertSql = "INSERT INTO accounts (email_address) VALUES (?)";
            try (PreparedStatement ps = conn.prepareStatement(insertSql, java.sql.Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, email);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        return keys.getLong(1);
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // Fallback stable positive hash code
        return Math.abs((long) email.hashCode()) + 1L;
    }

    private static com.emailapp.model.EmailMessage toComMessage(EmailMessage ems) {
        return new com.emailapp.model.EmailMessage(
                ems.getAccountId(),
                ems.getGmailMessageId(),
                ems.getSenderEmail(),
                ems.getRecipientEmails(),
                ems.getSubject(),
                ems.getBodyText(),
                ems.getReceivedAt(),
                ems.getGmailLabelIds()
        );
    }

    private static EmailMessage toEmsMessage(com.emailapp.model.EmailMessage com) {
        return new EmailMessage(
                com.getAccountId(),
                com.getGmailMessageId(),
                com.getSenderEmail(),
                com.getRecipientEmails(),
                com.getSubject(),
                com.getBodyText(),
                com.getReceivedAt(),
                com.getGmailLabelIds()
        );
    }

    private static SpamResult toEmsSpam(com.emailapp.model.SpamResult com) {
        return new SpamResult(com.getScore(), com.isSuspectedSpam(), com.getReasons());
    }

    private static com.emailapp.model.SpamResult toComSpam(SpamResult ems) {
        return new com.emailapp.model.SpamResult(ems.getScore(), ems.isSuspectedSpam(), ems.getReasons());
    }
}
