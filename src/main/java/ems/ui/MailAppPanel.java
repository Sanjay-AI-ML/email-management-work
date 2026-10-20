package ems.ui;

import ems.model.*;
import ems.service.EmailService;
import java.awt.*;
import java.awt.event.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.DefaultTableCellRenderer;

/** Member 1 UI. All service calls execute off the Swing event-dispatch thread. */
public final class MailAppPanel extends JPanel {
    private static final DateTimeFormatter DATE = DateTimeFormatter
            .ofPattern("dd MMM yyyy, HH:mm").withZone(ZoneId.systemDefault());
    private final EmailService service;
    private final boolean demo;
    private final CardLayout cards = new CardLayout();
    private final JPanel pages = UiTheme.panel(cards, UiTheme.BACKGROUND);
    private final EnumMap<Mailbox, JButton> navigation = new EnumMap<>(Mailbox.class);
    private final MailTableModel tableModel = new MailTableModel();
    private final JTable table = new JTable(tableModel);
    private final JTextField search = UiTheme.field("Search sender, subject or message");
    private final JLabel status = UiTheme.label("Ready to connect", Font.PLAIN, 12, UiTheme.MUTED);
    private final JLabel title = UiTheme.label("Inbox", Font.BOLD, 28, UiTheme.TEXT);
    private final JLabel subtitle = UiTheme.label("", Font.PLAIN, 12, UiTheme.MUTED);
    private final JLabel accountLabel = UiTheme.label("", Font.PLAIN, 11, new Color(0xB9C9D4));
    private final JButton connect = UiTheme.primaryButton("Connect Gmail");
    private final JButton refresh = UiTheme.secondaryButton("Refresh");
    private final JButton compose = UiTheme.primaryButton("+  Compose");
    private final JButton disconnect = UiTheme.secondaryButton("Disconnect");
    private final JButton markSpam = UiTheme.secondaryButton("Mark as spam");
    private final JButton notSpam = UiTheme.secondaryButton("Not spam");
    private final JButton searchButton = UiTheme.secondaryButton("Search");
    private final JPanel reader = UiTheme.panel(new BorderLayout(), UiTheme.SURFACE);
    private final JPanel listCards = UiTheme.panel(new CardLayout(), UiTheme.SURFACE);
    private final JLabel emptyTitle = UiTheme.label("No messages here", Font.BOLD, 18, UiTheme.TEXT);
    private final JLabel emptyDetail = UiTheme.label("", Font.PLAIN, 12, UiTheme.MUTED);
    private Account account;
    private Mailbox mailbox = Mailbox.INBOX;
    private boolean busy;
    private boolean updatingTable;
    private boolean connected;
    private EmailMessage openedMessage;

    public MailAppPanel(EmailService service) { this(service, false); }

    public MailAppPanel(EmailService service, boolean demoMode) {
        super(new BorderLayout());
        this.service = java.util.Objects.requireNonNull(service, "service");
        demo = demoMode;
        setBackground(UiTheme.BACKGROUND);
        setBorder(new EmptyBorder(0, 0, 0, 0));
        if (demo) add(demoBanner(), BorderLayout.NORTH);
        pages.add(connectionPage(), "connect");
        pages.add(mailPage(), "mail");
        add(pages, BorderLayout.CENTER);
        JPanel footer = UiTheme.panel(new BorderLayout(), UiTheme.SURFACE);
        footer.setBorder(new CompoundBorder(new MatteBorder(1, 0, 0, 0, UiTheme.BORDER),
                new EmptyBorder(9, 20, 9, 20)));
        status.setName("statusLabel");
        footer.add(status);
        footer.add(UiTheme.label("MAILDESK", Font.BOLD, 10, UiTheme.MUTED), BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
        wireActions();
        updateControls();
    }

    private JPanel demoBanner() {
        JPanel banner = UiTheme.panel(new FlowLayout(FlowLayout.LEFT, 20, 7), new Color(0xFFF3D6));
        banner.add(UiTheme.label("DEMO MODE", Font.BOLD, 11, new Color(0x805A16)));
        banner.add(UiTheme.label("Sample messages only. Nothing is sent to Gmail.", Font.PLAIN, 12,
                new Color(0x805A16)));
        return banner;
    }

    private JPanel connectionPage() {
        JPanel page = UiTheme.panel(new GridBagLayout(), UiTheme.BACKGROUND);
        JPanel card = UiTheme.panel(new BorderLayout(50, 0), UiTheme.SURFACE);
        card.setBorder(new CompoundBorder(new LineBorder(UiTheme.BORDER), new EmptyBorder(46, 46, 46, 46)));
        JPanel copy = UiTheme.panel(new GridBagLayout(), UiTheme.SURFACE);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.anchor = GridBagConstraints.WEST; c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1; c.insets = new Insets(0, 0, 22, 0);
        addRow(copy, UiTheme.label("MAILDESK", Font.BOLD, 15, UiTheme.ACCENT), c, 0);
        addRow(copy, UiTheme.label("Make room for", Font.BOLD, 35, UiTheme.TEXT), c, 1);
        c.insets = new Insets(-20, 0, 24, 0);
        addRow(copy, UiTheme.label("what matters.", Font.BOLD, 35, UiTheme.TEXT), c, 2);
        c.insets = new Insets(0, 0, 12, 0);
        addRow(copy, UiTheme.label("Your mail, a focused workspace, and a second", Font.PLAIN, 14, UiTheme.MUTED), c, 3);
        addRow(copy, UiTheme.label("look at messages that seem suspicious.", Font.PLAIN, 14, UiTheme.MUTED), c, 4);
        c.insets = new Insets(18, 0, 15, 0);
        connect.setName("connectButton");
        connect.setText(demo ? "Open demo mailbox  →" : "Connect Gmail  →");
        addRow(copy, connect, c, 5);
        c.insets = new Insets(0, 0, 6, 0);
        addRow(copy, UiTheme.label(demo ? "Try the screens with fictional messages." : "Sign in securely in your browser with Google.",
                Font.PLAIN, 12, UiTheme.MUTED), c, 6);
        addRow(copy, UiTheme.label(demo ? "No account or internet connection needed." : "Your Gmail password is never entered here.",
                Font.PLAIN, 12, UiTheme.MUTED), c, 7);
        card.add(copy, BorderLayout.CENTER);
        JPanel features = UiTheme.panel(new GridLayout(3, 1, 0, 16), UiTheme.SURFACE);
        features.setPreferredSize(new Dimension(280, 360));
        features.add(feature("01", "One clear inbox", "Read, search and write with less clutter."));
        features.add(feature("02", "A second look", "See why a message was flagged for review."));
        features.add(feature("03", "You're in control", "Correct spam decisions when needed."));
        card.add(features, BorderLayout.EAST);
        page.add(card);
        return page;
    }

    private JPanel feature(String number, String heading, String detail) {
        JPanel panel = UiTheme.panel(new BorderLayout(0, 8), UiTheme.BACKGROUND);
        panel.setBorder(new EmptyBorder(16, 18, 16, 18));
        panel.add(UiTheme.label(number + "  /  " + heading, Font.BOLD, 14, UiTheme.TEXT), BorderLayout.NORTH);
        JTextArea text = new JTextArea(detail);
        text.setFont(UiTheme.font(Font.PLAIN, 12)); text.setForeground(UiTheme.MUTED);
        text.setOpaque(false); text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true);
        panel.add(text);
        return panel;
    }

    private JPanel mailPage() {
        JPanel page = UiTheme.panel(new BorderLayout(), UiTheme.BACKGROUND);
        page.add(sidebar(), BorderLayout.WEST);
        JPanel workspace = UiTheme.panel(new BorderLayout(0, 20), UiTheme.BACKGROUND);
        workspace.setBorder(new EmptyBorder(25, 24, 20, 24));
        JPanel header = UiTheme.panel(new BorderLayout(12, 0), UiTheme.BACKGROUND);
        JPanel heading = UiTheme.panel(new GridLayout(2, 1, 0, 5), UiTheme.BACKGROUND);
        heading.add(title); heading.add(subtitle); header.add(heading);
        JPanel actions = UiTheme.panel(new FlowLayout(FlowLayout.RIGHT, 8, 0), UiTheme.BACKGROUND);
        refresh.setName("refreshButton"); actions.add(refresh); header.add(actions, BorderLayout.EAST);
        JPanel top = UiTheme.panel(new BorderLayout(0, 18), UiTheme.BACKGROUND);
        top.add(header, BorderLayout.NORTH);
        JPanel searchRow = UiTheme.panel(new BorderLayout(8, 0), UiTheme.BACKGROUND);
        search.setName("searchField"); searchButton.setName("searchButton");
        searchRow.add(search); searchRow.add(searchButton, BorderLayout.EAST);
        top.add(searchRow); workspace.add(top, BorderLayout.NORTH);
        configureTable();
        JScrollPane scroll = new JScrollPane(table);
        // Install explicitly so headless previews and native windows have the same header.
        scroll.setColumnHeaderView(table.getTableHeader());
        scroll.setBorder(BorderFactory.createEmptyBorder()); scroll.getViewport().setBackground(UiTheme.SURFACE);
        listCards.add(scroll, "messages");
        JPanel empty = UiTheme.panel(new GridBagLayout(), UiTheme.SURFACE);
        JPanel emptyCopy = UiTheme.panel(new GridLayout(2, 1, 0, 12), UiTheme.SURFACE);
        emptyCopy.add(emptyTitle); emptyCopy.add(emptyDetail); empty.add(emptyCopy);
        listCards.add(empty, "empty");
        JPanel list = UiTheme.panel(new BorderLayout(), UiTheme.SURFACE);
        list.setBorder(new LineBorder(UiTheme.BORDER));
        JLabel listTitle = UiTheme.label("MESSAGES", Font.BOLD, 10, UiTheme.MUTED);
        listTitle.setBorder(new EmptyBorder(15, 14, 12, 14)); list.add(listTitle, BorderLayout.NORTH);
        list.add(listCards);
        list.setMinimumSize(new Dimension(360, 300));
        reader.setMinimumSize(new Dimension(350, 300)); reader.setBorder(new LineBorder(UiTheme.BORDER));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, list, reader);
        split.setBorder(BorderFactory.createEmptyBorder()); split.setContinuousLayout(true);
        split.setResizeWeight(0.48); split.setDividerSize(9); split.setBackground(UiTheme.BACKGROUND);
        workspace.add(split); page.add(workspace); showReaderPlaceholder("Select a message", "Its contents and spam review will appear here.");
        return page;
    }

    private JPanel sidebar() {
        JPanel sidebar = UiTheme.panel(new BorderLayout(0, 24), UiTheme.SIDEBAR);
        sidebar.setPreferredSize(new Dimension(212, 700));
        sidebar.setBorder(new EmptyBorder(30, 17, 18, 17));
        JPanel top = UiTheme.panel(new GridBagLayout(), UiTheme.SIDEBAR);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 9, 5, 0);
        addRow(top, UiTheme.label("MAILDESK", Font.BOLD, 20, Color.WHITE), c, 0);
        addRow(top, UiTheme.label("A little more clarity.", Font.PLAIN, 11, new Color(0xB9C9D4)), c, 1);
        c.insets = new Insets(28, 0, 26, 0); compose.setName("composeButton"); addRow(top, compose, c, 2);
        c.insets = new Insets(0, 9, 12, 0);
        addRow(top, UiTheme.label("WORKSPACE", Font.BOLD, 10, new Color(0x91ACBC)), c, 3);
        int row = 4;
        for (Mailbox box : Mailbox.values()) {
            JButton button = new JButton(mailboxName(box));
            button.setHorizontalAlignment(SwingConstants.LEFT); button.setFont(UiTheme.font(Font.BOLD, 13));
            button.setOpaque(true); button.setFocusPainted(true);
            button.setBorder(new EmptyBorder(13, 12, 13, 8));
            button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            button.setName(switch (box) { case INBOX -> "inboxButton"; case SENT -> "sentButton";
                case SPAM -> "spamButton"; case SUSPECTED -> "suspectedButton"; });
            button.addActionListener(event -> changeMailbox(box));
            navigation.put(box, button); c.insets = new Insets(0, 0, 5, 0); addRow(top, button, c, row++);
        }
        sidebar.add(top, BorderLayout.NORTH);
        JPanel bottom = UiTheme.panel(new GridLayout(3, 1, 0, 9), UiTheme.SIDEBAR);
        bottom.add(UiTheme.label("CONNECTED ACCOUNT", Font.BOLD, 10, new Color(0x91ACBC)));
        bottom.add(accountLabel); disconnect.setName("disconnectButton"); bottom.add(disconnect);
        sidebar.add(bottom, BorderLayout.SOUTH);
        return sidebar;
    }

    private void configureTable() {
        table.setName("messageTable"); table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(58); table.setShowGrid(false); table.setIntercellSpacing(new Dimension(0, 0));
        table.setBackground(UiTheme.SURFACE); table.setForeground(UiTheme.TEXT);
        table.setSelectionBackground(UiTheme.SELECTED); table.setSelectionForeground(UiTheme.TEXT);
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setBackground(new Color(0xF7F9FA));
        table.getTableHeader().setForeground(UiTheme.MUTED);
        table.getTableHeader().setFont(UiTheme.font(Font.BOLD, 10));
        table.getTableHeader().setPreferredSize(new Dimension(400, 35));
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object value,
                    boolean selected, boolean focused, int row, int column) {
                // Plain text only: mail content must not trigger Swing HTML rendering.
                putClientProperty("html.disable", Boolean.TRUE);
                super.getTableCellRendererComponent(t, value, selected, focused, row, column);
                setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, UiTheme.BORDER),
                        new EmptyBorder(0, 12, 0, 9)));
                setFont(UiTheme.font(tableModel.isUnread(row) ? Font.BOLD : Font.PLAIN, column == 2 ? 10 : 12));
                setForeground(column == 2 ? UiTheme.MUTED : UiTheme.TEXT);
                setBackground(selected ? UiTheme.SELECTED : UiTheme.SURFACE);
                setToolTipText("Message: " + value);
                return this;
            }
        });
        setColumnWidths();
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !updatingTable && !busy && table.getSelectedRow() >= 0) {
                open(tableModel.getMessageAt(table.getSelectedRow()).getGmailMessageId());
            }
        });
    }

    private void setColumnWidths() {
        table.getColumnModel().getColumn(0).setPreferredWidth(150);
        table.getColumnModel().getColumn(1).setPreferredWidth(215);
        table.getColumnModel().getColumn(2).setPreferredWidth(100);
    }

    private void wireActions() {
        connect.addActionListener(event -> runTask("Connecting…", () -> {
            Account value = service.connectGmail();
            try {
                service.refresh();
                return new Connected(value, service.getMessages(Mailbox.INBOX, ""));
            } catch (Exception failure) {
                try { service.disconnect(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }, result -> {
            account = result.account(); connected = true; mailbox = Mailbox.INBOX;
            accountLabel.setText(account.getEmailAddress()); accountLabel.setToolTipText("Account: " + account.getEmailAddress());
            cards.show(pages, "mail"); applyMessages(result.messages());
            showNotice(demo ? "Demo mailbox open. No Gmail connection is active." : "Gmail connected. Inbox updated.", false);
        }));
        refresh.addActionListener(event -> load(true));
        searchButton.addActionListener(event -> load(false));
        search.addActionListener(event -> load(false));
        compose.addActionListener(event -> showCompose());
        disconnect.addActionListener(event -> close(() -> {
            account = null; connected = false; search.setText(""); tableModel.setMessages(List.of());
            openedMessage = null; cards.show(pages, "connect"); updateControls();
            showNotice("Disconnected. You can connect again when ready.", false);
        }));
        markSpam.setName("markSpamButton"); notSpam.setName("notSpamButton");
        markSpam.addActionListener(event -> correctSpam(true));
        notSpam.addActionListener(event -> correctSpam(false));
    }

    private void changeMailbox(Mailbox next) {
        if (busy || !connected) return;
        runTask("Loading " + mailboxName(next) + "…", () -> service.getMessages(next, ""), messages -> {
            mailbox = next; search.setText(""); applyMessages(messages);
            showNotice("Showing " + mailboxName(next).toLowerCase() + ".", false);
        });
        // The selected mailbox and query change only after the service succeeds.
    }

    private void load(boolean sync) {
        if (busy || !connected) return;
        String query = search.getText().strip();
        runTask(sync ? "Refreshing mailbox…" : "Searching messages…", () -> {
            if (sync) service.refresh();
            return service.getMessages(mailbox, query);
        }, messages -> {
            applyMessages(messages);
            showNotice(messages.size() + (messages.size() == 1 ? " message" : " messages")
                    + (query.isEmpty() ? " in this view." : " matching your search."), false);
        });
    }

    private void applyMessages(List<EmailMessage> messages) {
        updatingTable = true;
        tableModel.setSent(mailbox == Mailbox.SENT); tableModel.setMessages(messages); setColumnWidths();
        table.clearSelection(); updatingTable = false;
        openedMessage = null;
        title.setText(mailboxName(mailbox));
        subtitle.setText(switch (mailbox) {
            case INBOX -> "A clear view of what has arrived.";
            case SENT -> "Messages you have sent.";
            case SPAM -> "Messages classified as spam in Gmail.";
            case SUSPECTED -> "Our detector's suggestions. Review before deciding.";
        });
        ((CardLayout) listCards.getLayout()).show(listCards, messages.isEmpty() ? "empty" : "messages");
        emptyTitle.setText(search.getText().isBlank() ? "All clear here" : "No matching messages");
        emptyDetail.setText(search.getText().isBlank() ? "Refresh to check for new messages." : "Try another search or clear the search field.");
        showReaderPlaceholder("Select a message", "Read it here without leaving your inbox.");
        updateControls(); revalidate(); repaint();
    }

    private void open(String id) {
        Mailbox currentMailbox = mailbox;
        String query = search.getText().strip();
        openedMessage = null;
        showReaderPlaceholder("Opening message…", "Loading the message and its review.");
        runTask("Opening message…", () -> new Opened(service.openMessage(id), service.getSpamResult(id),
                service.getMessages(currentMailbox, query)), result -> {
            updatingTable = true; tableModel.setMessages(result.messages());
            for (int row = 0; row < tableModel.getRowCount(); row++) {
                if (tableModel.getMessageAt(row).getGmailMessageId().equals(id)) table.setRowSelectionInterval(row, row);
            }
            updatingTable = false; openedMessage = result.message();
            showMessage(result.message(), result.result());
            showNotice("Message opened.", false);
        }, error -> {
            updatingTable = true; table.clearSelection(); updatingTable = false;
            showReaderPlaceholder("Couldn't open this message", "Select it again to retry, or refresh your mailbox.");
            showNotice(error, true);
        });
    }

    private void showReaderPlaceholder(String heading, String detail) {
        reader.removeAll();
        JPanel center = UiTheme.panel(new GridBagLayout(), UiTheme.SURFACE);
        JPanel text = UiTheme.panel(new GridLayout(3, 1, 0, 12), UiTheme.SURFACE);
        text.add(UiTheme.label("○", Font.PLAIN, 42, UiTheme.ACCENT));
        text.add(UiTheme.label(heading, Font.BOLD, 20, UiTheme.TEXT));
        text.add(UiTheme.label(detail, Font.PLAIN, 12, UiTheme.MUTED)); center.add(text); reader.add(center);
        reader.revalidate(); reader.repaint();
    }

    private void showMessage(EmailMessage message, SpamResult result) {
        reader.removeAll();
        JPanel content = UiTheme.panel(new BorderLayout(0, 18), UiTheme.SURFACE);
        content.setBorder(new EmptyBorder(24, 24, 20, 24));
        ReaderArticle article = new ReaderArticle();
        JTextArea subject = plainText(message.getSubject().isBlank() ? "(No subject)" : message.getSubject(), 22, Font.BOLD);
        subject.setName("messageSubject"); article.add(subject);
        article.add(plainText("From  " + message.getSenderEmail(), 12, Font.BOLD));
        JTextArea recipients = plainText("To  " + String.join(", ", message.getRecipientEmails()), 12, Font.PLAIN);
        recipients.setForeground(UiTheme.MUTED); article.add(recipients);
        article.add(UiTheme.label(DATE.format(message.getReceivedAt()), Font.PLAIN, 11, UiTheme.MUTED));
        if (!message.getGmailLabelIds().contains("SENT")) {
            ReaderColumn review = new ReaderColumn(6);
            review.setBackground(result.isSuspectedSpam() ? new Color(0xFFF5DE) : new Color(0xEDF5F2));
            review.setBorder(new EmptyBorder(12, 12, 12, 12));
            review.add(UiTheme.label(result.isSuspectedSpam() ? "Review suggested · Score " + result.getScore()
                    : "No rule flag · Score " + result.getScore(), Font.BOLD, 12, UiTheme.TEXT));
            String reasons = result.getReasons().isEmpty() ? "A rule score is not a guarantee of safety."
                    : String.join("\n", result.getReasons());
            JTextArea explanation = plainText(reasons, 11, Font.PLAIN); explanation.setName("spamReasons");
            review.add(explanation); article.add(review);
        }
        article.add(Box.createVerticalStrut(8));
        JTextArea body = plainText(message.getBodyText().isBlank() ? "(This message has no plain-text content.)" : message.getBodyText(), 14, Font.PLAIN);
        body.setName("messageBody"); body.setBorder(new EmptyBorder(4, 0, 12, 8));
        article.add(body);
        JScrollPane scroll = new JScrollPane(article); scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        scroll.getViewport().setBackground(UiTheme.SURFACE); content.add(scroll);
        JPanel actions = UiTheme.panel(new FlowLayout(FlowLayout.LEFT, 6, 0), UiTheme.SURFACE);
        if (!message.getGmailLabelIds().contains("SENT")) { actions.add(markSpam); actions.add(notSpam); }
        content.add(actions, BorderLayout.SOUTH); reader.add(content); reader.revalidate(); reader.repaint();
    }

    private void correctSpam(boolean spam) {
        if (busy || openedMessage == null) return;
        String id = openedMessage.getGmailMessageId();
        Mailbox currentMailbox = mailbox;
        String query = search.getText().strip();
        runTask(spam ? "Marking as spam…" : "Saving correction…", () -> {
            if (spam) service.markAsSpam(id); else service.markAsNotSpam(id);
            return service.getMessages(currentMailbox, query);
        }, messages -> {
            applyMessages(messages);
            showNotice(spam ? "Marked as spam. Your correction was saved." : "Marked as not spam. Your correction was saved.", false);
        });
    }

    private void showCompose() {
        if (busy || account == null) return;
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this), "New message", Dialog.ModalityType.APPLICATION_MODAL);
        ComposePanel[] editor = new ComposePanel[1];
        editor[0] = new ComposePanel(account.getEmailAddress(), demo, draft -> {
            editor[0].setBusy(true);
            runTask(demo ? "Sending demo message…" : "Sending message…",
                    () -> service.sendMessage(draft.recipientEmail(), draft.subject(), draft.body()), id -> {
                        dialog.dispose();
                        showNotice(demo ? "Demo message added to Sent. Nothing was sent to Gmail." : "Message sent successfully.", false);
                        if (mailbox == Mailbox.SENT) load(false);
                    }, error -> {
                        editor[0].setBusy(false); editor[0].showError(error); showNotice(error, true);
                    });
        }, dialog::dispose);
        dialog.setContentPane(editor[0]); dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { if (!busy) dialog.dispose(); }
        });
        dialog.pack(); dialog.setMinimumSize(new Dimension(600, 530));
        dialog.setLocationRelativeTo(this); dialog.setVisible(true);
    }

    public boolean isBusy() { return busy; }

    public void showNotice(String message, boolean error) {
        status.setText(message); status.setForeground(error ? new Color(0xAA342A) : UiTheme.MUTED);
        status.setToolTipText("Status: " + message);
    }

    /** Finishes disconnection on a worker before invoking the callback on the EDT. */
    public void close(Runnable afterClose) {
        if (busy) return;
        runTask("Disconnecting…", () -> { service.disconnect(); return Boolean.TRUE; }, result -> {
            connected = false; afterClose.run();
        });
    }

    private void updateControls() {
        connect.setEnabled(!busy);
        for (var entry : navigation.entrySet()) {
            entry.getValue().setEnabled(connected && !busy);
            entry.getValue().setBackground(entry.getKey() == mailbox ? new Color(0x2D4C59) : UiTheme.SIDEBAR);
            entry.getValue().setForeground(entry.getKey() == mailbox ? Color.WHITE : new Color(0xC0CFD8));
        }
        for (JComponent control : new JComponent[] {refresh, compose, disconnect, search, searchButton, table}) {
            control.setEnabled(connected && !busy);
        }
        boolean canCorrect = connected && !busy && openedMessage != null
                && !openedMessage.getGmailLabelIds().contains("SENT");
        markSpam.setEnabled(canCorrect); notSpam.setEnabled(canCorrect);
        setCursor(Cursor.getPredefinedCursor(busy ? Cursor.WAIT_CURSOR : Cursor.DEFAULT_CURSOR));
    }

    private <T> void runTask(String activity, Callable<T> work, Consumer<T> success) {
        runTask(activity, work, success, error -> showNotice(error, true));
    }

    private <T> void runTask(String activity, Callable<T> work, Consumer<T> success, Consumer<String> failure) {
        if (busy) return;
        busy = true; updateControls(); showNotice(activity, false);
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return work.call(); }
            @Override protected void done() {
                busy = false;
                try {
                    T result = get();
                    success.accept(result);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt(); failure.accept("The operation was interrupted. Please try again.");
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    String message = cause instanceof ems.exception.AppException ? cause.getMessage()
                            : "The operation could not finish. Please try again.";
                    failure.accept(message == null || message.isBlank() ? "The operation could not finish." : message);
                } catch (CancellationException exception) {
                    failure.accept("The operation was cancelled.");
                } finally { updateControls(); }
            }
        }.execute();
    }

    private static JTextArea plainText(String text, int size, int style) {
        JTextArea area = new JTextArea(text);
        area.setFont(UiTheme.font(style, size)); area.setForeground(UiTheme.TEXT);
        area.setOpaque(false); area.setEditable(false); area.setLineWrap(true); area.setWrapStyleWord(true);
        area.setCaretPosition(0); return area;
    }

    private static String mailboxName(Mailbox box) {
        return switch (box) { case INBOX -> "Inbox"; case SENT -> "Sent"; case SPAM -> "Gmail Spam";
            case SUSPECTED -> "Suspected Spam"; };
    }

    private static void addRow(JPanel panel, JComponent component, GridBagConstraints c, int row) {
        c.gridy = row; panel.add(component, c);
    }

    private record Connected(Account account, List<EmailMessage> messages) { }
    private record Opened(EmailMessage message, SpamResult result, List<EmailMessage> messages) { }

    /**
     * Measures wrapped text at its allocated width before computing scroll height.
     * Standard BorderLayout/BoxLayout preferences can use a text area's old width,
     * cutting off its final lines after a narrow viewport is assigned.
     */
    private static class ReaderColumn extends JPanel {
        private final int gap;

        ReaderColumn(int gap) {
            super(null);
            this.gap = gap;
            setBackground(UiTheme.SURFACE);
        }

        @Override public Dimension getPreferredSize() {
            int width = getParent() instanceof JViewport viewport
                    ? viewport.getExtentSize().width : getWidth();
            if (width <= 0) width = 420;
            return new Dimension(width, heightForWidth(width));
        }

        private int heightForWidth(int width) {
            Insets insets = getInsets();
            int available = Math.max(1, width - insets.left - insets.right);
            int height = insets.top + insets.bottom;
            boolean first = true;
            for (Component child : getComponents()) {
                if (!child.isVisible()) continue;
                if (!first) height += gap;
                height += childHeight(child, available);
                first = false;
            }
            return height;
        }

        @Override public void doLayout() {
            Insets insets = getInsets();
            int width = Math.max(1, getWidth() - insets.left - insets.right);
            int y = insets.top;
            boolean first = true;
            for (Component child : getComponents()) {
                if (!child.isVisible()) continue;
                if (!first) y += gap;
                int height = childHeight(child, width);
                child.setBounds(insets.left, y, width, height);
                y += height;
                first = false;
            }
        }

        private static int childHeight(Component child, int width) {
            if (child instanceof ReaderColumn column) return column.heightForWidth(width);
            if (child instanceof JTextArea text) {
                Insets insets = text.getInsets();
                javax.swing.text.View view = text.getUI().getRootView(text);
                view.setSize(Math.max(1, width - insets.left - insets.right), Integer.MAX_VALUE);
                return (int) Math.ceil(view.getPreferredSpan(javax.swing.text.View.Y_AXIS))
                        + insets.top + insets.bottom;
            }
            return child.getPreferredSize().height;
        }
    }

    /** Incoming headers, spam reasons and body share one width-tracking scroll area. */
    private static final class ReaderArticle extends ReaderColumn implements Scrollable {
        ReaderArticle() { super(10); }
        @Override public Dimension getPreferredScrollableViewportSize() { return new Dimension(420, 500); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int orientation, int direction) { return 18; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int orientation, int direction) { return Math.max(18, r.height - 36); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
