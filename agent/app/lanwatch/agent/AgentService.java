package app.lanwatch.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import app.lanwatch.Address;
import app.lanwatch.Device;
import app.lanwatch.Json;
import app.lanwatch.Protocol;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The background half of the agent: it tells the desktop what this device is and keeps telling
 * it whenever that changes.
 *
 * A phone has to stay reachable to be useful here, and a plain background service gets killed on
 * most releases, so this is a foreground service with a notification — the one arrangement
 * Android has accepted since 5.1. The notification is not decoration: it is the permission slip
 * the user granted by not swiping it away, and it carries the state that matters anyway.
 *
 * Everything runs on one worker thread. Sockets block, and the main thread belongs to the UI.
 */
public final class AgentService extends Service {
    private static final String TAG = "LanWatch";
    private static final String PREFS = "lanwatch";
    private static final String KEY_SERVER = "server";

    /** What the activity asks the service for. */
    public interface Observer {
        void onStatus(String status, boolean connected);

        void onRoster(List<Device> devices);
    }

    private static final List<Observer> observers = new CopyOnWriteArrayList<Observer>();

    public static void observe(Observer observer) {
        if (observer != null) observers.add(observer);
    }

    public static void forget(Observer observer) {
        observers.remove(observer);
    }

    private volatile boolean running;
    /** Set when the server address changes: drop the current socket and dial the new one. */
    private volatile boolean reconnect;
    private Thread worker;
    private Socket socket;
    private Writer writer;
    private volatile String status = "не подключено";
    private volatile boolean connected;
    private volatile int battery = -1;
    private volatile boolean charging;
    private volatile boolean powerSave;
    private volatile boolean screenOn;

    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
                    // A new address means a new server; reconnect on the next pass.
                    if (KEY_SERVER.equals(key)) {
                    // Closing the socket is what actually unblocks the read: the loop is
                    // parked in readLine and would otherwise wait for the old server to speak.
                    reconnect = true;
                    closeSocket();
                }
                }
            };

    /** Battery and power changes arrive as broadcasts; this turns them into a report. */
    private final BroadcastReceiver powerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            readBattery(intent);
            sendReading();
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        readBattery(null);
        startForeground(NOTIFICATION_ID, notification());
        registerReceivers();
        prefs().registerOnSharedPreferenceChangeListener(preferenceListener);

        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "lanwatch-agent");
        worker.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Restarted rather than stopped: the whole point is to still be reporting after the
        // system has reclaimed memory, which is exactly when a stale battery would be worst.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        prefs().unregisterOnSharedPreferenceChangeListener(preferenceListener);
        try {
            unregisterReceiver(powerReceiver);
        } catch (Exception e) {
            // Never registered, or already gone.
        }
        closeSocket();
        announce("агент остановлен", false);
        super.onDestroy();
    }

    // --- the loop --------------------------------------------------------------

    private void loop() {
        while (running) {
            String address = prefs().getString(KEY_SERVER, "");
            try {
                if (address.length() == 0) {
                    String found = discover();
                    if (found.length() == 0) {
                        announce("компьютер не найден — укажите адрес", false);
                        sleep(5000);
                        continue;
                    }
                    address = found + ":" + Protocol.PORT;
                    prefs().edit().putString(KEY_SERVER, address).apply();
                }
                connect(address);
            } catch (Exception e) {
                announce("нет связи: " + e.getMessage(), false);
                sleep(5000);
            }
        }
    }

    private void connect(String address) throws Exception {
        String host = Address.host(address);
        int port = Address.port(address, Protocol.PORT);

        Socket opened = new Socket();
        opened.connect(new InetSocketAddress(host, port), 8000);
        socket = opened;
        writer = new OutputStreamWriter(opened.getOutputStream(), "UTF-8");
        connected = true;
        announce("подключено к " + host + ":" + port, true);
        sendReading();

        BufferedReader in = new BufferedReader(
                new InputStreamReader(opened.getInputStream(), "UTF-8"));
        long since = 0;
        String line;
        while (running && !reconnect && (line = in.readLine()) != null) {
            Protocol.Message message = Protocol.unframe(line);
            if (message != null && message.type.equals(Protocol.T_ROSTER)) {
                publishRoster(message.devices());
            }
            // Heartbeat: the desktop ages an agent out that stops talking.
            if (System.currentTimeMillis() - since > Protocol.HEARTBEAT_MS) {
                since = System.currentTimeMillis();
                sendReading();
            }
        }

        connected = false;
        announce("компьютер закрыл соединение", false);
        closeSocket();
        // The read loop returns on a dropped line too; let the next pass pick a fresh address.
        reconnect = false;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --- reading this device ----------------------------------------------------

    /** Fills in the fields only the device itself knows. */
    private Device reading() {
        Device device = new Device();
        device.kind = Device.KIND_AGENT;
        device.ip = localIp();
        device.name = Build.MANUFACTURER + " " + Build.MODEL;
        device.model = Build.MODEL;
        device.os = "Android " + Build.VERSION.RELEASE;
        device.app = "1.0";
        device.transport = transport();
        device.battery = battery;
        device.charging = charging;
        device.powerSave = powerSave;
        device.screenOn = screenOn;
        device.seen = System.currentTimeMillis();
        return device;
    }

    /** Sends the current state. Safe to call from the UI thread. */
    public void sendReading() {
        Writer out = writer;
        if (out == null) return;
        try {
            synchronized (this) {
                out.write(Protocol.frame(Protocol.T_UPDATE, reading().toJson()));
                out.flush();
            }
        } catch (Exception e) {
            connected = false;
            closeSocket();
        }
    }

    /** The battery broadcast is sticky, so this reads the current state with no waiting. */
    private void readBattery(Intent broadcast) {
        Intent source = broadcast != null
                ? broadcast
                : registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (source == null) return;

        int level = source.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = source.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level >= 0 && scale > 0) battery = Math.round(level * 100f / scale);

        int status = source.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;

        powerSave = isPowerSaveOn();
        screenOn = isScreenOn();
    }

    private boolean isPowerSaveOn() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
                return power != null && power.isPowerSaveMode();
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isScreenOn() {
        try {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            if (power == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                return power.isInteractive();
            }
            return power.isScreenOn();
        } catch (Exception e) {
            return false;
        }
    }

    private String transport() {
        try {
            ConnectivityManager manager =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return "";
            NetworkInfo info = manager.getActiveNetworkInfo();
            if (info == null || !info.isConnected()) return "";
            switch (info.getType()) {
                case ConnectivityManager.TYPE_WIFI: return "wifi";
                case ConnectivityManager.TYPE_MOBILE: return "мобильная сеть";
                case ConnectivityManager.TYPE_ETHERNET: return "проводная";
                default: return info.getTypeName().toLowerCase();
            }
        } catch (Exception e) {
            return "";
        }
    }

    private String localIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> nics =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                java.net.NetworkInterface nic = nics.nextElement();
                if (!nic.isUp() || nic.isLoopback()) continue;
                if (!Address.isWifi(nic.getName())) continue;
                java.util.Enumeration<java.net.InetAddress> addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    String text = addresses.nextElement().getHostAddress();
                    if (text != null && text.indexOf('.') > 0) return text;
                }
            }
        } catch (Exception e) {
            // Fall through: the desktop will label this connection by socket address anyway.
        }
        return "";
    }

    // --- discovery --------------------------------------------------------------

    /** Asks the network where the desktop is. Empty when nothing answers. */
    private String discover() {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            socket.setSoTimeout(2500);
            byte[] question = "LANW?".getBytes("UTF-8");
            socket.send(new DatagramPacket(question, question.length,
                    InetAddress.getByName("255.255.255.255"), Protocol.PORT + 1));
            byte[] buffer = new byte[128];
            DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
            socket.receive(reply);
            String text = new String(reply.getData(), 0, reply.getLength(), "UTF-8").trim();
            if (text.startsWith("LANW1 ")) return text.substring(6).trim();
        } catch (Exception e) {
            // Broadcast is often blocked; the user can type the address instead.
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {
                    // Closing.
                }
            }
        }
        return "";
    }

    // --- notification -----------------------------------------------------------

    /**
     * The ongoing notification.
     *
     * Its text is the fastest way to see whether the agent is doing its job, so it carries the
     * same state the desktop shows: charge, charging, and where the server is.
     */
    private Notification notification() {
        Intent open = new Intent(this, AgentActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content = PendingIntent.getActivity(this, 0, open, flags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "LanWatch",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
            builder = new Notification.Builder(this, CHANNEL);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setContentTitle("LanWatch — устройства в сети")
                .setContentText(statusText())
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(content)
                .setOngoing(true)
                .build();
    }

    private static final String CHANNEL = "lanwatch";
    private static final int NOTIFICATION_ID = 1;

    private String statusText() {
        if (battery < 0) return status;
        StringBuilder out = new StringBuilder(status);
        out.append("  ·  ").append(battery).append('%');
        if (charging) out.append(" ⚡");
        return out.toString();
    }

    private void refreshNotification() {
        try {
            NotificationManager manager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.notify(NOTIFICATION_ID, notification());
        } catch (Exception e) {
            // Notifications can be blocked; the service itself carries on regardless.
        }
    }

    private void registerReceivers() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        try {
            // The battery broadcast is sticky, so this also delivers the current state at once.
            registerReceiver(powerReceiver, filter);
        } catch (Exception e) {
            Log.w(TAG, "не удалось подписаться на батарею", e);
        }
    }

    // --- talking to the activity -------------------------------------------------

    private void announce(String text, boolean isConnected) {
        status = text;
        connected = isConnected;
        refreshNotification();
        for (Observer observer : observers) observer.onStatus(status, connected);
    }

    private void publishRoster(final List<Device> devices) {
        final List<Device> copy = new ArrayList<Device>(devices);
        for (Observer observer : observers) observer.onRoster(copy);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void closeSocket() {
        Writer out = writer;
        writer = null;
        if (out != null) {
            try {
                out.write(Protocol.frame(Protocol.T_BYE));
                out.flush();
            } catch (Exception e) {
                // Already gone.
            }
        }
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception e) {
                // Already gone.
            }
            socket = null;
        }
        connected = false;
    }
}