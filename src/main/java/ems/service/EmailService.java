package ems.service;

import ems.exception.AppException;
import ems.model.Account;
import ems.model.EmailMessage;
import ems.model.Mailbox;
import ems.model.SpamResult;
import java.util.List;

/**
 * Shared boundary between the Swing interface and Gmail/database integrations.
 * Implementations support one connected account at a time and scope every
 * mailbox operation to that account. Operations can block, so the UI calls them
 * on background workers rather than the Swing event-dispatch thread.
 *
 * <p>Implementations should turn recoverable provider/database errors into
 * {@link AppException} with useful, non-sensitive messages. A failed operation
 * must not be presented to the UI as a successful update.
 */
public interface EmailService {
    /** Authorizes Gmail access through Google and returns the connected account. */
    Account connectGmail() throws AppException;

    /** Ends the application's local connected session; does not delete the Gmail account. */
    void disconnect();

    /** Fetches Gmail changes, updates the local cache, and runs local spam analysis. */
    void refresh() throws AppException;
    default void refreshRecent() throws AppException { refresh(); }

    /**
     * Returns visible cached messages, newest first, for the selected mailbox.
     * An empty query returns all messages in that view; searches match subject,
     * body, sender, or recipient addresses. Returns an empty list if none match.
     */
    List<EmailMessage> getMessages(Mailbox mailbox, String query) throws AppException;

    /**
     * Opens an accessible message for the connected account and marks it read
     * in Gmail. Returns the updated snapshot after a successful operation.
     */
    EmailMessage openMessage(String gmailMessageId) throws AppException;

    /** Sends to one real recipient address and returns Gmail's new message ID. */
    String sendMessage(String recipientEmail, String subject, String body) throws AppException;

    /** Returns the local detector's result for a message in the connected account. */
    SpamResult getSpamResult(String gmailMessageId) throws AppException;

    /** Applies Gmail's spam classification and persists the user's correction locally. */
    void markAsSpam(String gmailMessageId) throws AppException;

    /** Removes Gmail's spam classification and persists the user's correction locally. */
    void markAsNotSpam(String gmailMessageId) throws AppException;

    /** Non-sensitive notice for a successful operation with a recoverable cache issue. */
    default String getLastWarning() { return ""; }
}
