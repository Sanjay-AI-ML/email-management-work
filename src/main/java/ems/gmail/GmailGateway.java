package ems.gmail;

import ems.exception.AppException;
import ems.model.EmailMessage;
import java.util.List;

/** Remote Gmail operations. Implementations must not expose tokens in errors. */
public interface GmailGateway {
    String connect() throws AppException;
    List<EmailMessage> fetchMessages(long accountId) throws AppException;
    EmailMessage getMessage(long accountId, String gmailMessageId) throws AppException;
    String sendMessage(String senderEmail, String recipientEmail, String subject, String body)
            throws AppException;
    void setRead(String gmailMessageId) throws AppException;
    void setSpam(String gmailMessageId, boolean spam) throws AppException;
    void disconnect();
}
