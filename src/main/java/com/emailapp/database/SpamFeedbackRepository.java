package com.emailapp.database;

import ems.storage.JdbcMailStore;
import java.sql.SQLException;

/** User corrections take precedence over automatic results across refreshes. */
public final class SpamFeedbackRepository {
    private final JdbcMailStore store;
    public SpamFeedbackRepository() { this(new JdbcMailStore()); }
    public SpamFeedbackRepository(JdbcMailStore store) { this.store = store; }
    public void saveFeedback(long accountId, String messageId, boolean spam) throws SQLException {
        store.saveFeedback(accountId, messageId, spam);
    }
}
