package ems.storage;

import ems.model.Account;
import ems.model.EmailMessage;
import ems.model.Mailbox;
import ems.model.SpamResult;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Account-scoped local mailbox cache. Gmail is the source of mailbox truth. */
public interface MailStore {
    void initialize() throws SQLException;
    Account findOrCreateAccount(String email) throws SQLException;
    void saveMessages(long accountId, List<EmailMessage> messages) throws SQLException;
    void replaceSnapshot(long accountId, List<EmailMessage> messages) throws SQLException;
    List<EmailMessage> getMessages(long accountId, Mailbox mailbox, String query) throws SQLException;
    Optional<EmailMessage> findMessage(long accountId, String gmailMessageId) throws SQLException;
    SpamResult getSpamResult(long accountId, String gmailMessageId) throws SQLException;
    void saveFeedback(long accountId, String gmailMessageId, boolean spam) throws SQLException;
    void deleteMessages(long accountId, Collection<String> gmailMessageIds) throws SQLException;
}
