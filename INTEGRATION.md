# Combined team handoff

The integration branch merges `main` at `b32cd0c`, including Member 2's PR #3,
with the Gmail/MySQL integration from PR #2. This is one application with one
production service, rather than independently configured team implementations.

| Area | Final implementation |
| --- | --- |
| Member 1 | Existing Swing screens and `EmailService`/`ems.model` contract |
| Member 2 | `GmailAuth`, `GmailApiClient`, `GmailEmailService`, compatible `com.emailapp.App`, contract checks, JDK discovery |
| Member 3 | Explainable `SpamClassifier`, blocked senders, persistent account-scoped messages and feedback |
| Integration | `AppConfig`, `JdbcMailStore`, MIME support, isolated HTTP/database tests, Maven packaging, Windows/Linux CI |

## Resolved overlap

- `GmailAuth` incorporates the tested desktop flow: random loopback port bound
  to 127.0.0.1, PKCE, callback state validation, refresh tokens, and an owner-only
  cache under `config/tokens`. The fixed-port, home-directory token implementation
  is replaced. Old token files are not imported; authorize again once.
- `GmailApiClient` is the shared `GmailGateway` implementation. It includes all
  mailbox pages, Spam, raw MIME parsing/composition, one authorization refresh
  retry, request timeouts, and errors that do not expose token/API response bodies.
  `GmailRestGateway` and `OAuthDesktopClient` have been consolidated into the
  Member 2 class names; there are no parallel production clients.
- `GmailEmailService.createDefault()` supplies the same configured Gmail client
  and MySQL store to either launcher. Injected constructors support the tests.
  Direct calls to Member 2's previous token-argument API methods should now use
  `GmailGateway` or `EmailService`; callers no longer pass raw OAuth tokens around.
- The final schema is `sql/schema.sql` for `email_management`; startup initializes
  tables automatically. Cache snapshots are transactional and feedback survives
  removal of a cached message. Gmail remains the source of truth.
- `SimpleJson` preserves the teammate's helper methods while rejecting malformed
  or incomplete JSON. Their 29 standalone assertions run in the JUnit suite.
- All PowerShell launchers source `scripts/initialize-java.ps1`. Maven resolves
  runtime dependencies; manually compiling only `build/classes` is no longer a
  supported production launch path.

## Moving from Member 2's earlier setup

1. Copy `config/app.properties.example` to the ignored `config/app.properties`
   and enter your local MySQL login. Defaults target `email_management`.
   An earlier `email_app` database is left intact; its cache is not migrated or
   deleted. Refresh repopulates the final database from Gmail. Existing local-only
   feedback/blocked sender rows in that older database need separate migration
   if you wish to retain them.
2. Use downloaded **Desktop app** OAuth JSON. The supported filename fallbacks
   are listed in the README. Configure `GMAIL_CREDENTIALS` for any other path.
   The old `GMAIL_ACCESS_TOKEN`, `GMAIL_CLIENT_ID`, `GMAIL_CLIENT_SECRET`, and
   `GOOGLE_CLIENT_*` alternatives are replaced by this single JSON configuration.
   A Web application OAuth client is rejected with a setup message.
3. Run `scripts/build.ps1`, then `scripts/run-app.ps1 -NoBuild --check-setup`.
4. Launch `scripts/run-app.ps1 -NoBuild` and click Connect Gmail. Both production
   entry points are supported by the dependency-bundled `target/maildesk.jar`.
5. Run `scripts/check.ps1 -MySql -Render` for local database and interface checks.

## Verified scope

On the integrated branch: 62 JUnit tests passed with zero failures/errors/skips,
including the configured real MySQL server. The contract/interface checks passed
53 + 29 + 40 assertions. Gmail/OAuth behavior is exercised with local HTTP
fixtures; no real message is sent by automated verification.

The local desktop OAuth file is configured and excluded from Git. Google account
consent and real Gmail delivery are still manual acceptance steps, described in
`MEMBER2.md`. Attachments, rich-text composition, scheduled synchronization, and
Google public-app verification remain outside this version's supported scope.

## Small real-Gmail sample

At the user's request, production imports are capped at 18 unique messages: up
to 6 recent Inbox, 6 Sent, and 6 Spam messages. Both initial connection and
Refresh use this cap. The bounded sample replaces only the local cache; it
does not delete mail in Gmail. The low-level gateway retains its paginated API
implementation for future expansion, but the production service uses the capped
folder windows. Search and spam review cover the cached sample.
