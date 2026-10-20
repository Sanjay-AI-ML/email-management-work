# Maildesk: Gmail email management system

A Java 17 desktop client built with Swing, JDBC, and MySQL. Connect a Gmail
account, read Inbox/Sent/Spam, send text emails, search the local cache, and
review explainable spam scores. Gmail's Spam mailbox and the application's
Suspected view remain separate. Automatic scoring never moves mail; the spam
buttons apply the user's explicit correction to Gmail and save it locally.

## Requirements

- JDK 17 or newer, with `java` and `javac` available on `PATH`.
- A running MySQL 8 server and a database user/password.
- A Google account with Gmail and a Desktop OAuth client for the Gmail API.
- Internet access for the first build, authorization, and Gmail operations.

The checked-in Maven Wrapper downloads the pinned Maven version and dependencies;
installing Maven separately is unnecessary. Application code compiles for Java 17.

## Configure once

From the project folder in PowerShell:

```powershell
Copy-Item config/app.properties.example config/app.properties
```

Edit `config/app.properties` and set `db.user` and `db.password` to your local
MySQL credentials. The default URL targets `email_management` on port 3306 and
requests creation of that database. The application initializes its tables on
connect. If your database user cannot create databases, have your administrator
create `email_management` first and grant that user access to it.

For a dedicated local user, an administrator can run the following once, replacing
the placeholder password before execution:

```sql
CREATE DATABASE IF NOT EXISTS email_management
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'maildesk'@'localhost' IDENTIFIED BY 'REPLACE_WITH_YOUR_PASSWORD';
GRANT ALL PRIVILEGES ON email_management.* TO 'maildesk'@'localhost';
```

Then set `db.user=maildesk` and the corresponding password locally.

### Google authorization

1. Create/select a project in [Google Cloud Console](https://console.cloud.google.com/).
2. Enable the **Gmail API**.
3. Configure **Google Auth platform** branding and audience. For personal Gmail
   accounts, use **External**, keep development in **Testing**, and add your
   team's Gmail addresses as test users.
4. Add the `https://www.googleapis.com/auth/gmail.modify` scope under Data Access.
5. Under **Clients**, create an OAuth client with application type **Desktop app**.
6. Download its JSON and save it locally as `config/credentials.json`.

The app opens your browser for Google consent and receives the result on a local
loopback address. Maildesk never asks for your Gmail password. Google documents
the [desktop authorization flow](https://developers.google.com/identity/protocols/oauth2/native-app)
and [Gmail project setup](https://developers.google.com/workspace/gmail/api/quickstart/java).
Broader public distribution needs Google's verification for the restricted
[`gmail.modify` scope](https://developers.google.com/workspace/gmail/api/auth/scopes).

Local credentials, saved tokens, and `config/app.properties` are ignored by Git.
Do not paste secrets into issues or commit actual emails. Tokens are saved under
`config/tokens` by default; keep access to that local directory private.
Disconnecting clears local authorization, so reconnect through Google when
starting a new session.

## Build and run

```powershell
.\mvnw.cmd --batch-mode clean package
.\scripts\run-app.ps1 -NoBuild --check-setup
.\scripts\run-app.ps1 -NoBuild
```

Click **Connect Gmail** and complete Google consent. You can use
`.\scripts\run-app.ps1` without `-NoBuild` to build before opening the app.
The distributable jar is `target/maildesk.jar`; launch from the project directory:

```powershell
java -jar target/maildesk.jar
```

MySQL and the OAuth client are still required when running the jar. The jar
contains the JDBC driver and other libraries, but never local passwords/tokens.

Configuration can also come from environment variables. They override the file:

| Environment variable | Property |
| --- | --- |
| `DB_URL` | `db.url` |
| `DB_USER` | `db.user` |
| `DB_PASSWORD` | `db.password` |
| `GMAIL_CREDENTIALS` | `gmail.credentials` |
| `GMAIL_TOKEN_DIR` | `gmail.tokenDir` |

Search covers cached messages. Refresh requires an internet connection; Gmail
remains the source of truth. This version supports plain-text composition, not
attachments, rich-text editing, or scheduled/background synchronization.

## Verify

```powershell
.\scripts\check.ps1
# Also save interface preview images under build/previews:
.\scripts\check.ps1 -Render
```

The regular checks run headlessly against fictional fixtures, a local fake HTTP
server, and an isolated H2 database in MySQL mode. The real MySQL test is skipped
by default. To enable it after configuring local database credentials:

```powershell
.\mvnw.cmd '-Dmaildesk.mysqlTest=true' test
```

The complete suite passed **50 JUnit tests with zero failures or skips**, plus
**53 contract/demo assertions and 40 Swing interaction assertions**, with that
MySQL option enabled. The real database test creates a random `.test` account
and removes its fixture rows afterward; Gmail is simulated and no actual email
is sent. Setup checking also confirmed the configured MySQL connection and
schema are ready.

Live Gmail authorization and delivery still require your Google Desktop OAuth
JSON and browser consent. See [MEMBER2.md](MEMBER2.md) for the real-account
acceptance checklist; these automated checks do not establish live Gmail access.

The standalone demo is still available without Google or MySQL setup:

```powershell
.\scripts\run-demo.ps1
```

It uses fictional messages in memory and clearly labels simulated sends.

## Project layout

- `src/main/java/ems/ui`: Member 1's Swing interface.
- `src/main/java/ems/gmail` and `ems/service`: Gmail access and orchestration.
- `src/main/java/ems/storage`: account-scoped JDBC persistence.
- `src/main/java/com/emailapp`: reviewed Member 3 components and compatibility code.
- `sql/schema.sql`: MySQL table definitions, also bundled as a jar resource.
- `src/demo/java`: fictional in-memory demo adapter.
- `src/test/java`: service, API, database, spam, and interface checks.

See [MEMBER3_REVIEW.md](MEMBER3_REVIEW.md) for integration findings and fixes.
