# Member 1: Swing interface handoff

This is the Java Swing interface for the Gmail-first email management project.
The production app now connects this interface to Gmail, JDBC persistence, and
spam analysis. The separate demo still uses fictional `.test` addresses and
in-memory messages; it never sends real emails.

## Build and run

Install JDK 17 or newer and make `java` and `javac` available on `PATH`.
The Maven Wrapper downloads dependencies automatically. Google credentials and
a database are unnecessary for the demo and headless tests.
From the project folder in PowerShell:

```powershell
.\scripts\build.ps1
.\scripts\check.ps1
.\scripts\run-demo.ps1
# Production app, after completing README.md setup:
.\scripts\run-app.ps1 -NoBuild
```

The build targets Java 17. The checks run without opening a window. Run the
last command to open the desktop demo; changes last only until it is closed.

## Team ownership

| Member | Owns |
| --- | --- |
| 1 | `ems.ui`: connection state, mailbox views, search, reading, composing, spam review, busy states, and errors |
| 2 | Gmail authorization and `EmailService` implementation; shared models and exceptions |
| 3 | MySQL storage, JDBC, and spam classification rules |

`src/demo/java/ems/demo` is a local UI test adapter, not the production backend.
The shared models and service interface in `src/main/java` are integration
contracts; agree with teammates before changing their signatures. Production
authorization and persistence are implemented outside the interface packages.

`MainFrame` is the desktop window. `MailAppPanel` contains the connection page,
mailboxes, reader, search, compose flow, and spam controls. `ComposePanel`
validates compose input, and `MailTableModel` supplies the message list. The demo
launcher creates `new MainFrame(new DemoEmailService(), true)`; the `true` flag
keeps the demo banner and simulated-send wording visible.

## Connecting the real backend

`ems.App` supplies the production `GmailEmailService` implementation of
`ems.service.EmailService` and opens the frame on Swing's event dispatch thread:

```java
EmailService service = /* production implementation */;
SwingUtilities.invokeLater(() -> {
    UiTheme.install();
    MainFrame frame = new MainFrame(service);
    frame.setVisible(true);
});
```

Import `javax.swing.SwingUtilities`, `ems.service.EmailService`,
`ems.ui.UiTheme`, and `ems.ui.MainFrame`. Service calls may block; the UI runs
them in `SwingWorker` background workers and updates components on the event
dispatch thread. Busy controls prevent overlapping operations. Return errors as
`AppException` so the UI can show them. The service owns the currently connected
account; all mailbox operations must stay scoped to that account.

Keep Gmail's Spam mailbox separate from locally Suspected messages. A spam
score is a rule score, not a probability. Corrections must be saved by the real
backend. Change Gmail's spam labels only when the user explicitly clicks
**Mark as spam** or **Not spam**. Automatic local analysis should flag messages
for the separate Suspected view without moving them in Gmail. Refresh and
search should use the established `EmailService` contract.

## Verification and scope

The checks cover shared-model immutability and the demo's connection, search,
read, send, spam correction, missing-message protection, and disconnect flows.
The separate headless UI checks exercise the Swing components and their service
integration without opening a window.
Test the integrated backend with dedicated Gmail test accounts after completing
the one-time OAuth and database setup in README.md. See MEMBER2.md for a manual
acceptance checklist and MEMBER3_REVIEW.md for database integration findings.

Never commit OAuth tokens, credentials, database passwords, or real message
exports. `.gitignore` includes the agreed local paths; use fictional fixtures
for shared tests.
