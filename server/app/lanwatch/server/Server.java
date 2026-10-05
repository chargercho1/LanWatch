package app.lanwatch.server;

import app.lanwatch.Device;
import app.lanwatch.Protocol;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Listens for phone agents and keeps them told what the network looks like.
 *
 * The desktop is the only authority on the roster. Agents push their own readings and are pushed
 * the whole table back, which is what lets a phone show the same list the desktop does rather
 * than only its own row. Every change republishes to everyone, so no phone is ever looking at a
 * stale network.
 *
 * Each connection gets its own thread. There are at most a handful of phones, and a blocking read
 * per thread keeps the protocol a plain line-at-a-time conversation with no shared state to guard.
 */
public final class Server {
    /** One connected agent. Inner, not static: it reports through the server's registry. */
    private final class Agent implements Runnable {
        final Socket socket;
        final String ip;
        Writer writer;
        volatile boolean open = true;

        Agent(Socket socket) {
            this.socket = socket;
            this.ip = socket.getInetAddress().getHostAddress();
        }

        @Override
        public void run() {
            try {
                writer = new OutputStreamWriter(socket.getOutputStream(), "UTF-8");
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), "UTF-8"));
                send(Protocol.frame(Protocol.T_ACK));

                String line;
                while (open && (line = in.readLine()) != null) {
                    Protocol.Message message = Protocol.unframe(line);
                    if (message == null) continue;
                    handle(message);
                }
            } catch (Exception e) {
                // A dropped phone is normal; the registry ages it out.
            } finally {
                open = false;
                close();
            }
        }

        private void handle(Protocol.Message message) throws Exception {
            if (message.type.equals(Protocol.T_BYE)) {
                open = false;
                close();
                return;
            }
            if (message.type.equals(Protocol.T_HELLO) || message.type.equals(Protocol.T_UPDATE)) {
                Device device = message.device();
                if (device.ip.length() == 0) device.ip = ip;
                device.seen = System.currentTimeMillis();
                registry.report(device, device.seen);
            }
        }

        synchronized void send(String line) {
            Writer out = writer;
            if (out == null) return;
            try {
                out.write(line);
                out.flush();
            } catch (Exception e) {
                open = false;
            }
        }

        void close() {
            try {
                socket.close();
            } catch (Exception e) {
                // Already gone.
            }
        }
    }

    /** The question an agent broadcasts when it does not know where the desktop is. */
    public static final String DISCOVERY_QUESTION = "LANW?";
    /** The answer: the desktop is here, at this address. */
    public static final String DISCOVERY_ANSWER = "LANW1";

    private final Registry registry;
    private final int requestedPort;
    private final List<Agent> agents = new CopyOnWriteArrayList<Agent>();
    private ServerSocket server;
    private java.net.DatagramSocket discovery;
    private volatile boolean running;
    private volatile int boundPort;
    private Thread acceptor;

    public Server(Registry registry, int port) {
        this.registry = registry;
        this.requestedPort = port;
        registry.addListener(new Registry.Listener() {
            @Override
            public void onChanged(List<Device> snapshot) {
                publish(snapshot);
            }
        });
    }

    /** The port actually in use, which matters when 0 was asked for. */
    public int port() {
        return boundPort != 0 ? boundPort : requestedPort;
    }

    /** The discovery port, one above the agent port so neither blocks the other. */
    public int discoveryPort() {
        return port() + 1;
    }

    public boolean isRunning() {
        return running;
    }

    public int agentCount() {
        return agents.size();
    }

    /** Binds and starts accepting. Throws when the port is taken, so the caller can say so. */
    public void start() throws Exception {
        server = new ServerSocket(requestedPort);
        boundPort = server.getLocalPort();
        running = true;
        acceptor = new Thread(new Runnable() {
            @Override
            public void run() {
                accept();
            }
        }, "lanwatch-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        startDiscovery();
    }

    /**
     * Answers "who is the desktop here" over UDP.
     *
     * Without this the user has to read the desktop's address off its window and type it on
     * every phone. Discovery is a convenience only — when a network blocks broadcast the agent
     * falls back to the typed address — so a failure here is swallowed rather than reported.
     */
    private void startDiscovery() {
        try {
            discovery = new java.net.DatagramSocket(discoveryPort());
            discovery.setBroadcast(true);
        } catch (Exception e) {
            discovery = null;
            return;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running && discovery != null) {
                    try {
                        byte[] buffer = new byte[64];
                        java.net.DatagramPacket packet =
                                new java.net.DatagramPacket(buffer, buffer.length);
                        discovery.receive(packet);
                        String question = new String(packet.getData(), 0, packet.getLength(), "UTF-8")
                                .trim();
                        if (!question.equals(DISCOVERY_QUESTION)) continue;
                        String reply = DISCOVERY_ANSWER + " " + Scanner.localAddress() + "\n";
                        byte[] bytes = reply.getBytes("UTF-8");
                        discovery.send(new java.net.DatagramPacket(bytes, bytes.length,
                                packet.getAddress(), packet.getPort()));
                    } catch (Exception e) {
                        if (!running) return;
                    }
                }
            }
        }, "lanwatch-discovery");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        for (Agent agent : agents) agent.close();
        agents.clear();
        try {
            if (server != null) server.close();
        } catch (Exception e) {
            // Closing.
        }
        try {
            if (discovery != null) discovery.close();
        } catch (Exception e) {
            // Closing.
        }
    }

    private void accept() {
        while (running) {
            try {
                final Socket client = server.accept();
                Agent agent = new Agent(client);
                agents.add(agent);
                Thread thread = new Thread(agent, "lanwatch-agent-" + agent.ip);
                thread.setDaemon(true);
                thread.start();
            } catch (SocketException e) {
                return;               // The socket was closed by stop().
            } catch (Exception e) {
                // A single bad connection must not take the listener down.
            }
        }
    }

    /** Sends the current table to every phone and forgets the ones that have gone quiet. */
    private void publish(List<Device> snapshot) {
        String roster = Protocol.frame(Protocol.T_ROSTER, Registry.rosterPayload(snapshot));
        for (Agent agent : agents) {
            if (!agent.open) {
                agents.remove(agent);
                continue;
            }
            agent.send(roster);
        }
    }

    /** Drops agents whose socket died, so the count on screen stays honest. */
    public void prune() {
        for (Agent agent : agents) {
            if (!agent.open) {
                agents.remove(agent);
            }
        }
    }

    /** The address to show the user, so they know where to point their phone. */
    public static String advertiseAddress() {
        String local = Scanner.localAddress();
        return local.length() == 0 ? "localhost" : local;
    }
}