# Maildesk: email management system

Member 1's Java Swing desktop interface for a Gmail-first email management
project. Includes Inbox, Sent, Gmail Spam, Suspected messages, search, message
reading, compose validation, and explicit spam corrections.

The runnable demo uses fictional messages in memory. It does not connect to
Google, send real email, or store data in MySQL. Members 2 and 3 will supply the
real Gmail service, database, and spam classifier.

## Run

Use JDK 17 or newer with `java` and `javac` on `PATH`. From this folder in
PowerShell:

```powershell
.\scripts\run-demo.ps1
```

The command builds the sources, then opens the demo desktop window.

## Check

```powershell
.\scripts\check.ps1
```

Checks run headlessly and need no Gmail account, database, or external Java
libraries. Build without launching with `.\scripts\build.ps1`.

See [MEMBER1.md](MEMBER1.md) for file ownership, integration instructions, and
the remaining backend work. The shared interface is
[`EmailService.java`](src/main/java/ems/service/EmailService.java).

Keep credentials, authorization tokens, passwords, and real emails out of Git.
Use the fictional demo fixtures for team development.
