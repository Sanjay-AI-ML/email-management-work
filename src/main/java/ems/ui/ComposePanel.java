package ems.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;

/**
 * Reusable compose form. Validation stays local; the owner performs sending
 * through its service on a background worker and reports failures with showError.
 */
public final class ComposePanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final Color ERROR = new Color(162, 51, 43);
    private static final Pattern ADDRESS = Pattern.compile(
            "^[^\\s@,;<>]+@[^\\s@,;<>.]+(?:\\.[^\\s@,;<>.]+)+$");

    /** Validated input, ready for the owner to submit through EmailService. */
    public static record Draft(String recipientEmail, String subject, String body) { }

    private final JTextField toField;
    private final JTextField subjectField;
    private final JTextArea bodyArea;
    private final JButton sendButton;
    private final JButton cancelButton;
    private final JLabel errorLabel;
    private final Consumer<Draft> onSend;
    private final Runnable onCancel;
    private boolean busy;

    public ComposePanel(
            String senderEmail,
            boolean demoMode,
            Consumer<Draft> onSend,
            Runnable onCancel) {
        this.onSend = Objects.requireNonNull(onSend, "onSend");
        this.onCancel = Objects.requireNonNull(onCancel, "onCancel");
        Objects.requireNonNull(senderEmail, "senderEmail");

        setName("composePanel");
        setLayout(new BorderLayout(0, 18));
        setBackground(UiTheme.SURFACE);
        setBorder(BorderFactory.createEmptyBorder(24, 28, 24, 28));
        setPreferredSize(new Dimension(660, 560));

        JPanel heading = column();
        heading.add(UiTheme.label("New message", Font.BOLD, 24, UiTheme.TEXT));
        heading.add(Box.createVerticalStrut(6));
        JLabel from = UiTheme.label("From: " + senderEmail, Font.PLAIN, 13, UiTheme.MUTED);
        from.putClientProperty("html.disable", Boolean.TRUE);
        heading.add(from);
        if (demoMode) {
            heading.add(Box.createVerticalStrut(12));
            JLabel notice = UiTheme.label(
                    "DEMO MODE  ·  Messages stay in the demo. Nothing is sent through Gmail.",
                    Font.PLAIN, 12, UiTheme.ACCENT);
            notice.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
            notice.setOpaque(true);
            notice.setBackground(UiTheme.BACKGROUND);
            notice.setAlignmentX(LEFT_ALIGNMENT);
            heading.add(notice);
        }
        add(heading, BorderLayout.NORTH);

        JPanel fields = new JPanel(new BorderLayout(0, 14));
        fields.setOpaque(false);
        JPanel shortFields = column();
        toField = UiTheme.field("recipient@example.com");
        toField.setName("composeTo");
        toField.getAccessibleContext().setAccessibleName("Recipient email address");
        toField.getAccessibleContext().setAccessibleDescription("Enter one recipient email address.");
        addField(shortFields, "To", toField, KeyEvent.VK_T);
        shortFields.add(Box.createVerticalStrut(12));

        subjectField = UiTheme.field("Add a subject");
        subjectField.setName("composeSubject");
        subjectField.getAccessibleContext().setAccessibleName("Subject");
        subjectField.getAccessibleContext().setAccessibleDescription("Required, up to 200 characters.");
        addField(shortFields, "Subject", subjectField, KeyEvent.VK_S);
        fields.add(shortFields, BorderLayout.NORTH);

        JPanel bodyPanel = new JPanel(new BorderLayout(0, 7));
        bodyPanel.setOpaque(false);
        bodyArea = new JTextArea();
        bodyArea.setName("composeBody");
        bodyArea.setFont(UiTheme.font(Font.PLAIN, 14));
        bodyArea.setForeground(UiTheme.TEXT);
        bodyArea.setBackground(UiTheme.SURFACE);
        bodyArea.setCaretColor(UiTheme.ACCENT);
        bodyArea.setLineWrap(true);
        bodyArea.setWrapStyleWord(true);
        bodyArea.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        bodyArea.getAccessibleContext().setAccessibleName("Message body");
        bodyArea.getAccessibleContext().setAccessibleDescription("Required, up to 10,000 characters.");
        JLabel bodyLabel = UiTheme.label("Message", Font.BOLD, 13, UiTheme.TEXT);
        bodyLabel.setLabelFor(bodyArea);
        bodyLabel.setDisplayedMnemonic(KeyEvent.VK_M);
        bodyPanel.add(bodyLabel, BorderLayout.NORTH);
        JScrollPane bodyScroll = new JScrollPane(bodyArea);
        bodyScroll.setBorder(BorderFactory.createLineBorder(UiTheme.BORDER));
        bodyScroll.setPreferredSize(new Dimension(560, 180));
        bodyPanel.add(bodyScroll, BorderLayout.CENTER);
        fields.add(bodyPanel, BorderLayout.CENTER);
        add(fields, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(0, 10));
        footer.setOpaque(false);
        errorLabel = UiTheme.label(" ", Font.PLAIN, 12, ERROR);
        errorLabel.setName("composeError");
        errorLabel.putClientProperty("html.disable", Boolean.TRUE);
        errorLabel.getAccessibleContext().setAccessibleName("Compose status");
        footer.add(errorLabel, BorderLayout.NORTH);
        JPanel actions = new JPanel(new BorderLayout());
        actions.setOpaque(false);
        actions.add(UiTheme.label("Ctrl+Enter to send", Font.PLAIN, 12, UiTheme.MUTED),
                BorderLayout.WEST);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        cancelButton = UiTheme.secondaryButton("Cancel");
        cancelButton.setName("cancelComposeButton");
        cancelButton.addActionListener(event -> cancel());
        sendButton = UiTheme.primaryButton(demoMode ? "Send demo message" : "Send message");
        sendButton.setName("sendButton");
        sendButton.addActionListener(event -> submit());
        buttons.add(cancelButton);
        buttons.add(sendButton);
        actions.add(buttons, BorderLayout.EAST);
        footer.add(actions, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);

        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK), "sendDraft");
        getActionMap().put("sendDraft", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                submit();
            }
        });
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancelCompose");
        getActionMap().put("cancelCompose", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                cancel();
            }
        });
    }

    /** The owner calls this on the Swing event-dispatch thread while sending. */
    public void setBusy(boolean busy) {
        this.busy = busy;
        toField.setEnabled(!busy);
        subjectField.setEnabled(!busy);
        bodyArea.setEnabled(!busy);
        sendButton.setEnabled(!busy);
        cancelButton.setEnabled(!busy);
    }

    /** Shows a recoverable send error inline without discarding the draft. */
    public void showError(String message) {
        String text = message == null || message.isBlank() ? " " : message;
        errorLabel.setText(text);
        errorLabel.setToolTipText(text.isBlank() ? null : "Status: " + text);
    }

    private void submit() {
        if (busy) {
            return;
        }
        String rawRecipient = toField.getText();
        String recipient = rawRecipient.trim();
        String rawSubject = subjectField.getText();
        String subject = rawSubject.trim();
        String body = bodyArea.getText();
        if (rawRecipient.indexOf('\r') >= 0 || rawRecipient.indexOf('\n') >= 0
                || recipient.length() > 254 || !ADDRESS.matcher(recipient).matches()) {
            showError("Enter one email address, such as name@example.com.");
            toField.requestFocusInWindow();
            return;
        }
        if (subject.isBlank() || subject.length() > 200
                || rawSubject.indexOf('\r') >= 0 || rawSubject.indexOf('\n') >= 0) {
            showError("Enter a subject between 1 and 200 characters.");
            subjectField.requestFocusInWindow();
            return;
        }
        if (body.isBlank() || body.length() > 10_000) {
            showError("Enter a message between 1 and 10,000 characters.");
            bodyArea.requestFocusInWindow();
            return;
        }
        showError(null);
        onSend.accept(new Draft(recipient, subject, body));
    }

    private void cancel() {
        if (!busy) {
            onCancel.run();
        }
    }

    private static JPanel column() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        return panel;
    }

    private static void addField(JPanel panel, String title, JTextField field, int mnemonic) {
        JLabel label = UiTheme.label(title, Font.BOLD, 13, UiTheme.TEXT);
        label.setLabelFor(field);
        label.setDisplayedMnemonic(mnemonic);
        label.setAlignmentX(LEFT_ALIGNMENT);
        field.setAlignmentX(LEFT_ALIGNMENT);
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        field.setPreferredSize(new Dimension(560, 38));
        panel.add(label);
        panel.add(Box.createVerticalStrut(6));
        panel.add(field);
    }
}
