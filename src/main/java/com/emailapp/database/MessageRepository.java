package com.emailapp.database;

import ems.model.EmailMessage;
import ems.storage.JdbcMailStore;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

/** Member 3 compatibility facade; use MailStore for application operations. */
public final class MessageRepository {
    private final JdbcMailStore store;
    public MessageRepository() { this(new JdbcMailStore()); }
    public MessageRepository(JdbcMailStore store) { this.store = store; }
    public void save(EmailMessage message) throws SQLException {
        store.saveMessages(message.getAccountId(), List.of(message));
    }
    /** Saving the full snapshot keeps recipients and labels consistent atomically. */
    public void saveRecipients(EmailMessage message) throws SQLException { save(message); }
    public void saveLabels(EmailMessage message) throws SQLException { save(message); }
    public List<EmailMessage> findByAccount(long accountId) throws SQLException { return store.findAll(accountId); }
    public List<String> findRecipients(long accountId, String messageId) throws SQLException {
        return store.findMessage(accountId, messageId).map(EmailMessage::getRecipientEmails).orElse(List.of());
    }
    public Set<String> findLabels(long accountId, String messageId) throws SQLException {
        return store.findMessage(accountId, messageId).map(EmailMessage::getGmailLabelIds).orElse(Set.of());
    }
    public void updateReadStatus(long accountId, String messageId, boolean read) throws SQLException {
        store.updateReadStatus(accountId, messageId, read);
    }
}
