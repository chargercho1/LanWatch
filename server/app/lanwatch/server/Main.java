package app.lanwatch.server;

import app.lanwatch.Protocol;
import app.lanwatch.server.ui.LanWatchFrame;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Entry point for the desktop side.
 *
 * Starts the registry, the listening socket and the window, then hands the window to the event
 * thread and puts the subnet sweep on a timer of its own. The sweep is a scheduled task rather
 * than something the window drives, so it keeps running while a dialog or a redraw is in the way.
 */
public final class Main {
    private Main() { }

    public static void main(String[] args) {
        // The display scale is settled before the JVM starts, by the manifest on LanWatch.exe: Windows
        // shrinks any window whose process has not declared itself DPI aware, and the JVM has no
        // way to declare it. Left at Java's own default, the two agree and the window comes out
        // the size the layout asked for — forcing the scale by hand only unbalances them.
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");

        final Registry registry = new Registry();
        final Server server = new Server(registry, Protocol.PORT);
        final Scanner scanner = new Scanner(registry);

        try {
            server.start();
        } catch (Exception e) {
            System.err.println("не удалось занять порт " + Protocol.PORT + ": " + e.getMessage());
            System.err.println("похоже, LanWatch уже запущен — закройте прежнее окно");
            System.exit(2);
            return;
        }

        // Look native rather than like a Swing leftover from 2004.
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // The cross-platform look is acceptable.
        }

        schedule(registry, scanner);

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                LanWatchFrame frame = new LanWatchFrame(registry, server, scanner);
                frame.setVisible(true);
                screenshot(frame, args);
            }
        });
    }

    /**
     * Writes the window's own layout to a PNG, with --shot <file>.
     *
     * This asks Swing to paint itself, which is the layout as it was computed, without the
     * operating system's own window scaling in the way. It is how the interface was checked on a
     * machine whose display scale the JVM cannot negotiate, and it is useful for the same reason
     * when changing the design.
     */
    private static void screenshot(final LanWatchFrame frame, String[] args) {
        String path = null;
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--shot")) path = args[i + 1];
        }
        if (path == null) return;
        final String target = path;

        javax.swing.Timer timer = new javax.swing.Timer(2500, new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                ((javax.swing.Timer) e.getSource()).stop();
                java.awt.Container root = frame.getContentPane();
                int w = root.getWidth();
                int h = root.getHeight();
                java.awt.image.BufferedImage image =
                        new java.awt.image.BufferedImage(w, h,
                                java.awt.image.BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g = image.createGraphics();
                root.printAll(g);
                g.dispose();
                try {
                    javax.imageio.ImageIO.write(image, "png", new java.io.File(target));
                    System.out.println("SHOT " + target + " " + w + "x" + h);
                } catch (Exception ex) {
                    System.out.println("SHOT FAILED " + ex);
                }
                System.exit(0);
            }
        });
        timer.setRepeats(false);
        timer.start();
    }

    /** Runs a sweep at startup and then once per protocol period. */
    private static void schedule(final Registry registry, final Scanner scanner) {
        Runnable sweep = new Runnable() {
            @Override
            public void run() {
                if (scanner.isRunning()) return;
                Thread worker = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        scanner.runOnce(Scanner.localAddress(), 24, 300);
                    }
                }, "lanwatch-sweep");
                worker.setDaemon(true);
                worker.start();
            }
        };

        sweep.run();
        java.util.Timer timer = new java.util.Timer("lanwatch-period", true);
        timer.schedule(new java.util.TimerTask() {
            @Override
            public void run() {
                sweep.run();
                registry.expire(System.currentTimeMillis(), false);
            }
        }, Protocol.SCAN_PERIOD_MS, Protocol.SCAN_PERIOD_MS);
    }
}