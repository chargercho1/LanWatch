package app.lanwatch.server;

import app.lanwatch.Device;
import app.lanwatch.Json;
import app.lanwatch.Protocol;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The device table every screen reads from.
 *
 * Facts arrive from two places and land in one map keyed by address: agents report what only the
 * device itself can know, the sweep reports what is merely there. Merging rather than replacing
 * is the whole point — a phone with the app installed is a single row carrying both its MAC from
 * the ARP cache and its battery from the agent.
 *
 * Nothing here touches Swing or sockets, so the rules stay testable on their own.
 */
public final class Registry {
    public interface Listener {
        void onChanged(List<Device> snapshot);
    }

    private final Map<String, Device> table = new LinkedHashMap<String, Device>();
    private final List<Listener> listeners = new ArrayList<Listener>();
    private boolean scanning;

    public synchronized void addListener(Listener listener) {
        listeners.add(listener);
    }

    public synchronized void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Folds one reading in and, if anything actually changed, notifies the listeners. */
    public synchronized boolean report(Device reading, long now) {
        if (reading == null || reading.ip == null || reading.ip.length() == 0) return false;

        Device known = table.get(reading.ip);
        if (known == null) {
            Device copy = new Device();
            // The address is the key, so it has to be on the record too: every consumer reads
            // device.ip, and a row without one renders blank and cannot be expired by address.
            copy.ip = reading.ip;
            copy.merge(reading);
            if (copy.seen == 0) copy.seen = now;
            table.put(reading.ip, copy);
            notifyChanged();
            return true;
        }

        known.ip = reading.ip;
        String before = Json.write(known.toJson());
        known.merge(reading);
        if (before.equals(Json.write(known.toJson()))) return false;
        notifyChanged();
        return true;
    }

    /** Marks the sweep as running or not, so the UI can show progress. */
    public synchronized void setScanning(boolean value) {
        if (scanning == value) return;
        scanning = value;
        notifyChanged();
    }

    public synchronized boolean isScanning() {
        return scanning;
    }

    /**
     * Drops agents that stopped reporting, and sweeps out everything that stopped answering.
     *
     * A device is kept when it is an agent still inside its timeout, or when the sweep has
     * already seen it. This runs after each sweep so a silent address does not linger forever,
     * while a phone that is merely asleep keeps its row and its last known battery.
     */
    public synchronized int expire(long now, boolean afterSweep) {
        List<String> gone = new ArrayList<String>();
        for (Device device : table.values()) {
            long silence = device.age(now);
            boolean staleAgent = device.isAgent() && silence > Protocol.AGENT_TIMEOUT_MS;
            boolean silentHost = afterSweep && !device.isAgent() && silence > Protocol.SCAN_PERIOD_MS * 3;
            if (staleAgent || silentHost) gone.add(device.ip);
        }
        for (String ip : gone) table.remove(ip);
        if (!gone.isEmpty()) notifyChanged();
        return gone.size();
    }

    public synchronized Device find(String ip) {
        return table.get(ip);
    }

    /** Devices with agents first, then by address — the order both UIs show. */
    public synchronized List<Device> snapshot() {
        List<Device> list = new ArrayList<Device>(table.values());
        java.util.Collections.sort(list, new java.util.Comparator<Device>() {
            @Override
            public int compare(Device a, Device b) {
                if (a.isAgent() != b.isAgent()) return a.isAgent() ? -1 : 1;
                return a.ip.compareTo(b.ip);
            }
        });
        return list;
    }

    public synchronized int size() {
        return table.size();
    }

    public synchronized int agentCount() {
        int count = 0;
        for (Device device : table.values()) if (device.isAgent()) count++;
        return count;
    }

    /** Agents reporting a battery at or below the given percentage. */
    public synchronized int lowBatteryCount(int percent) {
        int count = 0;
        for (Device device : table.values()) {
            if (device.isAgent() && device.hasBattery() && device.battery <= percent) count++;
        }
        return count;
    }

    /** How many agents have gone quiet, so the UI can warn instead of showing stale batteries. */
    public synchronized int silentAgentCount(long now) {
        int count = 0;
        for (Device device : table.values()) {
            if (device.isAgent() && device.ageSeconds(now) > Protocol.AGENT_TIMEOUT_MS / 1000) count++;
        }
        return count;
    }

    /** The payload the server sends to each agent so the phone shows the same table. */
    public static Map<String, Object> rosterPayload(List<Device> devices) {
        List<Object> list = new ArrayList<Object>();
        for (Device device : devices) list.add(device.toJson());
        return Json.obj("devices", list);
    }

    /** Must hold the monitor: listeners push into Swing and sockets. */
    private void notifyChanged() {
        List<Device> snapshot = snapshot();
        for (Listener listener : new ArrayList<Listener>(listeners)) listener.onChanged(snapshot);
    }

    /** Visible for tests: reports without notifying. */
    synchronized Collection<Device> raw() {
        return table.values();
    }
}