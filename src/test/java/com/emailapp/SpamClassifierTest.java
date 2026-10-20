package com.emailapp;

import com.emailapp.spam.SpamClassifier;
import ems.model.EmailMessage;
import ems.model.SpamResult;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpamClassifierTest {
    private final SpamClassifier classifier = new SpamClassifier();

    private EmailMessage message(String subject, String body, Set<String> labels) {
        return new EmailMessage(1, "message", "sender@example.com", List.of("me@example.com"),
                subject, body, Instant.parse("2026-10-20T10:15:00Z"), labels);
    }

    @Test void ordinaryMailNeedsNoDatabaseAndIsNotSpam() {
        SpamResult result = classifier.analyze(message("Meeting notes", "See you on Tuesday.", Set.of("INBOX")));
        assertEquals(0, result.getScore());
        assertFalse(result.isSuspectedSpam());
    }

    @Test void oneCommonPhraseDoesNotFlagLegitimateAccountMail() {
        SpamResult result = classifier.analyze(message("Please verify your account", "Welcome to our service.", Set.of("INBOX")));
        assertEquals(20, result.getScore());
        assertFalse(result.isSuspectedSpam());
    }

    @Test void multipleIndependentSignalsCanReachSpamThreshold() {
        SpamResult result = classifier.analyze(message("URGENT: Claim your prize", "You have won! Verify your account now. "
                + "https://one.example https://two.example https://three.example https://four.example https://five.example", Set.of("INBOX")));
        assertTrue(result.isSuspectedSpam());
        assertTrue(result.getScore() >= 50);
        assertTrue(result.getReasons().contains("Message contains many links"));
    }

    @Test void gmailSpamLabelDoesNotInfluenceOurClassifier() {
        SpamResult result = classifier.analyze(message("Meeting notes", "See you on Tuesday.", Set.of("SPAM")));
        assertEquals(0, result.getScore());
        assertFalse(result.isSuspectedSpam());
    }

    @Test void blockedSenderIsFlaggedWithReason() {
        SpamResult result = classifier.analyze(message("Hello", "Hello", Set.of("INBOX")), true);
        assertTrue(result.isSuspectedSpam());
        assertTrue(result.getReasons().contains("Sender is blocked"));
    }

    @Test void repeatedSinglePhraseDoesNotAccumulate() {
        SpamResult result = classifier.analyze(message("Hello", "you have won you have won you have won", Set.of("INBOX")));
        assertEquals(20, result.getScore());
    }
}
