package ems.demo;

import ems.ui.MainFrame;
import ems.ui.UiTheme;

import javax.swing.SwingUtilities;

/** Starts the interface with fictional, nonpersistent local data. */
public final class DemoApp {
    private DemoApp() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            UiTheme.install();
            MainFrame frame = new MainFrame(new DemoEmailService(), true);
            frame.setVisible(true);
        });
    }
}
