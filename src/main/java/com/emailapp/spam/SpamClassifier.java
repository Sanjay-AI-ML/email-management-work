package com.emailapp.spam;

import ems.model.EmailMessage;
import ems.model.SpamResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Explainable local rules, independent of Gmail's own SPAM classification. */
public final class SpamClassifier {
    private static final Pattern LINK = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);
    private static final List<String> PHRASES = List.of(
            "claim your prize", "verify your account", "you have won",
            "urgent action required", "click here to claim");

    public SpamResult analyze(EmailMessage message) {
        return analyze(message, false);
    }

    /** The store supplies account-specific block information within its transaction. */
    public SpamResult analyze(EmailMessage message, boolean blockedSender) {
        int score = 0;
        List<String> reasons = new ArrayList<>();
        String sender = message.getSenderEmail().trim().toLowerCase(Locale.ROOT);
        String subject = message.getSubject().toLowerCase(Locale.ROOT);
        String body = message.getBodyText().toLowerCase(Locale.ROOT);
        if (blockedSender) {
            score += SpamRules.BLOCKED_SENDER;
            reasons.add("Sender is blocked");
        }
        String content = subject + " " + body;
        int phraseMatches = 0;
        for (String phrase : PHRASES) {
            if (content.contains(phrase)) {
                score += SpamRules.SUSPICIOUS_PHRASE;
                reasons.add("Suspicious phrase: " + phrase);
                // Distinct signals accumulate; one phrase alone should not classify a message.
                if (++phraseMatches == 3) break;
            }
        }
        if (LINK.matcher(body).results().limit(5).count() >= 5) {
            score += SpamRules.MANY_LINKS;
            reasons.add("Message contains many links");
        }
        if (subject.contains("urgent") || subject.contains("act now")) {
            score += SpamRules.URGENT_SUBJECT;
            reasons.add("Urgent subject wording");
        }
        if (sender.isBlank()) reasons.add("Sender address is missing");
        return new SpamResult(score, score >= SpamRules.SPAM_THRESHOLD, reasons);
    }
}
