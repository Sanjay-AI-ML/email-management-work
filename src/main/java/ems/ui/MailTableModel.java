package ems.ui;

import ems.model.EmailMessage;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.swing.table.AbstractTableModel;

/** Table keeps message IDs with their rows; the UI never identifies mail by subject. */
public final class MailTableModel extends AbstractTableModel {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM, HH:mm")
            .withZone(ZoneId.systemDefault());
    private List<EmailMessage> messages = List.of();
    private boolean sent;

    public void setMessages(List<EmailMessage> messages) {
        this.messages = List.copyOf(messages);
        fireTableDataChanged();
    }

    public void setSent(boolean sent) {
        this.sent = sent;
        fireTableStructureChanged();
    }

    public EmailMessage getMessageAt(int row) { return messages.get(row); }
    public boolean isUnread(int row) {
        return messages.get(row).getGmailLabelIds().contains("UNREAD");
    }
    @Override public int getRowCount() { return messages.size(); }
    @Override public int getColumnCount() { return 3; }
    @Override public String getColumnName(int column) {
        return switch (column) {
            case 0 -> sent ? "TO" : "FROM";
            case 1 -> "SUBJECT";
            default -> sent ? "SENT AT" : "RECEIVED";
        };
    }
    @Override public Object getValueAt(int row, int column) {
        EmailMessage message = messages.get(row);
        return switch (column) {
            case 0 -> sent ? String.join(", ", message.getRecipientEmails()) : message.getSenderEmail();
            case 1 -> message.getSubject().isBlank() ? "(No subject)" : message.getSubject();
            default -> DATE.format(message.getReceivedAt());
        };
    }
}
