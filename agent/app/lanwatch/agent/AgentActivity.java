package app.lanwatch.agent;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.lanwatch.Device;

import java.util.ArrayList;
import java.util.List;

/**
 * The phone's screen: what this device is reporting, and everything the desktop knows.
 *
 * The list is the same roster the desktop shows, pushed by the server, so a phone in the room is
 * a full view of the network rather than a mirror of one row. Starting the app also starts the
 * service, because a battery the desktop cannot see is the whole point of the app.
 */
public final class AgentActivity extends Activity implements AgentService.Observer {
    private static final String PREFS = "lanwatch";
    private static final String KEY_SERVER = "server";

    private LinearLayout list;
    private TextView status;
    private TextView address;
    private EditText serverField;
    private LinearLayout connection;
    private final List<Device> devices = new ArrayList<Device>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Skin.apply(this);
        getWindow().setStatusBarColor(Skin.background);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setNavigationBarColor(Skin.background);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Skin.background);
        scroll.setFillViewport(true);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), dp(24), dp(20), dp(24));

        column.addView(header(), wrap(dp(18)));
        connection = connectionBlock();
        column.addView(connection, wrap(dp(18)));
        column.addView(caption("устройства в сети"), wrap(dp(20)));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        column.addView(list, wrap(0));

        scroll.addView(column);
        setContentView(scroll);

        startService(new Intent(this, AgentService.class));
        AgentService.observe(this);
        reloadDevices();
    }

    @Override
    protected void onResume() {
        super.onResume();
        AgentService.observe(this);
        reloadDevices();
    }

    @Override
    protected void onPause() {
        AgentService.forget(this);
        super.onPause();
    }

    // --- ui ---------------------------------------------------------------------

    private View header() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Skin.round(Skin.accentSoft, Skin.radius));
        box.setPadding(dp(18), dp(16), dp(18), dp(16));

        box.addView(Skin.text(this, "LanWatch", 26f, Skin.text, true));
        TextView about = Skin.text(this,
                "Это устройство сообщает компьютеру заряд и состояние,\n"
                        + "а компьютер присылает список всей сети обратно.",
                13f, Skin.textDim, false);
        about.setLineSpacing(0f, 1.15f);
        box.addView(about, wrap(dp(8)));

        status = Skin.text(this, "запуск…", 13f, Skin.accent, true);
        box.addView(status, wrap(dp(4)));

        address = Skin.text(this, "адрес компьютера не задан", 12f, Skin.textFaint, false);
        box.addView(address);
        return box;
    }

    private LinearLayout connectionBlock() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        box.addView(caption("адрес компьютера"), wrap(dp(8)));

        serverField = new EditText(this);
        serverField.setHint("192.168.1.10:43121 — можно не указывать, компьютер найдётся сам");
        serverField.setTextColor(Skin.text);
        serverField.setHintTextColor(Skin.textFaint);
        serverField.setTextSize(14f);
        serverField.setSingleLine(true);
        serverField.setPadding(dp(14), dp(12), dp(14), dp(12));
        serverField.setBackground(Skin.outlined(Skin.surface, Skin.outline, Skin.radiusSmall));
        serverField.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_SERVER, ""));
        box.addView(serverField, wrap(dp(8)));

        final SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        serverField.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            public void onTextChanged(CharSequence s, int a, int b, int c) { }

            public void afterTextChanged(Editable s) {
                prefs.edit().putString(KEY_SERVER, s.toString().trim()).apply();
            }
        });

        TextView save = Skin.text(this, "Сохранить и переподключиться", 14f, Skin.text, true);
        save.setGravity(Gravity.CENTER);
        save.setPadding(dp(16), dp(13), dp(16), dp(13));
        save.setBackground(Skin.outlined(Skin.surface, Skin.outline, Skin.radiusSmall));
        Skin.ripple(save, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopService(new Intent(AgentActivity.this, AgentService.class));
                startService(new Intent(AgentActivity.this, AgentService.class));
            }
        });
        box.addView(save, wrap(0));
        return box;
    }

    private TextView caption(String value) {
        TextView view = Skin.text(this, value, 12f, Skin.textFaint, true);
        view.setAllCaps(true);
        return view;
    }

    private LinearLayout.LayoutParams wrap(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = topMargin;
        return params;
    }

    // --- the roster ---------------------------------------------------------------

    private void reloadDevices() {
        list.removeAllViews();
        if (devices.isEmpty()) {
            TextView empty = Skin.text(this,
                    "Пока пусто. Компьютер пришлёт список, как только агент подключится.",
                    13f, Skin.textFaint, false);
            empty.setLineSpacing(0f, 1.15f);
            list.addView(empty, wrap(0));
            return;
        }
        for (Device device : devices) list.addView(row(device), wrap(dp(8)));
    }

    private View row(Device device) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(device.isAgent() ? Skin.round(Skin.surfaceHigh, Skin.radius)
                : Skin.round(Skin.surface, Skin.radius));
        box.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.addView(Skin.text(this, device.describe(), 15f,
                device.isAgent() ? Skin.text : Skin.textDim, true));
        names.addView(Skin.text(this, device.ip, 12f, Skin.textFaint, false), wrap(0));
        head.addView(names, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (device.hasBattery()) {
            TextView percent = Skin.text(this, device.battery + "%", 18f,
                    Skin.batteryColor(device.battery), true);
            percent.setGravity(Gravity.CENTER);
            percent.setMinWidth(dp(56));
            head.addView(percent);
        } else {
            TextView none = Skin.text(this, "—", 16f, Skin.textFaint, false);
            none.setGravity(Gravity.CENTER);
            none.setMinWidth(dp(56));
            head.addView(none);
        }
        box.addView(head);

        // The thin bar under the row carries the level without needing to read the number.
        if (device.hasBattery()) {
            BatteryBar bar = new BatteryBar(this);
            bar.setPercent(device.battery);
            box.addView(bar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));
        }

        StringBuilder tags = new StringBuilder();
        if (device.isAgent()) tags.append("агент");
        if (device.charging) tags.append(tags.length() > 0 ? "  ·  " : "").append("заряжается");
        else if (device.powerSave) tags.append(tags.length() > 0 ? "  ·  " : "").append("экономия");
        if (device.transport != null && device.transport.length() > 0) {
            tags.append(tags.length() > 0 ? "  ·  " : "").append(device.transport);
        }
        if (device.mac != null && device.mac.length() > 0) {
            tags.append(tags.length() > 0 ? "  ·  " : "").append(device.mac);
        }
        if (tags.length() > 0) {
            TextView tag = Skin.text(this, tags.toString(), 12f, Skin.textDim, false);
            box.addView(tag, wrap(dp(8)));
        }

        long seconds = device.ageSeconds(System.currentTimeMillis());
        TextView seen = Skin.text(this, seconds < 10 ? "обновлено только что"
                : seconds < 90 ? "обновлено " + seconds + " с назад"
                : "обновлено " + (seconds / 60) + " мин назад", 11f, Skin.textFaint, false);
        box.addView(seen);
        return box;
    }

    // --- service callbacks --------------------------------------------------------

    public void onStatus(String text, boolean connected) {
        if (status == null) return;
        status.setText(text);
        status.setTextColor(connected ? 0xFF7AD48A : Skin.textDim);
        if (address != null) {
            String configured = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_SERVER, "");
            address.setText(configured.length() == 0
                    ? "компьютер ещё не найден — оставьте адрес пустым, он ищется сам"
                    : "компьютер: " + configured);
        }
    }

    public void onRoster(final List<Device> roster) {
        // Called on the service's worker thread; the views are not.
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                devices.clear();
                devices.addAll(roster);
                reloadDevices();
            }
        });
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}