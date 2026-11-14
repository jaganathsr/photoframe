package com.jay.framehome;

import android.app.Activity;
import android.app.TimePickerDialog;
import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Settings screen for the sleep/wake schedule, with test buttons. */
public class ScheduleActivity extends Activity {
    private Switch enabled, fotooOnWake, fotooOnBoot;
    private Button sleepAt, wakeAt, grant;
    private TextView next;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setFillViewport(true);

        LinearLayout outer = new LinearLayout(this);
        outer.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(outer);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 40);
        col.setPadding(pad, pad, pad, pad);
        int width = Math.min(getResources().getDisplayMetrics().widthPixels, Ui.dp(this, 760));
        outer.addView(col, new LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT));

        col.addView(Ui.text(this, "Sleep schedule", 40, Ui.TEXT));
        next = Ui.text(this, "", 18, Ui.MUTED);
        col.addView(next, margins(4, 24));

        enabled = toggle("Turn the screen off and on automatically", Schedule.enabled(this), "enabled");
        col.addView(enabled, margins(6, 6));

        sleepAt = Ui.tile(this, "", null, v -> pickTime("sleep", Schedule.sleepMinute(this)));
        wakeAt = Ui.tile(this, "", null, v -> pickTime("wake", Schedule.wakeMinute(this)));
        LinearLayout times = new LinearLayout(this);
        times.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, Ui.dp(this, 130), 1f);
        half.setMargins(0, 0, Ui.dp(this, 8), 0);
        times.addView(sleepAt, half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, Ui.dp(this, 130), 1f);
        half2.setMargins(Ui.dp(this, 8), 0, 0, 0);
        times.addView(wakeAt, half2);
        col.addView(times, margins(6, 6));

        fotooOnWake = toggle("Start the slideshow after waking up", Schedule.fotooOnWake(this), "fotooOnWake");
        col.addView(fotooOnWake, margins(6, 6));
        fotooOnBoot = toggle("Start the slideshow when the frame powers on", Schedule.fotooOnBoot(this), "fotooOnBoot");
        col.addView(fotooOnBoot, margins(6, 6));

        grant = Ui.tile(this, "Allow screen off", "Required for the schedule to work", v -> requestAdmin());
        col.addView(grant, margins(6, 6));

        col.addView(Ui.text(this, "Test", 26, Ui.TEXT), margins(32, 8));
        Button testSleep = Ui.tile(this, "Sleep now", "Turn the screen off (press a key to wake)", v -> {
            if (!Schedule.sleepNow(this)) toast("Allow screen off first");
        });
        col.addView(testSleep, margins(6, 6));
        Button testCycle = Ui.tile(this, "Sleep, then wake in 1 minute", "Tests the full cycle", v -> {
            if (!Schedule.hasAdmin(this)) { toast("Allow screen off first"); return; }
            Schedule.scheduleOneOff(this, Schedule.ACTION_WAKE, 60_000, Schedule.REQ_TEST_WAKE);
            Schedule.sleepNow(this);
        });
        col.addView(testCycle, margins(6, 6));

        Button done = Ui.tile(this, "Done", null, v -> finish());
        col.addView(done, margins(32, 6));

        setContentView(scroll);
        enabled.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.immersive(this);
        refresh();
    }

    private Switch toggle(String label, boolean checked, String key) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextSize(22);
        s.setTextColor(Ui.TEXT);
        s.setChecked(checked);
        s.setBackground(Ui.tileBackground(this));
        int p = Ui.dp(this, 22);
        s.setPadding(p, p, p, p);
        s.setOnCheckedChangeListener((b, on) -> {
            Schedule.prefs(this).edit().putBoolean(key, on).apply();
            refresh();
        });
        return s;
    }

    private LinearLayout.LayoutParams margins(int topDp, int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, topDp);
        lp.bottomMargin = Ui.dp(this, bottomDp);
        return lp;
    }

    private void pickTime(String key, int current) {
        new TimePickerDialog(this, android.R.style.Theme_DeviceDefault_Dialog_Alert,
                (view, h, m) -> {
                    Schedule.prefs(this).edit().putInt(key, h * 60 + m).apply();
                    refresh();
                }, current / 60, current % 60, DateFormat.is24HourFormat(this)).show();
    }

    private void requestAdmin() {
        Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, Schedule.adminComponent(this))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Frame Home needs this to turn the screen off at your sleep time.");
        startActivity(i);
    }

    private void refresh() {
        Schedule.reschedule(this);
        Ui.setTileText(sleepAt, "Sleep at", Schedule.formatMinute(this, Schedule.sleepMinute(this)));
        Ui.setTileText(wakeAt, "Wake at", Schedule.formatMinute(this, Schedule.wakeMinute(this)));
        boolean on = enabled.isChecked();
        sleepAt.setEnabled(on);
        wakeAt.setEnabled(on);
        sleepAt.setAlpha(on ? 1f : 0.4f);
        wakeAt.setAlpha(on ? 1f : 0.4f);
        grant.setVisibility(Schedule.hasAdmin(this) ? android.view.View.GONE : android.view.View.VISIBLE);
        if (!on) {
            next.setText("Schedule is off — the screen stays on.");
        } else {
            next.setText("Next sleep: "
                    + Schedule.formatWhen(this, Schedule.nextOccurrence(Schedule.sleepMinute(this)))
                    + "   ·   Next wake: "
                    + Schedule.formatWhen(this, Schedule.nextOccurrence(Schedule.wakeMinute(this))));
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
