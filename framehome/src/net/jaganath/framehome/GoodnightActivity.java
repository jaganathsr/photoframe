package net.jaganath.framehome;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Shown at the scheduled sleep time: a good-night message for 30 seconds, then the screen turns
 * off. Keys and the gesture sensor can't cancel it, and if anything closes it early the frame
 * still goes to sleep.
 */
public class GoodnightActivity extends Activity {
    static final long SHOW_MS = 30_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean slept;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setShowWhenLocked(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF05060A);

        TextView moon = Ui.text(this, "🌙", 72, Ui.TEXT);
        moon.setGravity(Gravity.CENTER);
        root.addView(moon);

        TextView title = Ui.text(this, "Frame going to sleep.", 48, Ui.TEXT);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 24);
        root.addView(title, lp);

        TextView sub = Ui.text(this, "Good night and see you tomorrow", 30, Ui.MUTED);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = Ui.dp(this, 12);
        root.addView(sub, lp2);

        root.setAlpha(0f);
        setContentView(root);
        root.animate().alpha(1f).setDuration(1500).start();
        handler.postDelayed(this::sleepNow, SHOW_MS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.immersive(this);
    }

    private void sleepNow() {
        if (slept) return;
        slept = true;
        Schedule.sleepNow(this);
        finish();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return true; // keys and the gesture sensor don't cancel going to sleep
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        return true;
    }

    @Override
    public void onBackPressed() {
        // ignore
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (!slept) { // closed early by something else: still go to sleep
            slept = true;
            Schedule.sleepNow(this);
        }
        super.onDestroy();
    }
}
