package com.jay.framehome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Lenovo's frame app is a system app that forces itself to be the home screen (priority 1),
 * which a normal app can't override without root. This service:
 *  - catches the Home key before Android handles it and opens Frame Home directly
 *    (avoids Android's 5-second app-switch delay after Home), and
 *  - if a Lenovo screen still appears (e.g. at boot), immediately switches to Frame Home.
 */
public class HomeGuardService extends AccessibilityService {
    static final String LENOVO_PACKAGE = "com.walnut.bluetooth";
    private long lastRedirect;

    private void goHome() {
        long now = SystemClock.uptimeMillis();
        if (now - lastRedirect < 1000) return;
        lastRedirect = now;
        startActivity(new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (e.getAction() == KeyEvent.ACTION_UP) goHome();
        return true; // consume both down and up so Lenovo's home never opens
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (!LENOVO_PACKAGE.contentEquals(String.valueOf(e.getPackageName()))) return;
        android.util.Log.i("FrameHome", "Lenovo screen appeared: " + e.getClassName());
        if (SlideshowActivity.alive) {
            // Lenovo popped up over the slideshow (e.g. right after boot): go back to the photos.
            Schedule.startSlideshow(this);
        } else {
            goHome();
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        WebService.start(this); // the guard starts with the system, so the remote does too
    }

    @Override
    public void onInterrupt() {}
}
