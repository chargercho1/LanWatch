package app.lanwatch;

import java.net.InetAddress;
import java.util.List;
import java.util.Locale;

/**
 * Everything about addresses that both ends have to agree on.
 *
 * Three separate traps live here, and all three are shared: which of a machine's addresses a peer
 * should dial, how a hand typed address is split into host and port, and what counts as a MAC.
 * Neither the desktop nor the phone can see the other's source, so the rules exist once.
 */
public final class Address {
    private Address() { }

    /**
     * Whether an interface is Wi-Fi.
     *
     * Android exposes no API for naming the transport of a NetworkInterface, and the platform's
     * own naming is wlan0 and friends. It matters because a phone with mobile data also holds a
     * private 10.x address on the cellular interface: routable, and unreachable from the desktop
     * sitting on the same Wi-Fi. Wi-Fi is therefore always considered first.
     */
    public static boolean isWifi(String interfaceName) {
        if (interfaceName == null) return false;
        String name = interfaceName.toLowerCase(Locale.ROOT);
        return name.startsWith("wlan") || name.startsWith("wifi");
    }

    /**
     * The address a peer should dial, or an empty string when there is none.
     *
     * A listening socket binds to the wildcard and cannot answer this question itself, so the
     * answer comes from enumerating the interfaces. Loopback and the wildcard are skipped, IPv6
     * is not used at all, and a private IPv4 wins outright.
     */
    public static String choose(List<InetAddress> candidates) {
        String fallback = "";
        for (InetAddress address : candidates) {
            if (address == null) continue;
            String text = address.getHostAddress();
            if (text == null || text.length() == 0) continue;
            if (address.isLoopbackAddress() || address.isAnyLocalAddress()) continue;

            boolean ipv4 = text.indexOf(':') < 0;
            if (ipv4 && address.isSiteLocalAddress()) return text;   // 10/8, 172.16/12, 192.168/16

            // Keep the first routable IPv4 as a fallback. An IPv6 address is never offered: on a
            // home network it either has no route or needs a scope id that cannot be conveyed.
            if (ipv4 && fallback.length() == 0) fallback = text;
        }
        return fallback;
    }

    /**
     * The host part of a typed address, dropping any {@code :port} suffix.
     *
     * Handing "192.168.1.24:43121" to InetSocketAddress makes the resolver look for a host with
     * that name in it, and the failure points nowhere near the real mistake.
     */
    public static String host(String typed) {
        if (typed == null) return "";
        String trimmed = typed.trim();
        int colon = trimmed.lastIndexOf(':');
        if (hasPortSuffix(trimmed, colon)) return trimmed.substring(0, colon);
        return trimmed;
    }

    /** The port from a typed address, or the given default when it carries none. */
    public static int port(String typed, int fallback) {
        if (typed == null) return fallback;
        String trimmed = typed.trim();
        int colon = trimmed.lastIndexOf(':');
        if (!hasPortSuffix(trimmed, colon)) return fallback;
        try {
            int parsed = Integer.parseInt(trimmed.substring(colon + 1));
            return parsed > 0 && parsed <= 65535 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** True when the colon at {@code colon} really separates a port, rather than sitting in an IPv6 literal. */
    private static boolean hasPortSuffix(String trimmed, int colon) {
        if (colon <= 0 || colon == trimmed.length() - 1) return false;
        return trimmed.indexOf(':') == colon;
    }

    /**
     * Normalises a hardware address to lower case colon-separated form.
     *
     * The system cache spells them "3C-B3-84-1A-2B-3C", some tools spell them "3c.b3.84.1a.2b.3c",
     * and anything that is not twelve hex digits is simply not a MAC.
     */
    public static String normalizeMac(String token) {
        if (token == null) return null;
        String hex = token.toLowerCase(Locale.ROOT)
                .replace("-", "").replace(".", "").replace(":", "");
        if (hex.length() != 12) return null;
        for (int i = 0; i < 12; i++) {
            if (Character.digit(hex.charAt(i), 16) < 0) return null;
        }
        StringBuilder out = new StringBuilder(17);
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) out.append(':');
            out.append(hex, i, i + 2);
        }
        return out.toString();
    }
}