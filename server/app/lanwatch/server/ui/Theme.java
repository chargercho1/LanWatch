package app.lanwatch.server.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.RoundRectangle2D;
import java.util.HashSet;
import java.util.Set;

/**
 * The look: one accent, tonal surfaces, rounded corners, one type scale.
 *
 * Swing has no equivalent of Android's dynamic colour, so the palette is fixed and hand-tuned to
 * the Material dark tones rather than pulled from a system setting. Everything the window draws
 * goes through this class, which is what keeps the screens consistent with each other and with
 * the phone agent's own skin.
 */
public final class Theme {
    public static final Color BACKGROUND = new Color(0x101014);
    public static final Color SURFACE = new Color(0x1A1A20);
    public static final Color SURFACE_HIGH = new Color(0x26262E);
    public static final Color SURFACE_HOVER = new Color(0x32323C);
    public static final Color OUTLINE = new Color(0x3A3A44);

    public static final Color ACCENT = new Color(0x9DD3FF);
    public static final Color ACCENT_TEXT = new Color(0x06243D);
    public static final Color ACCENT_SOFT = new Color(0x269DD3FF);

    public static final Color TEXT = new Color(0xF2F2F5);
    public static final Color TEXT_DIM = new Color(0xB6B6C2);
    public static final Color TEXT_FAINT = new Color(0x82828F);

    public static final Color GOOD = new Color(0x7AD48A);
    public static final Color WARN = new Color(0xFFC46B);
    public static final Color BAD = new Color(0xFF8A80);

    public static final int RADIUS = 18;
    public static final int RADIUS_SMALL = 10;

    private static final String FAMILY = pickFamily();

    private Theme() { }

    private static String pickFamily() {
        Set<String> available = new HashSet<String>();
        try {
            for (String name : GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getAvailableFontFamilyNames()) {
                available.add(name);
            }
        } catch (Throwable t) {
            // Headless or restricted: the default family is fine.
        }
        String[] preferred = {"Segoe UI Variable", "Segoe UI", "Inter", "Roboto", "Helvetica"};
        for (String name : preferred) {
            if (available.contains(name)) return name;
        }
        return Font.SANS_SERIF;
    }

    public static Font font(int size, int style) {
        return new Font(FAMILY, style, size);
    }

    public static Font regular(int size) {
        return font(size, Font.PLAIN);
    }

    public static Font medium(int size) {
        return font(size, Font.BOLD);
    }

    /** Anti-aliasing and sub-pixel text: without it every rounded corner looks chewed. */
    public static Graphics2D prepare(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return g;
    }

    public static void fillRound(Graphics2D g, int x, int y, int width, int height, int radius,
            Color color) {
        if (width <= 0 || height <= 0) return;
        g.setColor(color);
        g.fill(new RoundRectangle2D.Float(x, y, width, height, radius * 2f, radius * 2f));
    }

    public static void strokeRound(Graphics2D g, int x, int y, int width, int height, int radius,
            Color color) {
        if (width <= 0 || height <= 0) return;
        g.setColor(color);
        g.setStroke(new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, width - 1, height - 1,
                radius * 2f, radius * 2f));
    }

    /** The battery colour for a percentage: green, then amber under 40, red under 15. */
    public static Color batteryColor(int percent) {
        if (percent <= 15) return BAD;
        if (percent <= 40) return WARN;
        return GOOD;
    }

    /**
     * A battery drawn as a ring around its percentage.
     *
     * A number alone is easy to miss in a list of a dozen rows; the ring is readable at a glance
     * and shows the level without a second line of text.
     */
    public static void batteryRing(Graphics2D g, int x, int y, int size, int percent,
            boolean charging) {
        int thickness = Math.max(3, size / 9);
        int inset = thickness / 2 + 1;

        g.setColor(SURFACE_HOVER);
        g.setStroke(new BasicStroke(thickness, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Arc2D.Float(x + inset, y + inset, size - inset * 2, size - inset * 2,
                90, -360, Arc2D.OPEN));

        if (percent >= 0) {
            double sweep = -360.0 * Math.min(100, percent) / 100.0;
            g.setColor(batteryColor(percent));
            g.setStroke(new BasicStroke(thickness, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Arc2D.Float(x + inset, y + inset, size - inset * 2, size - inset * 2,
                    90, (float) sweep, Arc2D.OPEN));
        }

        String text = percent < 0 ? "—" : percent + "%";
        g.setFont(medium(Math.max(9, size / 4)));
        g.setColor(percent < 0 ? TEXT_FAINT : TEXT);
        centre(g, text, x, y, size);
    }

    /** A flat pill, used for tags like "заряжается" or "adb". */
    public static void pill(Graphics2D g, String text, int x, int y, Color background, Color color) {
        if (text == null || text.length() == 0) return;
        g.setFont(medium(10));
        int width = g.getFontMetrics().stringWidth(text) + 14;
        int height = 18;
        fillRound(g, x, y, width, height, height / 2, background);
        g.setColor(color);
        g.drawString(text, x + 7, y + 13);
    }

    public static int pillWidth(Graphics2D g, String text) {
        if (text == null || text.length() == 0) return 0;
        g.setFont(medium(10));
        return g.getFontMetrics().stringWidth(text) + 14;
    }

    /** Text clipped to a width with an ellipsis, which is what keeps a long hostname in its box. */
    public static String clip(Graphics2D g, String text, int width) {
        if (text == null) return "";
        if (g.getFontMetrics().stringWidth(text) <= width) return text;
        String tail = "…";
        int room = width - g.getFontMetrics().stringWidth(tail);
        if (room <= 0) return tail;
        int end = text.length();
        while (end > 0 && g.getFontMetrics().stringWidth(text.substring(0, end)) > room) end--;
        return text.substring(0, end) + tail;
    }

    private static void centre(Graphics2D g, String text, int x, int y, int size) {
        java.awt.FontMetrics metrics = g.getFontMetrics();
        g.drawString(text, x + (size - metrics.stringWidth(text)) / 2,
                y + (size + metrics.getAscent() - metrics.getDescent()) / 2);
    }

    public static Color blend(Color from, Color to, double amount) {
        double t = Math.max(0, Math.min(1, amount));
        return new Color(
                (int) Math.round(from.getRed() + (to.getRed() - from.getRed()) * t),
                (int) Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * t),
                (int) Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * t),
                (int) Math.round(from.getAlpha() + (to.getAlpha() - from.getAlpha()) * t));
    }
}