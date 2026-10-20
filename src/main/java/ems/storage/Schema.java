package ems.storage;

/** Keep table definitions aligned with sql/schema.sql. Database creation is an installation step. */
final class Schema {
    private Schema() { }
    static final String[] TABLES = {
        """
        CREATE TABLE IF NOT EXISTS accounts (
            account_id BIGINT AUTO_INCREMENT PRIMARY KEY,
            email_address VARCHAR(254) NOT NULL UNIQUE
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS messages (
            account_id BIGINT NOT NULL,
            gmail_message_id VARCHAR(128) NOT NULL,
            sender_email VARCHAR(320) NOT NULL,
            subject TEXT NOT NULL,
            body_text LONGTEXT NOT NULL,
            received_at DATETIME(6) NOT NULL,
            is_read BOOLEAN NOT NULL DEFAULT TRUE,
            PRIMARY KEY (account_id, gmail_message_id),
            FOREIGN KEY (account_id) REFERENCES accounts(account_id)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS message_recipients (
            account_id BIGINT NOT NULL,
            gmail_message_id VARCHAR(128) NOT NULL,
            recipient_email VARCHAR(320) NOT NULL,
            PRIMARY KEY (account_id, gmail_message_id, recipient_email),
            FOREIGN KEY (account_id, gmail_message_id) REFERENCES messages(account_id, gmail_message_id) ON DELETE CASCADE
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS message_labels (
            account_id BIGINT NOT NULL,
            gmail_message_id VARCHAR(128) NOT NULL,
            gmail_label_id VARCHAR(128) NOT NULL,
            PRIMARY KEY (account_id, gmail_message_id, gmail_label_id),
            FOREIGN KEY (account_id, gmail_message_id) REFERENCES messages(account_id, gmail_message_id) ON DELETE CASCADE
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS spam_results (
            account_id BIGINT NOT NULL,
            gmail_message_id VARCHAR(128) NOT NULL,
            score INT NOT NULL,
            suspected_spam BOOLEAN NOT NULL,
            reasons TEXT NOT NULL,
            analyzed_at DATETIME(6) NOT NULL,
            PRIMARY KEY (account_id, gmail_message_id),
            FOREIGN KEY (account_id, gmail_message_id) REFERENCES messages(account_id, gmail_message_id) ON DELETE CASCADE
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS spam_feedback (
            account_id BIGINT NOT NULL,
            gmail_message_id VARCHAR(128) NOT NULL,
            user_marked_spam BOOLEAN NOT NULL,
            corrected_at DATETIME(6) NOT NULL,
            PRIMARY KEY (account_id, gmail_message_id),
            FOREIGN KEY (account_id) REFERENCES accounts(account_id)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS blocked_senders (
            account_id BIGINT NOT NULL,
            sender_email VARCHAR(320) NOT NULL,
            PRIMARY KEY (account_id, sender_email),
            FOREIGN KEY (account_id) REFERENCES accounts(account_id)
        )
        """
    };
}
