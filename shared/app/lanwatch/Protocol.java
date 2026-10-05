package app.lanwatch;

/**
 * The conversation between the desktop server and the phone agents.
 *
 * One JSON object per line, newline separated, which is the same framing DuetScreen uses and for
 * the same reason: a reader can hold a line at a time without any framing state, and a dropped
 * connection costs one line rather than the whole stream.
 *
 * Three messages travel in total. The agent announces itself, sends a new reading whenever
 * something changes, and says goodbye when it stops. The server answers with the full device
 * roster, because the phone has to show the same list the desktop does.
 */
public final class Protocol {
    private Protocol() { }

    /** Server port. 43121 sits just above DuetScreen's pair so the two never collide. */
    public static final int PORT = 43121;

    public static final String T_HELLO = "hello";    // agent  -> server: a full reading
    public static final String T_UPDATE = "update";  // agent  -> server: a full reading
    public static final String T_BYE = "bye";        // agent  -> server: leaving
    public static final String T_ROSTER = "roster";  // server -> agent:  every known device
    public static final String T_ACK = "ack";        // server -> agent:  you are registered

    /** Heartbeat every 20 s, which is also what marks an agent as gone when it stops. */
    public static final long HEARTBEAT_MS = 20000L;

    /** An agent that has not been heard from for this long is shown as stale. */
    public static final long AGENT_TIMEOUT_MS = 60000L;

    /** How often the subnet is swept. */
    public static final long SCAN_PERIOD_MS = 20000L;

    /** Wraps one message into a framed line. */
    public static String frame(String type, Object payload) {
        return Json.write(Json.obj("t", type, "d", payload)) + "\n";
    }

    public static String frame(String type) {
        return frame(type, Json.obj());
    }

    /**
     * Reads a framed line back.
     *
     * Returns null for anything that is not a message this protocol defines, so a stray line — a
     * port scanner knocking, or a version mismatch — is skipped rather than crashing a reader.
     */
    public static Message unframe(String line) {
        if (line == null) return null;
        java.util.Map<String, Object> map = Json.readObject(line.trim());
        if (map == null) return null;
        String type = Json.str(map, "t", "");
        if (type.length() == 0) return null;
        Object payload = map.get("d");
        java.util.Map<String, Object> body = payload instanceof java.util.Map
                ? cast(payload)
                : Json.obj();
        return new Message(type, body);
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> cast(Object value) {
        return (java.util.Map<String, Object>) value;
    }

    /** A decoded message: its type and its body. */
    public static final class Message {
        public final String type;
        public final java.util.Map<String, Object> body;

        Message(String type, java.util.Map<String, Object> body) {
            this.type = type;
            this.body = body;
        }

        public Device device() {
            return Device.fromJson(body);
        }

        public java.util.List<Device> devices() {
            java.util.List<Device> list = new java.util.ArrayList<Device>();
            Object value = body.get("devices");
            if (!(value instanceof java.util.List)) return list;
            for (Object item : (java.util.List<?>) value) {
                if (item instanceof java.util.Map) list.add(Device.fromJson(cast(item)));
            }
            return list;
        }
    }
}