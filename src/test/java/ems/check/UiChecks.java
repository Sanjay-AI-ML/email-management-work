package ems.check;

import ems.demo.DemoEmailService;
import ems.exception.AppException;
import ems.model.Mailbox;
import ems.service.EmailService;
import ems.ui.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Exercises real Swing actions headlessly, with slow/failing service responses. */
public final class UiChecks {
    private static MailAppPanel panel;
    private static int assertions;
    private static Path previewDirectory;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--render")) {
            previewDirectory = Path.of("build", "previews");
            Files.createDirectories(previewDirectory);
        }
        DemoEmailService demo = new DemoEmailService();
        AtomicBoolean onEdt = new AtomicBoolean();
        AtomicBoolean failRefresh = new AtomicBoolean(true);
        AtomicBoolean failOpen = new AtomicBoolean();
        AtomicReference<CountDownLatch> refreshGate = new AtomicReference<>();
        EmailService service = (EmailService) Proxy.newProxyInstance(EmailService.class.getClassLoader(),
                new Class<?>[] {EmailService.class}, (proxy, method, arguments) -> {
                    if (SwingUtilities.isEventDispatchThread()) onEdt.set(true);
                    if (method.getName().equals("refresh")) {
                        if (failRefresh.get()) throw new AppException("Test connection unavailable. Try again.");
                        CountDownLatch gate = refreshGate.get();
                        if (gate != null && !gate.await(5, TimeUnit.SECONDS)) throw new AppException("Test timed out.");
                    }
                    if (method.getName().equals("openMessage") && failOpen.get()) {
                        throw new AppException("Test message unavailable.");
                    }
                    try { return method.invoke(demo, arguments); }
                    catch (InvocationTargetException exception) { throw exception.getCause(); }
                });

        edt(() -> {
            UiTheme.install(); panel = new MailAppPanel(service, true);
            panel.setSize(1360, 860); layout(panel); render(panel, "connect.png");
        });
        click("connectButton");
        edt(() -> {
            check(button("connectButton").isEnabled(), "Connect retry available after refresh failure");
            check(label("statusLabel").getText().contains("Test connection"), "Useful connection failure shown");
        });
        boolean cleaned = false;
        try { demo.getMessages(Mailbox.INBOX, ""); } catch (AppException expected) { cleaned = true; }
        check(cleaned, "Partial connection disconnected after initial refresh failure");
        failRefresh.set(false); click("connectButton");
        edt(() -> {
            check(table().getRowCount() == 8, "Connected Inbox shown");
            check(button("composeButton").isEnabled(), "Compose enabled after connect");
            layout(panel); render(panel, "inbox.png");
            table().setRowSelectionInterval(0, 0);
        });
        awaitIdle();
        edt(() -> {
            check(find(panel, "messageBody") != null, "Opening row displays body");
            check(!((MailTableModel) table().getModel()).isUnread(0), "Opened row updates read state");
            layout(panel); render(panel, "reader.png");
        });
        failOpen.set(true);
        edt(() -> table().setRowSelectionInterval(1, 1)); awaitIdle();
        edt(() -> {
            check(find(panel, "markSpamButton") == null, "Failed open cannot correct previous message");
            check(find(panel, "messageBody") == null, "Previous body cleared on failed open");
            check(label("statusLabel").getText().contains("unavailable"), "Message failure shown");
        });
        failOpen.set(false);
        edt(() -> table().setRowSelectionInterval(1, 1)); awaitIdle();
        edt(() -> check(find(panel, "messageBody") != null,
                "The same row can be retried after an opening failure"));
        click("suspectedButton");
        edt(() -> {
            check(table().getRowCount() == 2, "Suspected view shows local flags");
            table().setRowSelectionInterval(0, 0);
        }); awaitIdle();
        edt(() -> {
            check(find(panel, "spamReasons") != null, "Rule reasons displayed");
            layout(panel); render(panel, "suspected.png");
        });
        click("notSpamButton");
        edt(() -> check(table().getRowCount() == 1, "Not spam correction removes local review item"));
        click("spamButton");
        edt(() -> check(table().getRowCount() == 2, "Gmail Spam stays separate"));
        click("sentButton");
        edt(() -> {
            check(table().getRowCount() == 2, "Sent view works");
            check(table().getColumnName(0).equals("TO"), "Sent identifies recipient");
            table().setRowSelectionInterval(0, 0);
        }); awaitIdle();
        edt(() -> check(find(panel, "markSpamButton") == null, "Sent cannot trigger spam correction"));
        click("inboxButton");
        edt(() -> field("searchField").setText("no-such-message-1928")); click("searchButton");
        edt(() -> check(table().getRowCount() == 0, "Empty search result clears list"));
        edt(() -> field("searchField").setText("project")); click("searchButton");
        edt(() -> check(table().getRowCount() > 0, "Search recovers from empty results"));
        CountDownLatch gate = new CountDownLatch(1); refreshGate.set(gate);
        edt(() -> button("refreshButton").doClick());
        edt(() -> {
            check(panel.isBusy(), "Long-running refresh tracked");
            check(!button("disconnectButton").isEnabled(), "Session cannot change mid-operation");
            check(!button("refreshButton").isEnabled(), "Duplicate refresh disabled");
        });
        gate.countDown(); awaitIdle(); refreshGate.set(null);
        failRefresh.set(true); click("refreshButton"); failRefresh.set(false);
        edt(() -> {
            check(button("refreshButton").isEnabled(), "Refresh recovers after failure");
            check(label("statusLabel").getText().contains("unavailable"), "Refresh error shown");
            check(table().getRowCount() > 0, "Cached messages remain on refresh error");
        });
        composeChecks();
        readerLayoutChecks(demo);
        click("disconnectButton");
        edt(() -> {
            check(button("connectButton").isEnabled(), "Disconnect returns to connect screen");
            check(!button("composeButton").isEnabled(), "Disconnected compose disabled");
        });
        check(!onEdt.get(), "Every service operation stayed off the Swing EDT");
        System.out.println("PASS: " + assertions + " Swing interaction assertions (headless).");
        if (previewDirectory != null) System.out.println("Rendered previews to " + previewDirectory.toAbsolutePath());
    }

    private static void composeChecks() throws Exception {
        AtomicReference<ComposePanel.Draft> submitted = new AtomicReference<>();
        AtomicInteger submissions = new AtomicInteger();
        AtomicInteger cancellations = new AtomicInteger();
        edt(() -> {
            ComposePanel form = new ComposePanel("student@example.test", true, draft -> {
                submitted.set(draft); submissions.incrementAndGet();
            }, cancellations::incrementAndGet);
            form.setSize(660, 620);
            JButton send = (JButton) find(form, "sendButton");
            send.doClick(); check(submissions.get() == 0, "Blank compose cannot send");
            ((JTextField) find(form, "composeTo")).setText("maya@campus.test");
            ((JTextField) find(form, "composeSubject")).setText("Project review");
            ((JTextArea) find(form, "composeBody")).setText("Hi Maya,\n\nThe mailbox screens are ready to review.\n\nThanks!");
            form.showError(null);
            layout(form); render(form, "compose.png"); send.doClick();
            check(submissions.get() == 1 && submitted.get().subject().equals("Project review"), "Validated compose delivers draft");
            form.setBusy(true);
            form.getActionMap().get("sendDraft").actionPerformed(null);
            form.getActionMap().get("cancelCompose").actionPerformed(null);
            check(submissions.get() == 1 && cancellations.get() == 0, "Busy compose blocks keyboard duplicates and dismissal");
            form.setBusy(false); form.showError("Delivery failed. You can retry.");
            check(((JTextArea) find(form, "composeBody")).getText().contains("ready"), "Draft survives send failure");
            ((JButton) find(form, "cancelComposeButton")).doClick();
            check(cancellations.get() == 1, "Compose cancel callback works");
        });
    }

    private static void readerLayoutChecks(DemoEmailService demo) throws Exception {
        String paragraph = "A longer paragraph must wrap at the reader width without losing its last line. ";
        String message = paragraph.repeat(95) + "\n\nFINAL SIGNATURE — visible at the end.";
        demo.sendMessage("student@example.test", "Long reader layout regression ".repeat(5), message);
        click("sentButton");
        edt(() -> table().setRowSelectionInterval(0, 0)); awaitIdle();
        edt(() -> {
            for (int width : new int[] {1360, 1100}) {
                panel.setSize(width, 740);
                layout(panel);
                JTextArea body = (JTextArea) find(panel, "messageBody");
                JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, body);
                try {
                    java.awt.geom.Rectangle2D end = body.modelToView2D(body.getDocument().getLength());
                    check(end != null && end.getMaxY() <= body.getHeight() - body.getInsets().bottom,
                            "All wrapped body lines have allocated height at width " + width);
                    Rectangle ending = SwingUtilities.convertRectangle(body, end.getBounds(), scroll.getViewport().getView());
                    scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());
                    layout(scroll);
                    JViewport viewport = scroll.getViewport();
                    check(viewport.getViewPosition().y + viewport.getExtentSize().height >= ending.getMaxY(),
                            "The final body line is reachable by scrolling at width " + width);
                    check(scroll.getVerticalScrollBar().getMaximum() > viewport.getExtentSize().height,
                            "Long mail produces a vertical scroll range at width " + width);
                } catch (javax.swing.text.BadLocationException exception) {
                    throw new AssertionError(exception);
                }
            }
            panel.setSize(1360, 860); layout(panel);
        });
    }

    private static void click(String name) throws Exception {
        edt(() -> button(name).doClick()); awaitIdle();
    }

    private static void awaitIdle() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        AtomicBoolean working = new AtomicBoolean();
        do {
            edt(() -> working.set(panel.isBusy()));
            if (!working.get()) return;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("UI worker did not finish");
    }

    private static JButton button(String name) { return (JButton) required(name); }
    private static JLabel label(String name) { return (JLabel) required(name); }
    private static JTextField field(String name) { return (JTextField) required(name); }
    private static JTable table() { return (JTable) required("messageTable"); }
    private static Component required(String name) {
        Component value = find(panel, name);
        if (value == null) throw new AssertionError("Missing component: " + name);
        return value;
    }

    private static Component find(Component component, String name) {
        if (name.equals(component.getName())) return component;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                Component match = find(child, name);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) layout(nested);
        }
    }

    private static void render(JComponent component, String filename) {
        if (previewDirectory == null) return;
        BufferedImage image = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        component.paint(graphics); graphics.dispose();
        try { ImageIO.write(image, "png", previewDirectory.resolve(filename).toFile()); }
        catch (java.io.IOException exception) { throw new RuntimeException(exception); }
    }

    private static void check(boolean condition, String description) {
        assertions++;
        if (!condition) throw new AssertionError(description);
    }

    private static void edt(Runnable work) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) work.run();
        else SwingUtilities.invokeAndWait(work);
    }
}
