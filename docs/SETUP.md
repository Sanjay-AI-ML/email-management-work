# Setup

## Database

Copy `config/app.properties.example` to `config/app.properties` and set `db.user`
and `db.password`. The default URL targets `email_management` on localhost:3306.
Startup creates the database when permitted and initializes its tables.

To use a dedicated local account, run the following as a MySQL administrator,
replacing the placeholder password:

```sql
CREATE DATABASE IF NOT EXISTS email_management
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'maildesk'@'localhost' IDENTIFIED BY 'REPLACE_WITH_YOUR_PASSWORD';
GRANT ALL PRIVILEGES ON email_management.* TO 'maildesk'@'localhost';
```

Set `db.user=maildesk` and the corresponding password in the local properties
file. If using another existing database, set `db.url` explicitly. Startup does
not migrate data from other databases; `sql/schema.sql` describes the current schema.

## Google OAuth

1. Create or select a project in [Google Cloud Console](https://console.cloud.google.com/).
2. Enable the **Gmail API**.
3. Configure Google Auth Platform branding and audience. For personal Gmail
   development, select **External**, keep the app in **Testing**, and add the
   Gmail accounts that will sign in as test users.
4. Add `https://www.googleapis.com/auth/gmail.modify` under Data Access.
5. Create an OAuth client with application type **Desktop app**.
6. Download the JSON and save it as `config/credentials.json`.

Maildesk opens the system browser for consent and receives the authorization
through a loopback callback. Gmail passwords are entered only on Google's sign-in
page. Public distribution requires the applicable Google verification; see
[Gmail authorization scopes](https://developers.google.com/workspace/gmail/api/auth/scopes)
and the [desktop OAuth flow](https://developers.google.com/identity/protocols/oauth2/native-app).

Saved tokens live under `config/tokens`. Disconnect clears local authorization.
Use the same Google account when returning to its existing cached mailbox.

## Configuration

Environment variables override `config/app.properties`:

| Environment variable | Property |
| --- | --- |
| `DB_URL` | `db.url` |
| `DB_USER` | `db.user` |
| `DB_PASSWORD` | `db.password` |
| `GMAIL_CREDENTIALS` | `gmail.credentials` |
| `GMAIL_TOKEN_DIR` | `gmail.tokenDir` |

Paths in the properties file are relative to the project directory unless absolute.
If no credential path is configured, discovery checks `config/credentials.json`,
`config/client_secret.json`, `credentials.json`, `client_secret.json`, and
`client_secrets.json`, in that order. A missing explicit path produces a setup
error. Use Desktop app JSON; Web application credentials are rejected.

The packaged compatibility launcher also works:
`java -cp target/maildesk.jar com.emailapp.App`.

## Verify the installation

```powershell
.\scripts\run-app.ps1 -NoBuild --check-setup
.\scripts\check.ps1 -MySql
```

The setup check reports OAuth file availability and database connectivity without
printing credentials. The MySQL test uses a temporary fixture account. To inspect
persisted records in MySQL Workbench:

```sql
USE email_management;
SHOW TABLES;
SELECT COUNT(*) AS cached_messages FROM messages;
SELECT gmail_message_id, score, suspected_spam FROM spam_results;
```

For a live acceptance check, connect a test account, compare the sample with
Gmail, refresh to verify duplicate-free storage, and search a cached subject.
Use team-controlled messages to test sending, read status, and spam corrections.
Reconnect after restarting to verify account isolation and persistence.

## Troubleshooting

| Symptom | Action |
| --- | --- |
| Missing OAuth JSON | Check `gmail.credentials` and download a Desktop app client. |
| Google blocks sign-in | Check the selected project's consent configuration and test-user list. |
| Gmail permission missing | Disconnect, reconnect, and grant the requested Gmail permission. |
| Temporary Gmail rate limit | Reads retry with bounded backoff; click Refresh later if the limit persists. |
| MySQL unavailable | Start MySQL and check URL, login, and database privileges. |
| Java compiler missing | Install JDK 17 or newer and configure `JAVA_HOME` or `PATH`. |

A refresh failure preserves the signed-in session and previous cached messages.
Sending and label changes are not automatically replayed after rate/server errors.
