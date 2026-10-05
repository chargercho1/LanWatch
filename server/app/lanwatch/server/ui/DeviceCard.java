package app.lanwatch.server.ui;

import app.lanwatch.Device;

import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;

/**
 * How one device looks in the list, and how it looks when it is selected on the right.
 *
 * Both are painted by hand rather than assembled from Swing components: a row has to stay exactly
 * as tall and as spaced as the style says, and laying that out with nested panels fights the
 * layout manager instead of cooperating with it.
 */
public final class DeviceCard {

    /** Height of one row, including the gap the style asks for. */
    public static final int ROW_HEIGHT = 108;
    private static final int CARD_INSET = 6;
    private static final int RING = 58;

    private DeviceCard() { }

    /** The list renderer. */
    public static final class Renderer extends JComponent implements ListCellRenderer<Device> {
        private Device device;
        private boolean selected;
        private boolean focused;

        @Override
        public java.awt.Component getListCellRendererComponent(JList<? extends Device> list,
                Device value, int index, boolean isSelected, boolean cellHasFocus) {
            this.device = value;
            this.selected = isSelected;
            this.focused = cellHasFocus;
            setPreferredSize(new Dimension(10, ROW_HEIGHT));
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.prepare((Graphics2D) g.create());
            try {
                paint(g2, getWidth(), getHeight());
            } finally {
                g2.dispose();
            }
        }

        private void paint(Graphics2D g2, int width, int height) {
            if (device == null) return;
            int top = CARD_INSET;
            int h = height - CARD_INSET * 2;
            Color fill = selected || focused ? Theme.SURFACE_HIGH : Theme.SURFACE;

            Theme.fillRound(g2, CARD_INSET, top, width - CARD_INSET * 2, h, Theme.RADIUS, fill);
            if (selected || focused) {
                Theme.strokeRound(g2, CARD_INSET, top, width - CARD_INSET * 2, h, Theme.RADIUS,
                        Theme.OUTLINE);
            }

            int left = CARD_INSET + 18;
            int right = width - CARD_INSET - 18;

            // --- title ---------------------------------------------------------------
            g2.setFont(Theme.medium(15));
            g2.setColor(device.isAgent() ? Theme.TEXT : Theme.TEXT_DIM);
            int titleWidth = right - left - RING - 20;
            g2.drawString(Theme.clip(g2, device.describe(), titleWidth), left, top + 28);

            // --- address line --------------------------------------------------------
            g2.setFont(Theme.regular(12));
            g2.setColor(Theme.TEXT_FAINT);
            StringBuilder where = new StringBuilder(device.ip);
            if (device.mac != null && device.mac.length() > 0) where.append("  ·  ").append(device.mac);
            g2.drawString(Theme.clip(g2, where.toString(), titleWidth), left, top + 50);

            // --- tags ----------------------------------------------------------------
            int x = left;
            int y = top + 62;
            if (device.isAgent()) {
                x = tag(g2, x, y, "агент", Theme.ACCENT_SOFT, Theme.ACCENT);
            }
            if (device.charging) {
                x = tag(g2, x, y, "заряжается", Theme.ACCENT_SOFT, Theme.ACCENT);
            } else if (device.powerSave) {
                x = tag(g2, x, y, "экономия", Theme.SURFACE_HOVER, Theme.WARN);
            }
            if (device.transport != null && device.transport.length() > 0) {
                tag(g2, x, y, device.transport, Theme.SURFACE_HOVER, Theme.TEXT_DIM);
            }

            // --- battery -------------------------------------------------------------
            int ringX = right - RING;
            if (device.hasBattery()) {
                Theme.batteryRing(g2, ringX, top + (h - RING) / 2, RING, device.battery,
                        device.charging);
            } else {
                Theme.fillRound(g2, ringX, top + (h - RING) / 2, RING, RING, RING / 2,
                        Theme.SURFACE_HIGH);
                g2.setFont(Theme.regular(11));
                g2.setColor(Theme.TEXT_FAINT);
                String text = "нет данных";
                java.awt.FontMetrics m = g2.getFontMetrics();
                g2.drawString(text, ringX + (RING - m.stringWidth(text)) / 2,
                        top + h / 2 + 4);
            }
        }

        private int tag(Graphics2D g2, int x, int y, String text, Color background, Color color) {
            Theme.pill(g2, text, x, y, background, color);
            return x + Theme.pillWidth(g2, text) + 6;
        }
    }

    /** The panel on the right that expands whatever row is selected. */
    public static final class Detail extends JPanel {
        private Device device;
        private long now;

        public Detail() {
            setPreferredSize(new Dimension(320, 10));
            setOpaque(true);
        }

        public void show(Device device, long now) {
            this.device = device;
            this.now = now;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.prepare((Graphics2D) g.create());
            try {
                int w = getWidth();
                int h = getHeight();
                g2.setColor(Theme.BACKGROUND);
                g2.fillRect(0, 0, w, h);
                g2.setColor(Theme.OUTLINE);
                g2.drawLine(0, 0, 0, h);

                if (device == null) {
                    g2.setFont(Theme.regular(13));
                    g2.setColor(Theme.TEXT_FAINT);
                    g2.drawString("Выберите устройство", 22, 40);
                    return;
                }

                int x = 22;
                int y = 36;

                g2.setFont(Theme.medium(17));
                g2.setColor(Theme.TEXT);
                g2.drawString(Theme.clip(g2, device.describe(), w - 44), x, y);
                y += 22;
                g2.setFont(Theme.regular(12));
                g2.setColor(Theme.TEXT_FAINT);
                g2.drawString(device.ip, x, y);
                y += 30;

                if (device.hasBattery()) {
                    Theme.batteryRing(g2, x, y, 64, device.battery, device.charging);
                    int bx = x + 82;
                    g2.setFont(Theme.medium(14));
                    g2.setColor(Theme.TEXT);
                    g2.drawString(device.battery + "%", bx, y + 22);
                    g2.setFont(Theme.regular(12));
                    g2.setColor(device.charging ? Theme.ACCENT : Theme.TEXT_DIM);
                    g2.drawString(device.charging ? "заряжается" : "не заряжается", bx, y + 42);
                    y += 84;
                } else {
                    g2.setFont(Theme.regular(13));
                    g2.setColor(Theme.TEXT_FAINT);
                    g2.drawString("Заряд неизвестен — устройство не сообщает его.", x, y);
                    y += 28;
                }

                y = row(g2, x, y, w, "Состояние", device.isAgent() ? "агент на связи" : "найдено сканером");
                y = row(g2, x, y, w, "MAC", orDash(device.mac));
                y = row(g2, x, y, w, "Модель", orDash(device.model));
                y = row(g2, x, y, w, "Система", orDash(device.os));
                y = row(g2, x, y, w, "Сеть", orDash(device.transport));
                y = row(g2, x, y, w, "Открытые порты", orDash(device.ports));
                y = row(g2, x, y, w, "Версия агента", orDash(device.app));

                if (device.isAgent()) {
                    y = row(g2, x, y, w, "Экономия энергии",
                            device.powerSave ? "включена" : "выключена");
                    y = row(g2, x, y, w, "Экран", device.screenOn ? "горит" : "погашен");
                }

                long seconds = device.ageSeconds(now);
                row(g2, x, y, w, "Обновлено",
                        seconds < 5 ? "только что"
                                : seconds < 90 ? seconds + " с назад"
                                : (seconds / 60) + " мин назад");
            } finally {
                g2.dispose();
            }
        }

        private int row(Graphics2D g2, int x, int y, int width, String label, String value) {
            g2.setFont(Theme.regular(12));
            g2.setColor(Theme.TEXT_FAINT);
            g2.drawString(label, x, y);

            g2.setFont(Theme.regular(13));
            g2.setColor(Theme.TEXT_DIM);
            // Clip first, then align: measuring the full string would push a clipped value
            // off the right edge instead of shortening it.
            String shown = Theme.clip(g2, value, Math.max(20, width - x * 2 - 140));
            g2.drawString(shown, width - 22 - g2.getFontMetrics().stringWidth(shown), y);
            return y + 24;
        }

        private String orDash(String value) {
            return value == null || value.length() == 0 ? "—" : value;
        }
    }
}