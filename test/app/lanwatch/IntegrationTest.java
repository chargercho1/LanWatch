package app.lanwatch;

import app.lanwatch.server.Registry;
import app.lanwatch.server.Server;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;

/**
 * The server and a phone agent talking to each other over real sockets.
 *
 * The unit checks prove the rules; this proves the wire. A server is started on a port the
 * operating system picks, a socket connects the way an agent would, and the exchange is read back
 * off the connection — so a framing mistake, a roster that never arrives, or discovery that never
 * answers all fail here rather than on a phone.
 */
public final class IntegrationTest {
    private int passed;
    private int failed;

    public static void main(String[] args) throws Exception {
        IntegrationTest test = new IntegrationTest();
        test.agentRegistersAndGetsRoster();
        test.secondAgentSeesTheFirst();
        test.goodbyeIsAccepted();
        test.discoveryAnswers();
        test.unknownTrafficIsIgnored();

        System.out.println();
        System.out.println("passed: " + test.passed + ", failed: " + test.failed);
        System.out.println(test.failed == 0 ? "ALL PASS" : "FAILURES");
        System.exit(test.failed == 0 ? 0 : test.failed);
    }

    /** A stand-in for the Android agent, speaking exactly what AgentService sends. */
    private static final class FakeAgent implements AutoCloseable {
        private final Socket socket;
        private final Writer writer;
        private final BufferedReader reader;

        FakeAgent(Server server) throws Exception {
            socket = new Socket("127.0.0.1", server.port());
            socket.setSoTimeout(5000);
            writer = new OutputStreamWriter(socket.getOutputStream(), "UTF-8");
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
        }

        void hello(String ip, String model, int battery, boolean charging) throws Exception {
            Device device = new Device();
            device.ip = ip;
            device.kind = Device.KIND_AGENT;
            device.model = model;
            device.battery = battery;
            device.charging = charging;
            device.seen = System.currentTimeMillis();
            send(Protocol.frame(Protocol.T_HELLO, device.toJson()));
        }

        void send(String line) throws Exception {
            writer.write(line);
            writer.flush();
        }

        /** Reads until a roster with at least the given number of devices arrives, or gives up. */
        Protocol.Message awaitRoster(int atLeast) throws Exception {
            long until = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < until) {
                String line;
                try {
                    line = reader.readLine();
                } catch (SocketTimeoutException e) {
                    return null;
                }
                if (line == null) return null;
                Protocol.Message message = Protocol.unframe(line);
                if (message == null || !message.type.equals(Protocol.T_ROSTER)) continue;
                if (message.devices().size() >= atLeast) return message;
            }
            return null;
        }

        /** Reads the next roster of any size. */
        Protocol.Message awaitRoster() throws Exception {
            return awaitRoster(0);
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (Exception e) {
                // Closing.
            }
        }
    }

    private void agentRegistersAndGetsRoster() throws Exception {
        Registry registry = new Registry();
        Server server = new Server(registry, 0);
        server.start();
        try {
            ok("the server binds a port", server.port() > 0);

            FakeAgent agent = new FakeAgent(server);
            try {
                agent.hello("192.168.1.44", "Pixel 8", 63, true);

                Protocol.Message roster = agent.awaitRoster(1);
                ok("the agent receives a roster", roster != null);
                if (roster == null) return;

                List<Device> devices = roster.devices();
                ok("the roster lists the agent", devices.size() == 1);

                Device self = devices.get(0);
                ok("the roster carries the address", "192.168.1.44".equals(self.ip));
                ok("the roster carries the model", "Pixel 8".equals(self.model));
                ok("the roster carries the battery", self.battery == 63);
                ok("the roster carries charging", self.charging);
                ok("the roster marks it as an agent", self.isAgent());
                ok("the registry agrees", registry.size() == 1);
            } finally {
                agent.close();
            }
        } finally {
            server.stop();
        }
    }

    private void secondAgentSeesTheFirst() throws Exception {
        Registry registry = new Registry();
        Server server = new Server(registry, 0);
        server.start();
        try {
            FakeAgent phone = new FakeAgent(server);
            FakeAgent tablet = new FakeAgent(server);
            try {
                phone.hello("192.168.1.44", "Pixel 8", 63, false);
                phone.awaitRoster(1);

                tablet.hello("192.168.1.45", "Galaxy Tab", 90, true);
                // Both agents have now registered, so the roster this one should show has two
                // rows. An earlier push carrying only the phone may still be in flight.
                Protocol.Message roster = tablet.awaitRoster(2);

                ok("the second agent gets a roster too", roster != null);
                if (roster == null) return;

                List<Device> devices = roster.devices();
                ok("the roster lists both agents", devices.size() == 2);

                boolean sawPhone = false;
                for (Device device : devices) {
                    if ("192.168.1.44".equals(device.ip)) {
                        sawPhone = true;
                        ok("the tablet sees the phone's model", "Pixel 8".equals(device.model));
                        ok("the tablet sees the phone's battery", device.battery == 63);
                    }
                }
                ok("the tablet sees the phone", sawPhone);
            } finally {
                phone.close();
                tablet.close();
            }
        } finally {
            server.stop();
        }
    }

    private void goodbyeIsAccepted() throws Exception {
        Registry registry = new Registry();
        Server server = new Server(registry, 0);
        server.start();
        try {
            FakeAgent agent = new FakeAgent(server);
            agent.hello("192.168.1.44", "Pixel 8", 63, false);
            agent.awaitRoster(1);
            ok("the agent is registered before leaving", registry.size() == 1);

            agent.send(Protocol.frame(Protocol.T_BYE));
            agent.close();

            // The row is kept until its timeout: the phone may simply have gone out of range.
            ok("a goodbye does not erase the row immediately", registry.size() == 1);
        } finally {
            server.stop();
        }
    }

    private void discoveryAnswers() throws Exception {
        Registry registry = new Registry();
        Server server = new Server(registry, 0);
        server.start();
        DatagramSocket probe = new DatagramSocket();
        try {
            probe.setSoTimeout(5000);
            probe.setBroadcast(true);
            byte[] question = Server.DISCOVERY_QUESTION.getBytes("UTF-8");
            probe.send(new DatagramPacket(question, question.length,
                    InetAddress.getByName("127.0.0.1"), server.discoveryPort()));

            byte[] buffer = new byte[128];
            DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
            probe.receive(reply);
            String answer = new String(reply.getData(), 0, reply.getLength(), "UTF-8").trim();

            ok("discovery answers with the marker", answer.startsWith(Server.DISCOVERY_ANSWER + " "));
            ok("discovery names an address", answer.length() > Server.DISCOVERY_ANSWER.length() + 1);
        } catch (SocketTimeoutException e) {
            no("discovery answers on loopback");
        } finally {
            probe.close();
            server.stop();
        }
    }

    private void unknownTrafficIsIgnored() throws Exception {
        Registry registry = new Registry();
        Server server = new Server(registry, 0);
        server.start();
        try {
            FakeAgent agent = new FakeAgent(server);
            try {
                // What a random port scanner, or a stale build of the agent, would send.
                agent.send("EHLO this is not a phone\n");
                agent.send("{\"t\":\"update\",\"d\":{}}\n");
                agent.send("not json at all\n");
                agent.send("\n");

                Protocol.Message roster = agent.awaitRoster();
                ok("the server survives junk", roster != null);
                ok("junk does not invent a device", registry.size() <= 1);
            } finally {
                agent.close();
            }
        } finally {
            server.stop();
        }
    }

    // --- harness ---------------------------------------------------------------

    private void ok(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("ok    " + name);
        } else {
            failed++;
            System.out.println("FAIL  " + name);
        }
    }

    private void ok(String name) {
        passed++;
        System.out.println("ok    " + name);
    }

    private void no(String name) {
        failed++;
        System.out.println("FAIL  " + name);
    }
}