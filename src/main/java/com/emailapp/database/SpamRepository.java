package com.emailapp.database;

import ems.model.EmailMessage;
import ems.model.SpamResult;
import ems.storage.JdbcMailStore;
import java.sql.SQLException;

/** Compatibility facade for Member 3's original repository API. */
public final class SpamRepository {
    private final JdbcMailStore store;
    public SpamRepository() { this(new JdbcMailStore()); }
    public SpamRepository(JdbcMailStore store) { this.store = store; }
    public void save(EmailMessage message, SpamResult result) throws SQLException {
        store.saveSpamResult(message.getAccountId(), message.getGmailMessageId(), result);
    }
    public SpamResult findByMessage(long accountId, String messageId) throws SQLException {
        if (store.findMessage(accountId, messageId).isEmpty()) return null;
        return store.getSpamResult(accountId, messageId);
    }
}
