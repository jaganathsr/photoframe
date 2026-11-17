package net.jaganath.framehome;

import android.content.Context;
import android.widget.TextView;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** One small line on the slideshow: the date a photo was taken and where ("Apr 8, 2023 · Paradise, NV"). */
final class InfoPanel extends TextView {

    InfoPanel(Context c) {
        super(c);
        // Bebas Neue (SIL Open Font License, see docs/licenses): tall and condensed, all caps.
        setTextSize(20);
        setLetterSpacing(0.06f);
        setTextColor(0xEBFFFFFF);
        setTypeface(c.getResources().getFont(R.font.bebas_neue_regular));
        setShadowLayer(6f, 0f, 1.5f, 0xCC000000);
        setSingleLine(true);
    }

    /** Shows the line for a photo, or hides it if switched off or nothing is known. */
    void show(PhotoSource.Meta m, String place) {
        String line = Prefs.photoInfo(getContext()) ? line(m, place) : null;
        setText(line == null ? "" : line);
        setVisibility(line == null ? GONE : VISIBLE);
    }

    /** "Apr 8, 2023 · Paradise, NV", or whichever part is known; null if neither. */
    static String line(PhotoSource.Meta m, String place) {
        String date = null;
        if (m != null && m.when != null && m.when.length() >= 10) {
            try {
                Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(m.when.substring(0, 10));
                if (d != null) date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(d);
            } catch (Exception ignored) {
            }
        }
        if (date != null && place != null) return date + "  ·  " + place;
        return date != null ? date : place;
    }
}
