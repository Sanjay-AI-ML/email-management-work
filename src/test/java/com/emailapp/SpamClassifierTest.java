
package com.emailapp;

import com.emailapp.database.MessageRepository;
import com.emailapp.database.SpamFeedbackRepository;
import com.emailapp.database.SpamRepository;
import com.emailapp.model.EmailMessage;
import com.emailapp.model.SpamResult;
import com.emailapp.spam.SpamClassifier;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

public class SpamClassifierTest {

    public static void main(String[] args) {

        SpamClassifier classifier = new SpamClassifier();

        EmailMessage message = new EmailMessage(
                900001L,
                "test-message-001",
                "unknown@example.com",
                Collections.singletonList("user@example.com"),
                "URGENT: Claim your prize",
                "You have won! Verify your account now. "
                        + "Visit https://one.example https://two.example "
                        + "https://three.example https://four.example "
                        + "https://five.example",
                Instant.now(),
                new HashSet<>(Arrays.asList("INBOX", "IMPORTANT"))
        );

        MessageRepository messageRepository = new MessageRepository();

        // Save the message, recipients and labels
        try {
            messageRepository.save(message);
            messageRepository.saveRecipients(message);
            messageRepository.saveLabels(message);

            System.out.println("Email message saved successfully!");
            System.out.println("Recipients and labels saved successfully!");

        } catch (SQLException e) {
            System.err.println("Failed to save email: " + e.getMessage());
            e.printStackTrace();
        }

        // Analyze the email
        SpamResult result = classifier.analyze(message);

        System.out.println("Spam score: " + result.getScore());
        System.out.println("Suspected spam: "
                + result.isSuspectedSpam());
        System.out.println("Reasons: " + result.getReasons());

        SpamRepository repository = new SpamRepository();

        // Save the spam result
        try {
            repository.save(message, result);
            System.out.println("Spam result saved successfully!");
        } catch (SQLException e) {
            System.err.println("Failed to save spam result: "
                    + e.getMessage());
            e.printStackTrace();
        }

        // Retrieve the spam result
        try {
            SpamResult savedResult = repository.findByMessage(
                    900001L, "test-message-001");

            if (savedResult != null) {
                System.out.println("\n--- Retrieved Spam Result ---");
                System.out.println("Saved score: "
                        + savedResult.getScore());
                System.out.println("Saved suspected spam: "
                        + savedResult.isSuspectedSpam());
                System.out.println("Saved reasons: "
                        + savedResult.getReasons());
            } else {
                System.out.println("No saved spam result found.");
            }
        } catch (SQLException e) {
            System.err.println("Failed to retrieve spam result: "
                    + e.getMessage());
            e.printStackTrace();
        }

        // Save user feedback
        try {
            SpamFeedbackRepository feedbackRepository =
                    new SpamFeedbackRepository();

            feedbackRepository.saveFeedback(
                    900001L, "test-message-001", true);

            System.out.println("Spam feedback saved successfully!");
        } catch (SQLException e) {
            System.err.println("Failed to save feedback: "
                    + e.getMessage());
            e.printStackTrace();
        }

        // Retrieve all messages for the test account
        try {
            messageRepository.updateReadStatus(
        900001L,
        "test-message-001",
        true
);
            List<EmailMessage> messages =
                    messageRepository.findByAccount(900001L);

            System.out.println("\n--- Messages Retrieved from MySQL ---");
            System.out.println("Total messages: " + messages.size());

            for (EmailMessage email : messages) {
                System.out.println("Message ID: "
                        + email.getGmailMessageId());
                System.out.println("Sender: " + email.getSenderEmail());
                System.out.println("Subject: " + email.getSubject());
                System.out.println("Recipients: " + email.getRecipientEmails());
System.out.println("Labels: " + email.getGmailLabelIds());
                System.out.println("-----------------------------");
            }
        } catch (SQLException e) {
            System.err.println("Failed to retrieve messages: "
                    + e.getMessage());
            e.printStackTrace();
        }
    }
}
