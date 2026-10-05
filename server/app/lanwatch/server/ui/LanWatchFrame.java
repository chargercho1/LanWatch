package app.lanwatch.server.ui;

import app.lanwatch.Device;
import app.lanwatch.Protocol;
import app.lanwatch.server.Registry;
import app.lanwatch.server.Scanner;
import app.lanwatch.server.Server;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * The window.
 *
 * One list of devices, a column of detail on the right, and a header that says where the phones
 * should point. The list is fed from the registry rather than from the network directly, so an
 * update arriving from an agent and one arriving from the sweep both reach the screen the same way.
 */
public final class LanWatchFrame extends JFrame {
    private final Registry registry;
    private final Server server;
    private final Scanner scanner;

    private final DefaultListModel<Device> model = new DefaultListModel<Device>();
    private final JList<Device> list = new JList<Device>(model);
    private final DeviceCard.Detail detail = new DeviceCard.Detail();
    private final JTextField search = new PlaceholderField("фильтр по имени или адресу");
    private final StatTile total = new StatTile("устройств");
    private final StatTile agents = new StatTile("агентов на связи");
    private final StatTile low = new StatTile("заряд ≤ 20%");
    private final JLabel address = new JLabel();
    private final JLabel status = new JLabel();
    private JPanel head;
    private final JButton scan = new JButton("Сканировать сеть");

    private List<Device> all = new ArrayList<Device>();
    private Timer refresh;

    public LanWatchFrame(Registry registry, Server server, Scanner scanner) {
        this.registry = registry;
        this.server = server;
        this.scanner = scanner;
        setTitle("LanWatch — устройства в сети");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(1180, 760);
        setMinimumSize(new Dimension(900, 560));
        setLocationRelativeTo(null);

        build();
        wire();
        reload();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutDown();
            }
        });
    }

    private void build() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.BACKGROUND);
        root.add(header(), BorderLayout.NORTH);

        JPanel centre = new JPanel(new BorderLayout());
        centre.setBackground(Theme.BACKGROUND);
        centre.add(listPane(), BorderLayout.CENTER);
        centre.add(detail, BorderLayout.EAST);
        root.add(centre, BorderLayout.CENTER);
        root.add(footer(), BorderLayout.SOUTH);

        setContentPane(root);
        Timer diag = new Timer(1200, new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                System.err.println("DIAG frame=" + getWidth() + "x" + getHeight()
                        + " content=" + getContentPane().getWidth()
                        + " head=" + (head == null ? -1 : head.getWidth())
                        + " uiScale=" + System.getProperty("sun.java2d.uiScale"));
                ((Timer) e.getSource()).stop();
            }
        });
        diag.setRepeats(false);
        diag.start();
    }

    private JComponent header() {
        final JPanel head = new JPanel();
        this.head = head;
        head.setLayout(new BorderLayout(0, 12));
        head.setBackground(Theme.BACKGROUND);
        head.setBorder(BorderFactory.createEmptyBorder(20, 22, 16, 22));

        JPanel titles = new JPanel(new GridLayout(0, 1));
        titles.setOpaque(false);
        JLabel name = new JLabel("LanWatch");
        name.setFont(Theme.medium(26));
        name.setForeground(Theme.TEXT);
        titles.add(name);

        address.setFont(Theme.regular(13));
        address.setForeground(Theme.TEXT_DIM);
        titles.add(address);

        head.add(titles, BorderLayout.NORTH);

        JPanel stats = new JPanel(new GridLayout(1, 3, 10, 0));
        stats.setOpaque(false);
        stats.add(total);
        stats.add(agents);
        stats.add(low);
        head.add(stats, BorderLayout.CENTER);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        controls.setOpaque(false);
        controls.add(search);
        controls.add(Gap.of(8));
        controls.add(scan);
        head.add(controls, BorderLayout.SOUTH);

        search.setPreferredSize(new Dimension(320, 38));
        style(search);
        search.setFont(Theme.regular(13));
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { reload(); }
            public void removeUpdate(DocumentEvent e) { reload(); }
            public void changedUpdate(DocumentEvent e) { reload(); }
        });

        style(scan);
        scan.setForeground(Theme.ACCENT_TEXT);
        scan.setBackground(Theme.ACCENT);
        scan.setFont(Theme.medium(13));
        scan.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        scan.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                startScan();
            }
        });
        return head;
    }

    private JComponent listPane() {
        list.setBackground(Theme.BACKGROUND);
        list.setCellRenderer(new DeviceCard.Renderer());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setSelectionBackground(Theme.SURFACE_HIGH);
        list.setFixedCellHeight(DeviceCard.ROW_HEIGHT);
        list.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBackground(Theme.BACKGROUND);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(DeviceCard.ROW_HEIGHT);
        styleScrollBar(scroll.getVerticalScrollBar());
        return scroll;
    }

    private JComponent footer() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(Theme.SURFACE);
        bar.setBorder(BorderFactory.createEmptyBorder(9, 22, 9, 22));
        status.setFont(Theme.regular(12));
        status.setForeground(Theme.TEXT_DIM);
        bar.add(status, BorderLayout.WEST);
        return bar;
    }

    private void wire() {
        registry.addListener(new Registry.Listener() {
            @Override
            public void onChanged(List<Device> snapshot) {
                all = snapshot;
                // Registry callbacks come from socket and sweep threads; Swing is not thread safe.
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        reload();
                    }
                });
            }
        });

        list.addListSelectionListener(new javax.swing.event.ListSelectionListener() {
            @Override
            public void valueChanged(javax.swing.event.ListSelectionEvent e) {
                if (e.getValueIsAdjusting()) return;
                detail.show(list.getSelectedValue(), System.currentTimeMillis());
            }
        });

        // A second timer for the clock in the detail panel: nothing else changes when only the
        // seconds pass, and repainting every row for that would be waste.
        refresh = new Timer(1000, new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                server.prune();
                detail.show(list.getSelectedValue(), System.currentTimeMillis());
                status.setText(statusText());
            }
        });
        refresh.start();
    }

    /** Re-fills the list from the current registry, honouring the filter. */
    private void reload() {
        model.clear();
        String query = search.getText().toLowerCase().trim();
        boolean filtering = query.length() > 0;

        for (Device device : all) {
            if (filtering && !matches(device, query)) continue;
            model.addElement(device);
        }

        long now = System.currentTimeMillis();
        total.setValue(model.getSize());
        agents.setValue(registry.agentCount());
        low.setValue(registry.lowBatteryCount(20));
        address.setText("сервер слушает " + Server.advertiseAddress() + ":" + Protocol.PORT
                + "   ·   телефон подключается сам, если указать этот адрес");
        status.setText(statusText());
        detail.show(list.getSelectedValue(), now);
        list.repaint();
    }

    private boolean matches(Device device, String query) {
        return contains(device.describe(), query) || contains(device.ip, query)
                || contains(device.mac, query) || contains(device.model, query)
                || contains(device.transport, query) || contains(device.name, query);
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase().indexOf(needle) >= 0;
    }

    private String statusText() {
        if (scanner.isRunning()) return "идёт сканирование сети…";
        if (!scanner.isLastSweepCredible() && scanner.lastSweepProbed() > 0) {
            return "сеть отвечает на " + scanner.lastSweepFound() + " из "
                    + scanner.lastSweepProbed()
                    + " адресов — это неправда, поэтому сканирование отброшено;"
                    + " телефоны с агентом по-прежнему видны";
        }
        int silent = registry.silentAgentCount(System.currentTimeMillis());
        StringBuilder out = new StringBuilder();
        out.append("устройств: ").append(registry.size());
        out.append("   ·   агентов: ").append(registry.agentCount());
        if (silent > 0) out.append(" (молчат: ").append(silent).append(")");
        out.append("   ·   обновление каждые 20 с");
        return out.toString();
    }

    private void startScan() {
        if (scanner.isRunning()) return;
        scan.setEnabled(false);
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String local = Scanner.localAddress();
                    scanner.runOnce(local, 24, 300);
                } finally {
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            scan.setEnabled(true);
                        }
                    });
                }
            }
        }, "lanwatch-scan");
        worker.setDaemon(true);
        worker.start();
    }

    private void shutDown() {
        refresh.stop();
        server.stop();
        dispose();
        System.exit(0);
    }

    // --- small helpers ---------------------------------------------------------

    private void style(JComponent view) {
        view.setOpaque(false);
        view.setBackground(Theme.SURFACE);
        view.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.OUTLINE),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));
    }

    private void styleScrollBar(JScrollBar bar) {
        bar.setUnitIncrement(DeviceCard.ROW_HEIGHT);
        bar.setOpaque(false);
        bar.setBackground(Theme.BACKGROUND);
        bar.setForeground(Theme.SURFACE_HIGH);
    }

    /** A number with a caption under it. */
    private static final class StatTile extends JComponent {
        private final String caption;
        private String value = "0";

        StatTile(String caption) {
            this.caption = caption;
            setPreferredSize(new Dimension(120, 62));
        }

        void setValue(int value) {
            this.value = String.valueOf(value);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.prepare((Graphics2D) g.create());
            try {
                Theme.fillRound(g2, 0, 0, getWidth(), getHeight(), Theme.RADIUS, Theme.SURFACE);
                g2.setFont(Theme.medium(22));
                g2.setColor(Theme.TEXT);
                g2.drawString(value, 16, 30);
                g2.setFont(Theme.regular(12));
                g2.setColor(Theme.TEXT_FAINT);
                g2.drawString(Theme.clip(g2, caption, getWidth() - 32), 16, 48);
            } finally {
                g2.dispose();
            }
        }
    }

    /** A fixed-width gap between two controls. */
    private static final class Gap {
        static JComponent of(int width) {
            JPanel panel = new JPanel();
            panel.setOpaque(false);
            panel.setPreferredSize(new Dimension(width, 1));
            return panel;
        }
    }

    /** A text field that shows a caption until something is typed into it. */
    private static final class PlaceholderField extends JTextField {
        private final String placeholder;

        PlaceholderField(String placeholder) {
            this.placeholder = placeholder;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (getText().length() > 0) return;
            Graphics2D g2 = Theme.prepare((Graphics2D) g.create());
            try {
                g2.setFont(getFont());
                g2.setColor(Theme.TEXT_FAINT);
                g2.drawString(placeholder, getInsets().left + 1, getInsets().top + 1
                        + g2.getFontMetrics().getAscent());
            } finally {
                g2.dispose();
            }
        }
    }
}