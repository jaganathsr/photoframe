package net.jaganath.framehome;

import android.service.dreams.DreamService;

/** The slideshow as Android's screensaver, so an idle screen goes back to photos. */
public class FrameDream extends DreamService {
    private static volatile FrameDream active;
    private SlideshowView view;

    static boolean isDreaming() { return active != null; }

    /** Ends the screensaver if it is showing (used by the web remote's "Home"). */
    static void stopDreaming() {
        FrameDream d = active;
        if (d != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(d::finish);
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Interactive so stray hand-gesture sensor events don't end it; see dispatchKeyEvent.
        setInteractive(true);
        setFullscreen(true);
        view = new SlideshowView(this);
        setContentView(view);
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        switch (view.onKey(event)) {
            case EXIT:
                finish();
                return true;
            case HANDLED:
                return true;
            default:
                // Ignore gesture-sensor noise; other real keys (e.g. typing) end the screensaver.
                String name = android.view.KeyEvent.keyCodeToString(event.getKeyCode());
                if (name.contains("GESTURE")) return true;
                finish();
                return true;
        }
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        if (event.getAction() == android.view.MotionEvent.ACTION_UP) finish();
        return true;
    }

    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent event) {
        return true; // mouse movement shouldn't end it; a click (touch event) does
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        active = this;
        view.start();
    }

    @Override
    public void onDreamingStopped() {
        active = null;
        view.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (view != null) view.release();
        super.onDetachedFromWindow();
    }
}
