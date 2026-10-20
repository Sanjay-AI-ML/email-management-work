package com.emailapp;

import ems.service.EmailService;
import ems.service.GmailEmailService;
import ems.ui.MainFrame;
import ems.ui.UiTheme;

import javax.swing.SwingUtilities;

/**
 * Main production entry point for Maildesk email management application.
 * Connects GmailEmailService to the Swing desktop interface.
 */
public class App {

    public static void main(String[] args) {
        EmailService service = new GmailEmailService();

        SwingUtilities.invokeLater(() -> {
            UiTheme.install();
            MainFrame frame = new MainFrame(service);
            frame.setVisible(true);
        });
    }
}

