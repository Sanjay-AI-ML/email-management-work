package ems.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable snapshot of a Gmail message. Gmail IDs are opaque strings.
 * Empty subjects and plain-text bodies are valid. Dates are instants; the UI
 * formats them in the user's local time zone.
 */
public final class EmailMessage {
    private final long accountId;
    private final String gmailMessageId;
    private final String senderEmail;
    private final List<String> recipientEmails;
    private final String subject;
    private final String bodyText;
    private final Instant receivedAt;
    private final Set<String> gmailLabelIds;

    public EmailMessage(
            long accountId,
            String gmailMessageId,
            String senderEmail,
            List<String> recipientEmails,
            String subject,
            String bodyText,
            Instant receivedAt,
            Set<String> gmailLabelIds) {
        this.accountId = accountId;
        this.gmailMessageId = Objects.requireNonNull(gmailMessageId, "gmailMessageId");
        this.senderEmail = Objects.requireNonNull(senderEmail, "senderEmail");
        this.recipientEmails = List.copyOf(recipientEmails);
        this.subject = Objects.requireNonNull(subject, "subject");
        this.bodyText = Objects.requireNonNull(bodyText, "bodyText");
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
        this.gmailLabelIds = Set.copyOf(gmailLabelIds);
    }

    public long getAccountId() {
        return accountId;
    }

    public String getGmailMessageId() {
        return gmailMessageId;
    }

    public String getSenderEmail() {
        return senderEmail;
    }

    public List<String> getRecipientEmails() {
        return recipientEmails;
    }

    public String getSubject() {
        return subject;
    }

    public String getBodyText() {
        return bodyText;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Set<String> getGmailLabelIds() {
        return gmailLabelIds;
    }
}
