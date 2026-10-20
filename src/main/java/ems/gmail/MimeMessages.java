package ems.gmail;

import ems.exception.AppException;
import ems.model.EmailMessage;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;

/** RFC 822/MIME conversion. HTML is converted to inert text, never rendered or fetched. */
final class MimeMessages {
    private MimeMessages() {}

    static EmailMessage parse(long accountId, String id, String raw, Instant received,
            Set<String> labels) throws AppException {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(raw);
            MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()),
                    new ByteArrayInputStream(bytes));
            Address[] from = mime.getFrom();
            String sender = from == null || from.length == 0 ? "" : address(from[0]);
            List<String> recipients = new ArrayList<>();
            Address[] allRecipients = mime.getAllRecipients();
            if (allRecipients != null) {
                for (Address recipient : allRecipients) recipients.add(address(recipient));
            }
            Date sentDate = mime.getSentDate();
            Instant date = received != null ? received
                    : sentDate == null ? Instant.EPOCH : sentDate.toInstant();
            return new EmailMessage(accountId, id, sender, recipients,
                    mime.getSubject() == null ? "" : mime.getSubject(),
                    readText(mime, 0), date, labels);
        } catch (Exception e) {
            throw new AppException("Gmail returned a message that could not be read.", e);
        }
    }

    static String compose(String sender, String recipient, String subject, String body)
            throws AppException {
        try {
            MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()));
            mime.setFrom(validAddress(sender));
            mime.setRecipient(Message.RecipientType.TO, validAddress(recipient));
            if (subject == null || subject.contains("\r") || subject.contains("\n")) {
                throw new AppException("The subject must be a single line.");
            }
            mime.setSubject(subject, StandardCharsets.UTF_8.name());
            mime.setText(body == null ? "" : body, StandardCharsets.UTF_8.name());
            mime.setSentDate(new Date());
            mime.saveChanges();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            mime.writeTo(bytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException("Enter a valid sender and recipient email address.", e);
        }
    }

    private static InternetAddress validAddress(String value) throws Exception {
        if (value == null || value.contains("\r") || value.contains("\n")) {
            throw new AppException("Enter one valid recipient email address.");
        }
        InternetAddress[] addresses = InternetAddress.parse(value, true);
        if (addresses.length != 1) throw new AppException("Enter one recipient email address.");
        addresses[0].validate();
        return addresses[0];
    }

    private static String address(Address address) {
        return address instanceof InternetAddress internet ? internet.getAddress() : address.toString();
    }

    private static String readText(Part part, int depth) throws Exception {
        if (depth > 30 || Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())
                || (part.getFileName() != null && !part.getFileName().isBlank())) return "";
        if (part.isMimeType("text/plain")) return contentString(part);
        if (part.isMimeType("text/html")) return htmlToText(contentString(part));
        if (part.isMimeType("multipart/*")) {
            Multipart multi = (Multipart) part.getContent();
            // Prefer the text/plain alternative instead of displaying duplicate HTML text.
            if (part.isMimeType("multipart/alternative")) {
                String fallback = "";
                for (int i = 0; i < multi.getCount(); i++) {
                    Part child = multi.getBodyPart(i);
                    String text = readText(child, depth + 1);
                    if (child.isMimeType("text/plain") && !text.isBlank()) return text;
                    if (!text.isBlank()) fallback = text;
                }
                return fallback;
            }
            List<String> pieces = new ArrayList<>();
            for (int i = 0; i < multi.getCount(); i++) {
                String text = readText(multi.getBodyPart(i), depth + 1);
                if (!text.isBlank()) pieces.add(text);
            }
            return String.join("\n\n", pieces);
        }
        return "";
    }

    private static String contentString(Part part) throws Exception {
        Object content = part.getContent();
        return content instanceof String text ? text : "";
    }

    private static String htmlToText(String html) throws IOException {
        StringBuilder text = new StringBuilder();
        new ParserDelegator().parse(new StringReader(html), new HTMLEditorKit.ParserCallback() {
            private int hiddenDepth;
            @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
                if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE) hiddenDepth++;
                if (hiddenDepth == 0 && tag.isBlock()) newline();
            }
            @Override public void handleEndTag(HTML.Tag tag, int pos) {
                if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE) {
                    hiddenDepth = Math.max(0, hiddenDepth - 1);
                }
                if (hiddenDepth == 0 && tag.isBlock()) newline();
            }
            @Override public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
                if (tag == HTML.Tag.BR) newline();
            }
            @Override public void handleText(char[] data, int pos) {
                if (hiddenDepth == 0) text.append(data);
            }
            private void newline() {
                if (text.length() > 0 && text.charAt(text.length() - 1) != '\n') text.append('\n');
            }
        }, true);
        return text.toString().strip();
    }
}
