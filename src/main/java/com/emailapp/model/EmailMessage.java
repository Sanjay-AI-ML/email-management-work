
package com.emailapp.model;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public class EmailMessage {

    private long accountId;
    private String gmailMessageId;
    private String senderEmail;
    private List<String> recipientEmails;
    private String subject;
    private String bodyText;
    private Instant receivedAt;
    private Set<String> gmailLabelIds;

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
        this.gmailMessageId = gmailMessageId;
        this.senderEmail = senderEmail;
        this.recipientEmails = recipientEmails;
        this.subject = subject;
        this.bodyText = bodyText;
        this.receivedAt = receivedAt;
        this.gmailLabelIds = gmailLabelIds;
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
