-- MySQL Schema for Maildesk Email Management System

CREATE DATABASE IF NOT EXISTS email_app;
USE email_app;

-- 1. Connected Gmail accounts
CREATE TABLE IF NOT EXISTS accounts (
    account_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    email_address VARCHAR(255) NOT NULL UNIQUE
);

-- 2. Cached email messages
CREATE TABLE IF NOT EXISTS messages (
    account_id BIGINT NOT NULL,
    gmail_message_id VARCHAR(255) NOT NULL,
    sender_email VARCHAR(255) NOT NULL,
    subject TEXT,
    body_text LONGTEXT,
    received_at TIMESTAMP NOT NULL,
    is_read BOOLEAN DEFAULT FALSE,
    PRIMARY KEY (account_id, gmail_message_id),
    CONSTRAINT fk_messages_account FOREIGN KEY (account_id) REFERENCES accounts (account_id) ON DELETE CASCADE
);

-- 3. Recipient addresses for each message
CREATE TABLE IF NOT EXISTS message_recipients (
    account_id BIGINT NOT NULL,
    gmail_message_id VARCHAR(255) NOT NULL,
    recipient_email VARCHAR(255) NOT NULL,
    PRIMARY KEY (account_id, gmail_message_id, recipient_email),
    CONSTRAINT fk_recipients_message FOREIGN KEY (account_id, gmail_message_id) REFERENCES messages (account_id, gmail_message_id) ON DELETE CASCADE
);

-- 4. Gmail labels associated with each message (INBOX, SENT, SPAM, UNREAD, etc.)
CREATE TABLE IF NOT EXISTS message_labels (
    account_id BIGINT NOT NULL,
    gmail_message_id VARCHAR(255) NOT NULL,
    gmail_label_id VARCHAR(255) NOT NULL,
    PRIMARY KEY (account_id, gmail_message_id, gmail_label_id),
    CONSTRAINT fk_labels_message FOREIGN KEY (account_id, gmail_message_id) REFERENCES messages (account_id, gmail_message_id) ON DELETE CASCADE
);

-- 5. Local spam detection results
CREATE TABLE IF NOT EXISTS spam_results (
    account_id BIGINT NOT NULL,
    gmail_message_id VARCHAR(255) NOT NULL,
    score INT NOT NULL,
    suspected_spam BOOLEAN NOT NULL,
    reasons TEXT,
    analyzed_at TIMESTAMP NOT NULL,
    PRIMARY KEY (account_id, gmail_message_id),
    CONSTRAINT fk_spam_message FOREIGN KEY (account_id, gmail_message_id) REFERENCES messages (account_id, gmail_message_id) ON DELETE CASCADE
);

-- 6. User feedback corrections (Mark as spam / Not spam)
CREATE TABLE IF NOT EXISTS spam_feedback (
    account_id BIGINT NOT NULL,
    gmail_message_id VARCHAR(255) NOT NULL,
    user_marked_spam BOOLEAN NOT NULL,
    corrected_at TIMESTAMP NOT NULL,
    PRIMARY KEY (account_id, gmail_message_id),
    CONSTRAINT fk_feedback_message FOREIGN KEY (account_id, gmail_message_id) REFERENCES messages (account_id, gmail_message_id) ON DELETE CASCADE
);

-- 7. Blocked senders per account
CREATE TABLE IF NOT EXISTS blocked_senders (
    account_id BIGINT NOT NULL,
    sender_email VARCHAR(255) NOT NULL,
    PRIMARY KEY (account_id, sender_email),
    CONSTRAINT fk_blocked_account FOREIGN KEY (account_id) REFERENCES accounts (account_id) ON DELETE CASCADE
);
