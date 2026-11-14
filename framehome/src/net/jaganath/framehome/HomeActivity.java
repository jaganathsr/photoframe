package net.jaganath.framehome;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

/** Full-screen home screen: clock, schedule status, and tiles for Photos, Schedule, Apps, Settings. */
public class HomeActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private Button photos;
    private Button setupTile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // No keep-screen-on here on purpose: when the home screen is left idle, Android's
        // screen timeout starts the screensaver, i.e. the Fotoo slideshow.

        boolean portrait = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_PORTRAIT;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(this, 28);
        root.setPadding(pad, pad, pad, pad);

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextSize(portrait ? 120 : 112);
        clock.setTextColor(Ui.TEXT);
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        clock.setGravity(Gravity.CENTER);
        root.addView(clock);

        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, MMMM d");
        date.setFormat24Hour("EEEE, MMMM d");
        date.setTextSize(30);
        date.setTextColor(Ui.MUTED);
        date.setGravity(Gravity.CENTER);
        root.addView(date);

        status = Ui.text(this, "", 20, Ui.MUTED);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(this, 16);
        slp.bottomMargin = Ui.dp(this, 28);
        root.addView(status, slp);

        photos = Ui.tile(this, "Slideshow", "FrameNew from Immich", v -> Schedule.startSlideshow(this));
        Button schedule = Ui.tile(this, "Schedule", "Sleep & wake times",
                v -> startActivity(new Intent(this, ScheduleActivity.class)));
        Button apps = Ui.tile(this, "Apps", "All installed apps",
                v -> startActivity(new Intent(this, AppsActivity.class)));
        Button settings = Ui.tile(this, "Settings", "Android settings", v -> openSettings());
        setupTile = Ui.tile(this, "Setup", "", v -> startActivity(new Intent(this, SetupActivity.class)));

        Button[] tiles = {photos, schedule, setupTile, apps, settings};
        int perRow = portrait ? 3 : 5;
        int tileH = Ui.dp(this, portrait ? 170 : 150);
        int gap = Ui.dp(this, 10);
        for (int start = 0; start < tiles.length; start += perRow) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_HORIZONTAL);
            row.setWeightSum(perRow);
            for (int i = start; i < Math.min(start + perRow, tiles.length); i++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, tileH, 1f);
                lp.setMargins(gap, gap, gap, gap);
                row.addView(tiles[i], lp);
            }
            root.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        setContentView(root);
        photos.requestFocus();
        WebService.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.immersive(this);
        Schedule.reschedule(this);
        updateStatus();
        int left = Setup.remaining(this);
        Ui.setTileText(setupTile, "Setup", left == 0 ? "All set ✓" : left + (left == 1 ? " step left" : " steps left"));
        // First launch after a plain APK install: walk through the permissions on the frame.
        if (!Setup.requiredDone(this) && !Schedule.prefs(this).getBoolean("setupShown", false)) {
            startActivity(new Intent(this, SetupActivity.class));
            return;
        }
        maybeStartFotooAfterBoot();
        resetIdle();
    }

    // ---- Return to the slideshow after a couple of idle minutes on the home screen.
    private final Runnable idleToSlideshow = () -> Schedule.startSlideshow(this);

    private void resetIdle() {
        handler.removeCallbacks(idleToSlideshow);
        int minutes = Prefs.idleMinutes(this);
        if (minutes > 0) handler.postDelayed(idleToSlideshow, minutes * 60_000L);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(idleToSlideshow);
        super.onPause();
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        String name = android.view.KeyEvent.keyCodeToString(event.getKeyCode());
        // The frame's hand-gesture sensor and app-injected keys don't count as someone using it.
        if (!name.contains("GESTURE")
                && event.getDeviceId() != android.view.KeyCharacterMap.VIRTUAL_KEYBOARD) {
            resetIdle();
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        resetIdle();
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent event) {
        resetIdle(); // mouse movement
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Ui.immersive(this);
    }

    @Override
    public void onBackPressed() {
        // This is the home screen; there is nothing to go back to.
    }

    private void updateStatus() {
        String s;
        if (!Schedule.enabled(this)) {
            s = "Sleep schedule is off";
        } else {
            s = "Sleeps at " + Schedule.formatMinute(this, Schedule.sleepMinute(this))
                    + "  ·  Wakes at " + Schedule.formatMinute(this, Schedule.wakeMinute(this));
            if (!Schedule.hasAdmin(this)) s += "\nScreen-off permission missing — open Schedule";
        }
        String remote = WebService.address();
        if (remote != null) s += "\nPhone remote: " + remote + "   ·   PIN " + Prefs.pin(this);
        status.setText(s);
    }

    /** Starts the slideshow automatically once after each power-on, like the original frame software. */
    private void maybeStartFotooAfterBoot() {
        if (!Schedule.fotooOnBoot(this)) return;
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        SharedPreferences p = Schedule.prefs(this);
        if (boot == p.getInt("lastBoot", Integer.MIN_VALUE)) return;
        p.edit().putInt("lastBoot", boot).apply();
        handler.postDelayed(() -> Schedule.startSlideshow(this), 3000);
    }

    private void launchFotoo(boolean complain) {
        Intent i = Schedule.fotooIntent(this);
        if (i == null) {
            if (complain) Toast.makeText(this, "Fotoo is not installed", Toast.LENGTH_LONG).show();
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    private void openSettings() {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .setComponent(new ComponentName("com.android.settings", "com.android.settings.Settings"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
