package net.jaganath.framehome;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

/** Shared look: dark background, large rounded tiles with a clear focus/hover highlight. */
final class Ui {
    static final int BG = 0xFF0E0F13;
    static final int TEXT = 0xFFFFFFFF;
    static final int MUTED = 0xFF9AA0AE;
    static final int ACCENT = 0xFF5B8CFF;

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private static GradientDrawable rounded(Context c, int fill, int stroke, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, 20));
        g.setStroke(dp(c, strokeDp), stroke);
        return g;
    }

    static Drawable tileBackground(Context c) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, rounded(c, 0xFF34405E, ACCENT, 4));
        s.addState(new int[]{android.R.attr.state_focused}, rounded(c, 0xFF243154, ACCENT, 4));
        s.addState(new int[]{android.R.attr.state_hovered}, rounded(c, 0xFF243154, ACCENT, 3));
        s.addState(new int[]{android.R.attr.state_selected}, rounded(c, 0xFF243154, ACCENT, 4));
        s.addState(new int[]{}, rounded(c, 0xFF1A1D24, 0xFF2A2E38, 1));
        return s;
    }

    static Button tile(Context c, String title, String subtitle, View.OnClickListener l) {
        Button b = new Button(c);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(22);
        b.setGravity(Gravity.CENTER);
        b.setBackground(tileBackground(c));
        b.setStateListAnimator(null);
        b.setPadding(dp(c, 10), dp(c, 16), dp(c, 10), dp(c, 16));
        b.setFocusable(true);
        b.setOnClickListener(l);
        setTileText(b, title, subtitle);
        return b;
    }

    static void setTileText(TextView b, String title, String subtitle) {
        SpannableStringBuilder sb = new SpannableStringBuilder(title);
        if (subtitle != null && !subtitle.isEmpty()) {
            int start = sb.length();
            sb.append("\n").append(subtitle);
            sb.setSpan(new RelativeSizeSpan(0.6f), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(MUTED), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        b.setText(sb);
    }

    static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    @SuppressWarnings("deprecation")
    static void immersive(Activity a) {
        a.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
}
