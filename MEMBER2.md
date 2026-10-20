# Member 2: Gmail Connection and Business Logic

This module provides the Google OAuth 2.0 authorization, Gmail REST API v1 integration, production `EmailService` implementation, and bridges the Swing interface (Member 1) with MySQL database storage and the spam classifier (Member 3).

---

## Member 2 Ownership

- **`ems.model`**: Shared models (`Account`, `EmailMessage`, `Mailbox`, `SpamResult`)
- **`ems.exception`**: `AppException`
- **`ems.gmail`**:
  - `GmailAuth`: Google OAuth 2.0 loopback server, token refresh, and credential storage.
  - `GmailApiClient`: Gmail REST API v1 client via Java 17 `HttpClient` (profile, fetch, send, labels, read status).
  - `SimpleJson`: Dependency-free JSON parser and serializer.
- **`ems.service`**:
  - `EmailService`: Shared interface boundary with Member 1.
  - `GmailEmailService`: Production implementation connecting Gmail, MySQL repositories, and `SpamClassifier`.
- **`App.java`**: Main production entry point (`com.emailapp.App` and `ems.App`).

---

## Shared Models (`ems.model`)

1. **`Account`**:
   `long accountId`, `String emailAddress`
2. **`EmailMessage`**:
   `long accountId`, `String gmailMessageId`, `String senderEmail`, `List<String> recipientEmails`, `String subject`, `String bodyText`, `Instant receivedAt`, `Set<String> gmailLabelIds`
3. **`SpamResult`**:
   `int score`, `boolean suspectedSpam`, `List<String> reasons`
4. **`Mailbox`**:
   `INBOX`, `SENT`, `SPAM`, `SUSPECTED`
   - `SPAM`: Messages classified by Gmail as spam (`SPAM` label).
   - `SUSPECTED`: Messages flagged by local spam classifier (`suspectedSpam = true`) and not moved into Gmail's Spam.

---

## Production Setup & Google Authorization

### 1. Setting Up Google Cloud OAuth Credentials

1. Go to [Google Cloud Console](https://console.cloud.google.com/).
2. Create a project and enable the **Gmail API**.
3. Create OAuth 2.0 Client ID Credentials (Application Type: **Desktop Application**).
4. Set the following environment variables (or supply in `.env` / terminal):
   ```powershell
   $env:GMAIL_CLIENT_ID = "your-client-id.apps.googleusercontent.com"
   $env:GMAIL_CLIENT_SECRET = "your-client-secret"
   ```
5. When `connectGmail()` is invoked, Maildesk starts a temporary local listener on `http://localhost:8888/callback` and opens the browser for authorization.
6. The resulting access and refresh tokens are securely saved to `~/.email_app_tokens.json` for subsequent launches.

---

## Database Configuration (MySQL)

Configure database connection settings via environment variables (defaults to `localhost:3306/email_app`):

```powershell
$env:DB_URL = "jdbc:mysql://localhost:3306/email_app"
$env:DB_USER = "root"
$env:DB_PASSWORD = "your-password"
```

Initialize the schema using `sql/schema.sql`:
```powershell
mysql -u root -p < sql/schema.sql
```

---

## How to Build and Run

### Automated Headless Verification
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\check.ps1
```

### Run Member 1 Demo App (Fictional In-Memory Data)
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-demo.ps1
```

### Run Production Maildesk (Gmail + MySQL Integration)
From the project folder:
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1
java -cp build/classes com.emailapp.App
```
