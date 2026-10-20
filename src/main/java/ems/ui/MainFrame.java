package ems.ui;

import ems.service.EmailService;
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;

/** Production integration: construct on the EDT with Member 2's EmailService. */
public final class MainFrame extends JFrame {
    private final MailAppPanel appPanel;

    public MainFrame(EmailService service) { this(service, false); }

    public MainFrame(EmailService service, boolean demoMode) {
        super(demoMode ? "Maildesk — Interface demo" : "Maildesk — Email management");
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Create MainFrame on the Swing event-dispatch thread.");
        }
        appPanel = new MailAppPanel(service, demoMode);
        setContentPane(appPanel);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        Rectangle available = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setMinimumSize(new Dimension(Math.min(1100, available.width), Math.min(700, available.height)));
        setSize(Math.min(1360, available.width), Math.min(860, available.height));
        setLocationRelativeTo(null);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                if (appPanel.isBusy()) {
                    appPanel.showNotice("Please wait for the current operation to finish.", true);
                    return;
                }
                appPanel.close(MainFrame.this::dispose);
            }
        });
    }

    public MailAppPanel getAppPanel() { return appPanel; }
}
