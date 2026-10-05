package app.lanwatch.server;

import app.lanwatch.Address;
import app.lanwatch.Device;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Sweeps the local subnet and reports what answers.
 *
 * Discovery is deliberately modest: a parallel reachability probe decides whether anything is
 * there, then a handful of TCP connects say what kind of thing it is. That works on a plain
 * home network without any privileges, and unlike a ping sweep it also finds devices that ignore
 * ICMP — which on a modern phone is most of them.
 *
 * The address range is computed by {@link #sweep}, which is pure and covered by the self test.
 */
public final class Scanner {
    /** Ports worth one connect each: they tell a printer from a laptop from a TV. */
    private static final int[] PROBE_PORTS = {
            80, 443, 22, 445, 139, 23, 548, 631, 9100, 8009, 8080, 32400, 5000, 5555, 2869, 3389
    };

    /** Names for the ports above, so an unknown device at least says what it speaks. */
    static String serviceName(int port) {
        switch (port) {
            case 80: return "http";
            case 443: return "https";
            case 22: return "ssh";
            case 445: case 139: return "smb";
            case 23: return "telnet";
            case 548: return "afp";
            case 631: return "ipp";
            case 9100: return "jetdirect";
            case 8009: case 8080: return "web";
            case 32400: return "plex";
            case 5000: return "upnp";
            case 5555: return "adb";
            case 2869: return "chromecast";
            case 3389: return "rdp";
            default: return "port";
        }
    }

    private final Registry registry;
    private volatile ExecutorService pool;
    private volatile boolean running;
    private volatile int lastSweepProbed;
    private volatile int lastSweepFound;
    private volatile boolean lastSweepCredible = true;

    public Scanner(Registry registry) {
        this.registry = registry;
    }

    /** How many addresses the last sweep asked about, and how many answered. */
    public int lastSweepProbed() {
        return lastSweepProbed;
    }

    public int lastSweepFound() {
        return lastSweepFound;
    }

    /** False when the answer rate says the network stack is replying to everything. */
    public boolean isLastSweepCredible() {
        return lastSweepCredible;
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * The worker pool, created on first use and kept for the next sweep.
     *
     * A pool is shut down when idle here rather than per scan: the scheduler thread lives as long
     * as the window does, and a scan that shut the pool down would leave the next one with no
     * workers to run on.
     */
    private ExecutorService pool() {
        ExecutorService current = pool;
        if (current == null || current.isShutdown()) {
            synchronized (this) {
                if (pool == null || pool.isShutdown()) pool = Executors.newFixedThreadPool(32);
                current = pool;
            }
        }
        return current;
    }

    /** The address of this machine on the interface that has the default route, or "" if none. */
    /**
 * The interface whose subnet this machine shares with the phones.
 *
 * Wi-Fi wins over the default route. That ordering is not cosmetic: a machine with Docker, a VPN
 * or Hyper-V installed holds private addresses on those adapters, and the default route can well
 * point at one of them — which is a subnet no phone on the Wi-Fi belongs to.
 */
public static String localAddress() {
        List<InetAddress> wifi = new ArrayList<InetAddress>();
        List<InetAddress> rest = new ArrayList<InetAddress>();
        try {
            for (Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
                    nics != null && nics.hasMoreElements();) {
                NetworkInterface nic = nics.nextElement();
                try {
                    if (!nic.isUp() || nic.isLoopback()) continue;
                } catch (SocketException e) {
                    continue;
                }
                List<InetAddress> into = Address.isWifi(nic.getName()) ? wifi : rest;
                for (Enumeration<InetAddress> addresses = nic.getInetAddresses();
                        addresses.hasMoreElements();) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address) into.add(address);
                }
            }
        } catch (SocketException e) {
            // Fall through to the route probe below.
        }

        List<InetAddress> all = new ArrayList<InetAddress>(wifi);
        all.addAll(rest);
        String chosen = Address.choose(all);
        if (chosen.length() > 0) return chosen;

        // No usable interface: ask the routing table which address would be used to get out.
        try {
            Socket probe = new Socket();
            try {
                probe.connect(new InetSocketAddress("192.0.2.1", 9), 1);
                return probe.getLocalAddress().getHostAddress();
            } finally {
                probe.close();
            }
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Whether a sweep answered implausibly often, and so cannot be believed.
     *
     * A subnet where two thirds of the addresses reply does not exist; it means the network stack
     * answered for addresses that are not there. Some sandboxes, VPNs and virtual adapters do
     * exactly this, and a list claiming 254 devices is worse than no list: the user is told their
     * network is full of hardware that is not.
     */
    public static boolean looksImplausible(int found, int probed) {
        if (probed < 32) return true;              // too small a sample to judge
        return found * 10 <= probed * 6;           // sixty percent answering is the ceiling
    }

    /**
     * Every address in the subnet of {@code local}, except the machine's own.
     *
     * The size follows the prefix length, so a /24 sweep asks 254 questions and a /30 asks two
     * rather than scanning the whole quarter of the world.
     */
    public static List<String> sweep(String local, int bits) {
        List<String> out = new ArrayList<String>();
        String[] octets = local == null ? null : local.split("\\.");
        if (octets == null || octets.length != 4) return out;

        int prefix = Math.max(8, Math.min(30, bits));
        int[] value = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                value[i] = Integer.parseInt(octets[i]);
            } catch (NumberFormatException e) {
                return out;
            }
            if (value[i] < 0 || value[i] > 255) return out;
        }

        int total = 1 << (32 - prefix);
        // A /8 would be 16 million probes; one host is a misconfiguration, not a network.
        int limit = Math.min(total, 4096);
        long own = ((long) value[0] << 24) | ((long) value[1] << 16)
                | ((long) value[2] << 8) | value[3];
        // Round down to the network address: the whole point is to enumerate the subnet the
        // machine sits in, not the range below the address it happens to hold.
        long base = own - (own % total);

        for (int offset = 0; offset < limit; offset++) {
            long candidate = base + offset;
            if (candidate == own) continue;
            if (candidate < 0 || candidate > 0xFFFFFFFFL) continue;
            out.add(format(candidate));
        }
        return out;
    }

    public static String format(long address) {
        return ((address >> 24) & 0xFF) + "." + ((address >> 16) & 0xFF) + "."
                + ((address >> 8) & 0xFF) + "." + (address & 0xFF);
    }

    /** Runs one sweep and folds the results in. Returns how many devices answered. */
    public int runOnce(String local, int bits, int timeoutMs) {
        running = true;
        registry.setScanning(true);
        final long now = System.currentTimeMillis();
        ExecutorService workers = pool();
        List<String> targets = sweep(local, bits);
        final CountDownLatch counter = new CountDownLatch(targets.size());
        try {
            final List<Device> found = Collections.synchronizedList(new ArrayList<Device>());

            for (final String ip : targets) {
                workers.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Device device = probe(ip, timeoutMs);
                            if (device != null) found.add(device);
                        } finally {
                            counter.countDown();
                        }
                    }
                });
            }
            // A whole sweep may take a minute when addresses time out; wait for all of them so a
            // partial result never wipes the devices that are still alive.
            counter.await(120, TimeUnit.SECONDS);

            // Read the neighbour cache once, after the sweep: entries only exist for addresses
            // that have been contacted, and the sweep is what contacted them.
            java.util.Map<String, String> macs = Arp.read();
            int probed = targets.size();
            int answered = found.size();
            lastSweepProbed = probed;
            lastSweepFound = answered;
            lastSweepCredible = looksImplausible(answered, probed);

            if (!lastSweepCredible) {
                // Reporting these would put two hundred imaginary devices on screen. The scan is
                // discarded and the window says why, which is the honest outcome.
                System.err.println("сканирование не вызывает доверия: ответили " + answered
                        + " из " + probed + " адресов — сеть отвечает на всё, список пуст");
                return 0;
            }

            for (Device device : found) {
                String mac = macs.get(device.ip);
                if (mac != null) device.mac = mac;
                device.seen = now;
                registry.report(device, now);
            }
            registry.expire(now, true);
            return found.size();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } finally {
            running = false;
            registry.setScanning(false);
        }
    }

    /** Asks one address whether it is there, and what it is. Null when it is not. */
    private Device probe(String ip, int timeoutMs) {
        InetAddress address;
        try {
            address = InetAddress.getByName(ip);
        } catch (Exception e) {
            return null;
        }

        boolean reachable;
        try {
            reachable = address.isReachable(timeoutMs);
        } catch (IOException e) {
            // A probe that cannot be sent is not a probe that failed; treat the host as unknown
            // and let the open ports decide whether anything is really there.
            reachable = false;
        }
        List<Integer> open = new ArrayList<Integer>();
        for (int port : PROBE_PORTS) {
            if (openPort(address, port, Math.min(timeoutMs, 400))) open.add(port);
        }
        // A host that answers neither ICMP nor TCP can still hold this app's own agent port,
        // so give that one a chance before calling the address empty.
        if (!reachable && open.isEmpty()) return null;

        Device device = new Device();
        device.ip = ip;
        device.kind = Device.KIND_SCANNED;
        device.ports = join(open);
        device.transport = guessTransport(device.ports);
        device.name = hostName(address);
        return device;
    }

    private boolean openPort(InetAddress address, int port, int timeoutMs) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(address, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
                // Already closing.
            }
        }
    }

    public static String join(List<Integer> values) {
        StringBuilder out = new StringBuilder();
        for (Integer value : values) {
            if (out.length() > 0) out.append(',');
            out.append(value.intValue());
        }
        return out.toString();
    }

    /** A printer speaks IPP, a Chromecast speaks its own port, and so on. */
    public static String guessTransport(String ports) {
        if (ports == null) return "";
        if (ports.contains("9100") || ports.contains("631")) return "принтер";
        if (ports.contains("32400")) return "медиасервер";
        if (ports.contains("2869")) return "медиаплеер";
        if (ports.contains("139") || ports.contains("445")) return "общий доступ";
        if (ports.contains("3389")) return "удалённый рабочий стол";
        if (ports.contains("22")) return "ssh";
        return "";
    }

    /**
     * The reverse name, if the network bothers to publish one.
     *
     * Reverse DNS on a home router usually answers with the vendor default, which is at least a
     * hint; when it times out or answers nothing the address itself is shown instead.
     */
    private String hostName(InetAddress address) {
        try {
            String name = address.getCanonicalHostName();
            if (name != null && name.length() > 0 && !name.equals(address.getHostAddress())) {
                return name;
            }
        } catch (Exception e) {
            // No name published; the address is the name.
        }
        return "";
    }
}