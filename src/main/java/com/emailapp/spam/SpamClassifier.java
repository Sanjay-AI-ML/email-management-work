
package com.emailapp.spam;

import com.emailapp.model.EmailMessage;
import com.emailapp.model.SpamResult;
import com.emailapp.database.BlockedSenderRepository;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SpamClassifier {

    private final BlockedSenderRepository blockedSenderRepository =
            new BlockedSenderRepository();

    public SpamResult analyze(EmailMessage message) {

        int score = 0;
        List<String> reasons = new ArrayList<>();

        String sender = safe(message.getSenderEmail())
                .toLowerCase(Locale.ROOT).trim();

        String subject = safe(message.getSubject())
                .toLowerCase(Locale.ROOT);

        String body = safe(message.getBodyText())
                .toLowerCase(Locale.ROOT);

        // Rule 1: Check whether this sender is blocked
        if (!sender.isBlank()) {
            try {
                boolean blocked = blockedSenderRepository.isBlocked(
                        message.getAccountId(),
                        sender
                );

                if (blocked) {
                    score += SpamRules.BLOCKED_SENDER;
                    reasons.add("Sender is blocked");
                }

            } catch (SQLException e) {
                throw new IllegalStateException(
                        "Unable to check blocked senders for account "
                                + message.getAccountId(),
                        e
                );
            }
        }

        String content = subject + " " + body;

        // Rule 2: Suspicious phrases
        String[] suspiciousPhrases = {
            "claim your prize",
            "verify your account",
            "you have won",
            "urgent action required",
            "click here to claim"
        };

        for (String phrase : suspiciousPhrases) {
            if (content.contains(phrase)) {
                score += SpamRules.SUSPICIOUS_PHRASE;
                reasons.add("Suspicious phrase: " + phrase);
                break;
            }
        }

        // Rule 3: Many links
        Matcher matcher = Pattern.compile(
                "https?://\\S+",
                Pattern.CASE_INSENSITIVE
        ).matcher(body);

        int linkCount = 0;

        while (matcher.find()) {
            linkCount++;
        }

        if (linkCount >= 5) {
            score += SpamRules.MANY_LINKS;
            reasons.add("Message contains many links");
        }

        // Rule 4: Urgent subject wording
        if (subject.contains("urgent")
                || subject.contains("act now")) {

            score += SpamRules.URGENT_SUBJECT;
            reasons.add("Urgent subject wording");
        }

        // Missing sender information
        if (sender.isBlank()) {
            reasons.add("Sender address is missing");
        }

        boolean suspectedSpam =
                score >= SpamRules.SPAM_THRESHOLD;

        return new SpamResult(
                score,
                suspectedSpam,
                reasons
        );
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}