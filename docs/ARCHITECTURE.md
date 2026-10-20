# Architecture

```text
Swing interface
  -> EmailService / GmailEmailService
       -> GmailGateway / GmailApiClient -> Gmail REST API
            -> GmailAuth -> Google OAuth + local token store
       -> MailStore / JdbcMailStore -> MySQL
            -> SpamClassifier -> rule scores and saved feedback
```

## Application flow

`ems.App` loads configuration and creates the production service. The compatible
`com.emailapp.App` entry point delegates to it. The Swing interface calls services
through background workers and updates components on the event dispatch thread.

Google authorization uses PKCE, a random state value, and a loopback callback.
The token store restricts file access and supports token refresh. Tokens are
excluded from the database and version control.

Connection and Refresh request up to six recent messages each from Gmail Inbox,
Sent, and Spam. Message IDs are deduplicated and imports are capped at 18 unique
messages. The service transactionally replaces the local sample. This operation
does not delete Gmail messages. Account IDs scope all cached messages and feedback.

Gmail Spam uses Gmail's `SPAM` label. Suspected Spam shows local rule-based review
results for received messages outside Gmail Spam. A score is an explanation of
matched rules, not a probability. Saved user corrections override automatic
classification. Only explicit read, send, or spam actions change Gmail state.

## Storage

| Table | Stores |
| --- | --- |
| `accounts` | Connected Gmail account identifiers |
| `messages` | Cached message content and timestamps |
| `message_recipients` | Recipient addresses |
| `message_labels` | Gmail label IDs |
| `spam_results` | Scores, explanations, and analysis timestamps |
| `spam_feedback` | Account-scoped user corrections |
| `blocked_senders` | Account-scoped blocked sender addresses |

Messages use `(account_id, gmail_message_id)` as their composite key. Related
message writes and sample replacement use transactions. Feedback survives removal
of a message from the local sample. The SQL definitions are in `sql/schema.sql`.

## Verification boundaries

JUnit covers OAuth callbacks, HTTP handling, MIME parsing, service behavior,
transactional storage, account isolation, and spam rules. Headless Swing checks
exercise the interface through its service contract. Default integration tests
use local HTTP fixtures and H2; a separate opt-in test exercises MySQL.
