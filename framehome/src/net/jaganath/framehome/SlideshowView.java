package net.jaganath.framehome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextClock;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen photo slideshow with selectable transitions and a small clock. Used by the
 * slideshow activity and the screensaver. Settings (duration, transition, fit, clock) are read
 * from {@link Prefs} for every photo, so changes from the web remote apply on the next photo.
 */
final class SlideshowView extends FrameLayout {
    /** Running slideshows, so the web remote can send next/prev/reload. */
    private static final Set<SlideshowView> ACTIVE = new CopyOnWriteArraySet<>();
    private static volatile File currentFile;

    static boolean anyActive() { return !ACTIVE.isEmpty(); }
    static File currentFile() { return currentFile; }

    /** "next", "prev", "reload" or "settings"; safe to call from any thread. */
    static void broadcast(String command) {
        for (SlideshowView v : ACTIVE) v.main.post(() -> v.command(command));
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final PhotoSource source;
    private final ImageView[] layers = new ImageView[2];
    private final Bitmap[] bitmaps = new Bitmap[2];
    private final TextView message;
    private final TextClock clock;
    private final Deque<File> history = new ArrayDeque<>();
    private final Random random = new Random();
    private ValueAnimator kenBurns;
    private int front = 0;
    private boolean running, loading;
    private final Runnable tick = () -> load(null);

    SlideshowView(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
        source = PhotoSource.get(c);
        for (int i = 0; i < 2; i++) {
            ImageView iv = new ImageView(c);
            iv.setAlpha(0f);
            addView(iv, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            layers[i] = iv;
        }

        message = Ui.text(c, "Loading photos…", 26, 0xCCFFFFFF);
        message.setGravity(Gravity.CENTER);
        addView(message, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        clock = new TextClock(c);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextSize(30);
        clock.setTextColor(0xD9FFFFFF);
        clock.setShadowLayer(8f, 0f, 2f, 0xCC000000);
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        int m = Ui.dp(c, 28);
        lp.setMargins(m, m, m, m);
        addView(clock, lp);
        applySettings();
    }

    enum KeyAction { EXIT, HANDLED, IGNORED }

    /**
     * Keyboard and the frame's hand-gesture sensor. Deliberate keys exit; right/left (arrows or a
     * sideways wave) go to the next/previous photo; anything else, including stray gesture events
     * and keys injected by other apps, is ignored so the slideshow doesn't close by accident.
     */
    KeyAction onKey(KeyEvent e) {
        int code = e.getKeyCode();
        String name = KeyEvent.keyCodeToString(code);
        boolean injected = e.getDeviceId() == KeyCharacterMap.VIRTUAL_KEYBOARD;
        if (e.getAction() == KeyEvent.ACTION_UP) {
            android.util.Log.i("FrameHome", "slideshow key " + name + " device=" + e.getDeviceId()
                    + (injected ? " (injected, ignored for exit)" : ""));
        }
        if (injected && !name.contains("DPAD")) return KeyAction.HANDLED;
        boolean next = code == KeyEvent.KEYCODE_DPAD_RIGHT || name.contains("GESTURE_DIR_RIGHT");
        boolean prev = code == KeyEvent.KEYCODE_DPAD_LEFT || name.contains("GESTURE_DIR_LEFT");
        boolean exit = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE
                || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_SPACE;
        if (e.getAction() != KeyEvent.ACTION_UP) {
            return (next || prev || exit) ? KeyAction.HANDLED : KeyAction.IGNORED;
        }
        if (exit) return KeyAction.EXIT;
        if (next || prev) {
            command(next ? "next" : "prev");
            return KeyAction.HANDLED;
        }
        return KeyAction.IGNORED;
    }

    void start() {
        if (running) return;
        running = true;
        ACTIVE.add(this);
        main.post(tick);
    }

    void stop() {
        running = false;
        ACTIVE.remove(this);
        main.removeCallbacks(tick);
        if (kenBurns != null) kenBurns.cancel();
    }

    void release() {
        stop();
        worker.shutdownNow();
        for (int i = 0; i < 2; i++) {
            layers[i].setImageDrawable(null);
            if (bitmaps[i] != null) bitmaps[i].recycle();
            bitmaps[i] = null;
        }
    }

    private void command(String cmd) {
        if (!running) return;
        switch (cmd) {
            case "next":
                main.removeCallbacks(tick);
                load(null);
                break;
            case "prev":
                if (history.size() >= 2) {
                    history.pollLast();                 // the photo on screen now
                    File previous = history.pollLast(); // re-added when shown
                    main.removeCallbacks(tick);
                    load(previous);
                }
                break;
            case "reload":
                history.clear();
                main.removeCallbacks(tick);
                load(null);
                break;
            default: // "settings"
                applySettings();
                break;
        }
    }

    private void applySettings() {
        clock.setVisibility(Prefs.showClock(getContext()) ? VISIBLE : GONE);
    }

    private void load(File specific) {
        if (!running || loading) return;
        loading = true;
        final int w = Math.max(getWidth(), 1920), h = Math.max(getHeight(), 1080);
        worker.execute(() -> {
            File f = specific != null ? specific : source.next();
            Bitmap bmp = f == null ? null : decode(f, w, h);
            String err = source.lastError();
            main.post(() -> {
                loading = false;
                show(f, bmp, err);
            });
        });
    }

    private void show(File file, Bitmap bmp, String err) {
        if (!running) {
            if (bmp != null) bmp.recycle();
            return;
        }
        if (bmp == null) {
            if (bitmaps[front] == null) {
                message.setText(err != null ? err + "\nRetrying…" : "No photos in the album yet");
                message.setVisibility(VISIBLE);
            }
            main.postDelayed(tick, 30_000);
            return;
        }
        message.setVisibility(GONE);
        applySettings();
        currentFile = file;
        history.addLast(file);
        while (history.size() > 50) history.pollFirst();

        int back = 1 - front;
        ImageView in = layers[back], out = layers[front];
        in.setScaleType(scaleFor(bmp));
        in.setImageBitmap(bmp);
        Bitmap old = bitmaps[back];
        bitmaps[back] = bmp;
        if (old != null) old.recycle();

        long intervalMs = Prefs.intervalSeconds(getContext()) * 1000L;
        transition(in, out, Prefs.transition(getContext()), Prefs.transitionMs(getContext()), intervalMs);
        front = back;
        main.postDelayed(tick, intervalMs);
    }

    private ImageView.ScaleType scaleFor(Bitmap bmp) {
        String fit = Prefs.fit(getContext());
        if ("fill".equals(fit)) return ImageView.ScaleType.CENTER_CROP;
        if ("fit".equals(fit)) return ImageView.ScaleType.FIT_CENTER;
        // smart: fill the screen when the shapes are close, otherwise show the whole photo.
        float viewRatio = (float) getWidth() / Math.max(getHeight(), 1);
        float imgRatio = (float) bmp.getWidth() / bmp.getHeight();
        return Math.abs(imgRatio - viewRatio) / viewRatio < 0.22f
                ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER;
    }

    private void transition(ImageView in, ImageView out, String type, long ms, long intervalMs) {
        if (kenBurns != null) kenBurns.cancel();
        in.animate().cancel();
        out.animate().cancel();
        reset(in);
        in.bringToFront();
        message.bringToFront();
        clock.bringToFront();
        float w = getWidth();
        switch (type) {
            case "none":
                in.setAlpha(1f);
                out.setAlpha(0f);
                break;
            case "slide":
                in.setAlpha(1f);
                in.setTranslationX(w);
                in.animate().translationX(0).setDuration(ms).start();
                out.animate().translationX(-w).setDuration(ms).withEndAction(() -> {
                    out.setAlpha(0f);
                    reset(out);
                }).start();
                break;
            case "zoom":
                in.setAlpha(0f);
                in.setScaleX(1.15f);
                in.setScaleY(1.15f);
                in.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(ms).start();
                out.animate().alpha(0f).setDuration(ms).start();
                break;
            case "kenburns": {
                in.setAlpha(0f);
                in.animate().alpha(1f).setDuration(ms).start();
                out.animate().alpha(0f).setDuration(ms).start();
                // Slow zoom and drift across the photo for the whole time it is shown.
                final float dx = (random.nextFloat() - 0.5f) * 0.06f * w;
                final float dy = (random.nextFloat() - 0.5f) * 0.06f * getHeight();
                final boolean zoomIn = random.nextBoolean();
                kenBurns = ValueAnimator.ofFloat(0f, 1f);
                kenBurns.setDuration(intervalMs + ms);
                kenBurns.setInterpolator(new LinearInterpolator());
                kenBurns.addUpdateListener(a -> {
                    float t = (float) a.getAnimatedValue();
                    float s = zoomIn ? 1f + 0.12f * t : 1.12f - 0.12f * t;
                    in.setScaleX(s);
                    in.setScaleY(s);
                    in.setTranslationX(dx * t);
                    in.setTranslationY(dy * t);
                });
                kenBurns.start();
                break;
            }
            default: // crossfade
                in.setAlpha(0f);
                in.animate().alpha(1f).setDuration(ms).start();
                out.animate().alpha(0f).setDuration(ms).start();
                break;
        }
    }

    private static void reset(ImageView v) {
        v.setTranslationX(0f);
        v.setTranslationY(0f);
        v.setScaleX(1f);
        v.setScaleY(1f);
    }

    private static Bitmap decode(File f, int reqW, int reqH) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) {
            f.delete();
            return null;
        }
        int sample = 1;
        while (o.outWidth / (sample * 2) >= reqW && o.outHeight / (sample * 2) >= reqH) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        try {
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }
}
