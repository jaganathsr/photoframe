package net.jaganath.framehome;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/** Invisible activity that turns the screen on, then starts the slideshow (or home) and closes. */
public class WakeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        KeyguardManager km = getSystemService(KeyguardManager.class);
        if (km != null) km.requestDismissKeyguard(this, null);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            boolean homeRequested = Schedule.prefs(this).getBoolean("homeOnNextWake", false);
            if (homeRequested) Schedule.prefs(this).edit().remove("homeOnNextWake").apply();
            if (!homeRequested && Schedule.fotooOnWake(this)) {
                Schedule.startSlideshow(this);
            } else {
                startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            finish();
        }, 800);
    }
}
