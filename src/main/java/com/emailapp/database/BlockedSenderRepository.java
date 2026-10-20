package com.emailapp.database;

import ems.storage.JdbcMailStore;
import java.sql.SQLException;

/** Account-scoped sender blocks used by the local classifier. */
public final class BlockedSenderRepository {
    private final JdbcMailStore store;
    public BlockedSenderRepository() { this(new JdbcMailStore()); }
    public BlockedSenderRepository(JdbcMailStore store) { this.store = store; }
    public boolean isBlocked(long accountId, String senderEmail) throws SQLException {
        return store.isSenderBlocked(accountId, senderEmail);
    }
    public void block(long accountId, String senderEmail) throws SQLException {
        store.setSenderBlocked(accountId, senderEmail, true);
    }
    public void unblock(long accountId, String senderEmail) throws SQLException {
        store.setSenderBlocked(accountId, senderEmail, false);
    }
}
