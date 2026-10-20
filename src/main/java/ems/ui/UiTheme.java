package ems.ui;

import java.awt.*;
import javax.swing.*;
import javax.swing.border.*;

/** Small, dependency-free style helpers shared by Member 1's screens. */
public final class UiTheme {
    public static final Color BACKGROUND = new Color(0xF4F6F8);
    public static final Color SURFACE = Color.WHITE;
    public static final Color TEXT = new Color(0x182C3C);
    public static final Color MUTED = new Color(0x667684);
    public static final Color ACCENT = new Color(0x117568);
    public static final Color BORDER = new Color(0xDFE5E9);
    public static final Color SIDEBAR = new Color(0x172C3C);
    public static final Color SELECTED = new Color(0xEAF4F1);

    private UiTheme() { }

    public static void install() {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {
            // Swing's installed look and feel remains usable.
        }
        Font base = font(Font.PLAIN, 13);
        for (String key : new String[] {"Label.font", "Button.font", "TextField.font",
                "TextArea.font", "Table.font", "TableHeader.font", "OptionPane.messageFont",
                "OptionPane.buttonFont", "ToolTip.font"}) {
            UIManager.put(key, base);
        }
        UIManager.put("Panel.background", BACKGROUND);
        UIManager.put("ScrollBar.width", 11);
        UIManager.put("TextField.selectionBackground", new Color(0xC4E4DE));
    }

    public static Font font(int style, int size) {
        return new Font("SansSerif", style, size);
    }

    public static JLabel label(String text, int style, int size, Color color) {
        JLabel label = new JLabel();
        label.putClientProperty("html.disable", Boolean.TRUE);
        label.setText(text);
        label.setFont(font(style, size));
        label.setForeground(color);
        return label;
    }

    public static JButton primaryButton(String text) {
        return button(text, ACCENT, Color.WHITE);
    }

    public static JButton secondaryButton(String text) {
        return button(text, SURFACE, TEXT);
    }

    private static JButton button(String text, Color background, Color foreground) {
        JButton button = new JButton(text);
        button.setFont(font(Font.BOLD, 13));
        button.setBackground(background);
        button.setForeground(foreground);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setFocusPainted(true);
        button.setBorder(new CompoundBorder(new LineBorder(
                background.equals(SURFACE) ? BORDER : background, 1, true),
                new EmptyBorder(10, 16, 10, 16)));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    public static JTextField field(String placeholder) {
        JTextField field = new JTextField() {
            @Override protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                if (getText().isEmpty() && !isFocusOwner()) {
                    Graphics2D g = (Graphics2D) graphics.create();
                    g.setFont(getFont());
                    g.setColor(MUTED);
                    g.drawString(placeholder, getInsets().left,
                            (getHeight() - g.getFontMetrics().getHeight()) / 2
                                    + g.getFontMetrics().getAscent());
                    g.dispose();
                }
            }
        };
        field.setFont(font(Font.PLAIN, 13));
        field.setForeground(TEXT);
        field.setBackground(SURFACE);
        field.setBorder(new CompoundBorder(new LineBorder(BORDER, 1, true),
                new EmptyBorder(10, 12, 10, 12)));
        field.getAccessibleContext().setAccessibleName(placeholder);
        return field;
    }

    static JPanel panel(java.awt.LayoutManager layout, Color color) {
        JPanel panel = new JPanel(layout);
        panel.setBackground(color);
        return panel;
    }
}
