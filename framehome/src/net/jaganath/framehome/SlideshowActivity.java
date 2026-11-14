package net.jaganath.framehome;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;

/** The slideshow as a normal full-screen app. Any key, click or Back returns to Frame Home. */
public class SlideshowActivity extends Activity {
    /** True while the slideshow exists (even if something pops up over it). */
    static volatile boolean alive;
    private SlideshowView view;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        alive = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new SlideshowView(this);
        setContentView(view);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.immersive(this);
        view.start();
    }

    @Override
    protected void onPause() {
        view.stop();
        super.onPause();
    }

    @Override
    public void finish() {
        android.util.Log.i("FrameHome", "slideshow closing",
                new Throwable("finish() called from"));
        super.finish();
    }

    @Override
    protected void onDestroy() {
        alive = false;
        view.release();
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        switch (view.onKey(event)) {
            case EXIT:
                finish();
                return true;
            case HANDLED:
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // A real mouse click exits; ignore anything synthetic.
        if (event.getAction() == MotionEvent.ACTION_UP
                && (event.getSource() & android.view.InputDevice.SOURCE_MOUSE)
                        == android.view.InputDevice.SOURCE_MOUSE) {
            finish();
        }
        return true;
    }
}
