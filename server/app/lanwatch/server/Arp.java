package app.lanwatch.server;

import app.lanwatch.Address;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * MAC addresses from the operating system neighbour cache.
 *
 * Java has no way to ask for a remote host's hardware address, and opening a raw socket for it
 * would need root on Linux and Npcap on Windows. The system already knows the answer for every
 * address this process has recently talked to, and the sweep has just talked to all of them, so
 * the cache is read instead of asking the hardware.
 *
 * That is the honest version of the trick: it only knows about addresses contacted recently, and
 * a cold entry is genuinely unknown rather than blank-by-lazy.
 */
public final class Arp {
    private Arp() { }

    /**
     * The cache spells addresses "3C-B3-84-1A-2B-3C" and some tools spell them
     * "3c.b3.84.1a.2b.3c"; both become the same colon-separated form. Anything that is not twelve
     * hex digits is not a MAC and is rejected rather than shown.
     */
    public static String normalize(String token) {
        return Address.normalizeMac(token);
    }

    /** The cache as read now: address to MAC. Empty when the platform gives nothing usable. */
    public static Map<String, String> read() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return new LinkedHashMap<String, String>();
        }
        Map<String, String> out = new LinkedHashMap<String, String>();
        try {
            Process process = new ProcessBuilder("arp", "-a").redirectErrorStream(true).start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), "UTF-8"));
            try {
                String line;
                while ((line = reader.readLine()) != null) parse(line, out);
            } finally {
                reader.close();
            }
            process.waitFor();
        } catch (Exception e) {
            // No cache to read is a normal outcome, not a failure: the rows still show by address.
        }
        return out;
    }

    /**
     * Reads one line of `arp -a` output.
     *
     * Lines look like "  192.168.0.23          3c-b3-84-1a-2b-3c     dynamic", but some Windows
     * builds wrap them and some prefix an interface header. Only a line that starts with an
     * address and whose second token is a MAC is accepted.
     */
    public static void parse(String line, Map<String, String> out) {
        if (line == null) return;
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 2) return;
        if (!isAddress(parts[0])) return;
        String mac = normalize(parts[1]);
        if (mac == null) return;
        out.put(parts[0], mac);
    }

    private static boolean isAddress(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.length() == 0 || part.length() > 3) return false;
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) return false;
            }
            if (Integer.parseInt(part) > 255) return false;
        }
        return true;
    }
}