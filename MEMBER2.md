# Member 2: Gmail integration handoff

Maildesk's production entry point is `ems.App`. The app connects the existing
`EmailService` interface to browser-based Google OAuth, the Gmail REST API, JDBC
persistence, and Member 3's spam detector. Member 1's UI calls services from
background Swing workers so mailbox activity does not block the event thread.

Member 2's PR #3 is incorporated in this version. Its `GmailAuth` and
`GmailApiClient` names now identify the single production OAuth/API stack;
`com.emailapp.App` delegates to `ems.App`. `GmailEmailService.createDefault()`
centralizes configuration and JDBC wiring. `SimpleJson` retains the team's
helper API with strict parsing, and `MemberTwoChecks` runs automatically under
Maven. See [INTEGRATION.md](INTEGRATION.md) for the resolved differences.

## Local setup

Follow [README.md](README.md) to configure MySQL and download a Google Desktop
OAuth client to `config/credentials.json`. Save database settings only in the
ignored `config/app.properties`, or use the documented environment variables.
Build with `.\mvnw.cmd clean package`, then run
`.\scripts\run-app.ps1 -NoBuild --check-setup` to inspect local prerequisites.
This check must never print database passwords or OAuth tokens.

Launching the app and clicking Connect Gmail is the account owner's consent
step. Automated tests use fake tokens and HTTP responses; the repository does
not include a usable Google OAuth client or a pre-authorized Gmail account.

## Integration behavior

- Shared `ems.model` types remain the UI contract; repositories scope messages
  and classification feedback to the connected account.
- Gmail message IDs identify cached messages and prevent refresh duplicates.
- Reading removes Gmail's unread label. Sending uses Gmail's messages endpoint.
- Gmail Spam reflects Gmail labels; Suspected reflects our explainable rule
  score and persisted user corrections.
- Automatic analysis never changes Gmail labels. Mark as spam / Not spam are
  explicit user operations.
- MySQL failures and Gmail authorization/API failures become actionable
  `AppException` messages at the UI boundary.

## Automated verification

The combined suite passed 62 JUnit tests with no failures, errors, or skips,
plus 53 Member 1 contract/demo, 29 Member 2 contract, and 40 Swing assertions. This run
included the actual configured MySQL server; the setup check also connected
and initialized its schema successfully.

To repeat the full run after setting local database credentials:

```powershell
.\mvnw.cmd '-Dmaildesk.mysqlTest=true' test
```

Without that option, the one real MySQL test is intentionally skipped, and
the remaining checks use isolated H2/HTTP fixtures. The MySQL test creates a
random `.test` account, scopes fixture operations to that account, and removes
the fixture rows afterward. It uses a fake Gmail gateway, so no actual Gmail
email is sent. Google Desktop OAuth JSON remains the external setup requirement
before live Gmail authorization and delivery can be checked.

## Manual acceptance test with real credentials

Use two team-controlled Gmail test accounts and harmless text messages.

1. Run setup checking successfully against your MySQL server.
2. Connect account A, complete consent, and refresh. Compare visible messages
   with Gmail in a browser.
3. Refresh again and confirm cache rows/message lists are not duplicated.
4. Send from A to test account B and confirm actual arrival and A's Sent copy.
5. Open an unread inbox message and confirm Gmail shows it as read.
6. Search a distinctive subject/body from the cached messages.
7. Inspect reasons for a flagged fictional/test message. Confirm analysis alone
   did not move it in Gmail.
8. Mark a harmless test message as spam; compare Gmail Spam. Mark it not spam
   and compare the inbox. Refresh and confirm the saved correction persists.
9. Disconnect, connect account B, and confirm A's messages are not shown.
10. Restart the app, connect again through Google, and verify database persistence.
    Disconnect clears local tokens; this version does not promise automatic login.
11. Try an unavailable database or revoked authorization and verify a readable
    error and usable retry controls.

Attachments, rich-text composition, automatic scheduled synchronization, and
public OAuth verification are outside this first version. Spam scoring is a
rule score for review, not a measured probability or guaranteed detection.
