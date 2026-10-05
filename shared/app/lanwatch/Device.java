package app.lanwatch;

import java.util.Map;

/**
 * One row in the device table, as seen by every screen that shows the network.
 *
 * A device can be known from two directions at once: the subnet scan sees that something answers
 * at an address, and an agent additionally reports its battery and model. Keeping both kinds of
 * fact in one record means a phone with the app installed and a printer without it appear in the
 * same list, with the phone carrying more columns rather than being somewhere else entirely.
 *
 * Every field is a plain value so the record can go over the wire as a flat JSON object and be
 * read back by either side without reflection.
 */
public final class Device {
    public static final String KIND_AGENT = "agent";
    public static final String KIND_SCANNED = "scanned";

    public String ip = "";
    public String name = "";        // hostname, or the agent's advertised label
    public String kind = KIND_SCANNED;
    public String mac = "";
    public String os = "";
    public String model = "";
    public String app = "";         // agent version
    public String transport = "";   // "wifi", "mobile", "wired", or "" when unknown
    public String ports = "";       // open ports worth mentioning, e.g. "80,443"
    public int battery = -1;        // 0..100, or -1 when nothing reported one
    public boolean charging;
    public boolean powerSave;
    public boolean screenOn;
    public long seen;               // last contact, epoch millis

    public boolean isAgent() {
        return KIND_AGENT.equals(kind);
    }

    public boolean hasBattery() {
        return battery >= 0;
    }

    /** "192.168.1.5" or, when the name is known, "Pixel 8 · 192.168.1.5". */
    public String title() {
        if (name == null || name.length() == 0) return ip;
        if (name.equals(ip)) return ip;
        return name + "  ·  " + ip;
    }

    public long age(long now) {
        return Math.max(0, now - seen);
    }

    /** Seconds since last contact, for the "seen 12 s ago" line. */
    public long ageSeconds(long now) {
        return age(now) / 1000;
    }

    /**
     * Folds a fresh report into this record.
     *
     * Agent fields win outright, because only the device itself knows them. Scan fields are
     * filled in where they are still empty, and a mac or name discovered later fills a blank
     * without erasing anything an agent has since reported.
     */
    public void merge(Device other) {
        if (other == null) return;
        if (fill(other.name)) name = other.name;
        if (fill(other.mac)) mac = other.mac;
        if (fill(other.os)) os = other.os;
        if (fill(other.model)) model = other.model;
        if (fill(other.app)) app = other.app;
        if (fill(other.transport)) transport = other.transport;
        if (fill(other.ports)) ports = other.ports;
        if (other.battery >= 0) battery = other.battery;
        if (other.isAgent()) {
            kind = KIND_AGENT;
            charging = other.charging;
            powerSave = other.powerSave;
            screenOn = other.screenOn;
        }
        if (other.seen > seen) seen = other.seen;
    }

    private static boolean fill(String value) {
        return value != null && value.length() > 0;
    }

    // --- wire format -----------------------------------------------------------

    public Map<String, Object> toJson() {
        return Json.obj(
                "ip", ip,
                "name", name,
                "kind", kind,
                "mac", mac,
                "os", os,
                "model", model,
                "app", app,
                "transport", transport,
                "ports", ports,
                "battery", battery,
                "charging", charging,
                "powerSave", powerSave,
                "screenOn", screenOn,
                "seen", seen);
    }

    public static Device fromJson(Map<String, Object> map) {
        Device device = new Device();
        if (map == null) return device;
        device.ip = Json.str(map, "ip", "");
        device.name = Json.str(map, "name", "");
        device.kind = Json.str(map, "kind", KIND_SCANNED);
        device.mac = Json.str(map, "mac", "");
        device.os = Json.str(map, "os", "");
        device.model = Json.str(map, "model", "");
        device.app = Json.str(map, "app", "");
        device.transport = Json.str(map, "transport", "");
        device.ports = Json.str(map, "ports", "");
        device.battery = Json.num(map, "battery", -1);
        device.charging = Json.bool(map, "charging", false);
        device.powerSave = Json.bool(map, "powerSave", false);
        device.screenOn = Json.bool(map, "screenOn", false);
        device.seen = Json.longNum(map, "seen", 0);
        return device;
    }

    /** Best guess at what to call this device in the UI. */
    public String describe() {
        if (model != null && model.length() > 0) return model;
        if (name != null && name.length() > 0 && !name.equals(ip)) return name;
        if (transport != null && transport.length() > 0) return "устройство " + transport;
        if (ports != null && ports.length() > 0) return "устройство, порты " + ports;
        return "устройство";
    }

    /** A plain summary line for the agents that receive this list. */
    public String summary() {
        StringBuilder out = new StringBuilder(describe());
        if (hasBattery()) {
            out.append("  ").append(battery).append('%');
            if (charging) out.append(" ⚡");
        }
        out.append("  ").append(ip);
        return out.toString();
    }
}