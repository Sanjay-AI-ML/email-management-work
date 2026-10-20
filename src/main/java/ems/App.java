package ems;

import ems.config.AppConfig;
import ems.exception.AppException;
import ems.gmail.GmailRestGateway;
import ems.service.GmailEmailService;
import ems.storage.JdbcMailStore;
import ems.ui.MainFrame;
import ems.ui.UiTheme;
import java.nio.file.Files;
import java.sql.DriverManager;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/** Production entry point. The demo has its own explicitly labelled launcher. */
public final class App {
    private App() { }

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--help")) {
            System.out.println("Maildesk - Java Gmail client\n"
                    + "Run: java -jar target/maildesk.jar\n"
                    + "Check setup: java -jar target/maildesk.jar --check-setup\n"
                    + "Configuration: config/app.properties (or DB_URL, DB_USER, DB_PASSWORD, GMAIL_CREDENTIALS, GMAIL_TOKEN_DIR).\n"
                    + "See README.md for Google Desktop OAuth and MySQL setup.");
            return;
        }
        if (args.length > 1 || args.length == 1 && !args[0].equals("--check-setup")) {
            System.err.println("Unknown option. Use --help or --check-setup.");
            System.exit(2);
        }
        try {
            AppConfig config = AppConfig.load();
            JdbcMailStore store = new JdbcMailStore(() -> DriverManager.getConnection(
                    config.getDatabaseUrl(), config.getDatabaseUser(), config.getDatabasePassword()));
            if (args.length == 1) {
                boolean credentials = Files.isRegularFile(config.getGmailCredentialsPath());
                System.out.println("Google Desktop OAuth JSON: " + (credentials ? "found" : "missing - follow README.md setup"));
                boolean database = false;
                try { store.initialize(); database = true; System.out.println("MySQL: connected; schema ready."); }
                catch (java.sql.SQLException exception) {
                    System.out.println("MySQL: unavailable - check db.url, db.user and db.password in config/app.properties.");
                }
                System.out.println("Live Gmail access also requires the user to authorize the app in their browser.");
                if (!credentials || !database) System.exit(1);
                return;
            }
            GmailEmailService service = new GmailEmailService(new GmailRestGateway(
                    config.getGmailCredentialsPath(), config.getTokenDirectory()), store);
            SwingUtilities.invokeLater(() -> {
                UiTheme.install();
                new MainFrame(service).setVisible(true);
            });
        } catch (AppException | RuntimeException exception) {
            String message = exception instanceof AppException ? exception.getMessage()
                    : "Maildesk could not start. Check config/app.properties and use --check-setup.";
            System.err.println(message);
            if (args.length == 0 && !java.awt.GraphicsEnvironment.isHeadless()) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null, message,
                        "Maildesk setup", JOptionPane.ERROR_MESSAGE));
            }
            System.exit(1);
        }
    }
}
