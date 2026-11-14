package net.jaganath.framehome;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

/**
 * Every user-adjustable setting, with defaults and validation. Shared by the on-device screens
 * and the web remote. Slideshow settings are read live, so changes apply on the next photo.
 */
final class Prefs {
    static final String[] TRANSITIONS = {"crossfade", "slide", "zoom", "kenburns", "none"};
    static final String[] ORDERS = {"shuffle", "newest", "oldest"};
    static final String[] FITS = {"smart", "fill", "fit"};
    static final String[] RECENCY = {"off", "slight", "moderate", "strong"};

    private Prefs() {}

    private static SharedPreferences p(Context c) { return Schedule.prefs(c); }

    static final String[] SOURCES = {"immich", "folder", "smb"};
    static final String DEFAULT_FOLDER = "/sdcard/Pictures/Frame";

    static String str(Context c, String key, String def) { return p(c).getString(key, def); }
    static boolean bool(Context c, String key, boolean def) { return p(c).getBoolean(key, def); }

    /** Photo source; defaults to Immich if it was configured by the installer, else a folder. */
    static String source(Context c) {
        String s = p(c).getString("source", null);
        if (s != null) return s;
        return PhotoSource.loadConfig(c).url != null ? "immich" : "folder";
    }

    /** Source settings for the web remote. Secrets are never sent back, only whether they're set. */
    static JSONObject sourceJson(Context c) throws Exception {
        PhotoSource.Config cfg = PhotoSource.loadConfig(c);
        return new JSONObject()
                .put("source", source(c))
                .put("immichUrl", str(c, "immichUrl", cfg.url == null ? "" : cfg.url))
                .put("immichKeySet", !str(c, "immichKey", cfg.apiKey == null ? "" : cfg.apiKey).isEmpty())
                .put("folderPath", str(c, "folderPath", DEFAULT_FOLDER))
                .put("smbHost", str(c, "smbHost", ""))
                .put("smbShare", str(c, "smbShare", ""))
                .put("smbPath", str(c, "smbPath", ""))
                .put("smbUser", str(c, "smbUser", ""))
                .put("smbDomain", str(c, "smbDomain", ""))
                .put("smbPassSet", !str(c, "smbPass", "").isEmpty())
                .put("recursive", bool(c, "recursive", true));
    }

    /**
     * Tests a new photo source and saves it only if it works. Empty secret fields keep the saved
     * value. Returns {ok, count} or {ok:false, error}.
     */
    static JSONObject testAndSaveSource(Context c, JSONObject o) throws Exception {
        String source = o.optString("source", source(c));
        boolean known = false;
        for (String s : SOURCES) known |= s.equals(source);
        if (!known) return new JSONObject().put("ok", false).put("error", "Unknown source");

        // Build the candidate settings on top of the current ones.
        Map<String, Object> next = new HashMap<>();
        next.put("source", source);
        for (String k : new String[]{"immichUrl", "folderPath", "smbHost", "smbShare", "smbPath", "smbUser", "smbDomain"}) {
            if (o.has(k)) next.put(k, o.getString(k).trim());
        }
        for (String k : new String[]{"immichKey", "smbPass"}) {
            if (o.has(k) && !o.getString(k).isEmpty()) next.put(k, o.getString(k));
        }
        if (o.has("recursive")) next.put("recursive", o.getBoolean("recursive"));

        Map<String, ?> before = new HashMap<>(p(c).getAll());
        SharedPreferences.Editor e = p(c).edit();
        for (Map.Entry<String, Object> en : next.entrySet()) {
            if (en.getValue() instanceof Boolean) e.putBoolean(en.getKey(), (Boolean) en.getValue());
            else e.putString(en.getKey(), (String) en.getValue());
        }
        e.commit();
        Providers.Provider candidate = Providers.fromSettings(c);
        try {
            if (candidate instanceof Providers.ImmichProvider) {
                Providers.ImmichProvider im = (Providers.ImmichProvider) candidate;
                im.albums(); // checks the server address and API key
                if (im.albumId.isEmpty()) {
                    PhotoSource.get(c).invalidate();
                    return new JSONObject().put("ok", true).put("needsAlbum", true);
                }
            }
            int count = candidate.list().size();
            if (count == 0) throw new Exception("Connected, but found no photos there");
            PhotoSource.get(c).invalidate();
            SlideshowView.broadcast("reload");
            return new JSONObject().put("ok", true).put("count", count);
        } catch (Exception ex) {
            // Roll back so a typo doesn't break a working slideshow.
            SharedPreferences.Editor r = p(c).edit();
            for (String k : next.keySet()) {
                Object old = before.get(k);
                if (old == null) r.remove(k);
                else if (old instanceof Boolean) r.putBoolean(k, (Boolean) old);
                else r.putString(k, String.valueOf(old));
            }
            r.commit();
            String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            return new JSONObject().put("ok", false).put("error", msg);
        } finally {
            candidate.close();
        }
    }

    static int intervalSeconds(Context c) {
        return p(c).getInt("intervalSeconds", PhotoSource.loadConfig(c).intervalSeconds);
    }
    static String transition(Context c) { return p(c).getString("transition", "crossfade"); }
    static int transitionMs(Context c) { return p(c).getInt("transitionMs", 1500); }
    static String order(Context c) { return p(c).getString("order", "shuffle"); }
    static String fit(Context c) { return p(c).getString("fit", "smart"); }
    static String recency(Context c) { return p(c).getString("recency", "slight"); }
    static boolean showClock(Context c) { return p(c).getBoolean("showClock", true); }
    static int idleMinutes(Context c) { return p(c).getInt("idleMinutes", 2); }
    static String albumId(Context c) {
        String a = p(c).getString("albumId", null);
        return a != null ? a : PhotoSource.loadConfig(c).albumId;
    }

    /** 4-digit PIN for the web remote, created on first use. */
    static String pin(Context c) {
        String s = p(c).getString("pin", null);
        if (s == null) {
            s = String.format(java.util.Locale.US, "%04d", new SecureRandom().nextInt(10000));
            p(c).edit().putString("pin", s).apply();
        }
        return s;
    }

    /** Screen brightness 0-100, or -1 for automatic. */
    static int brightness(Context c) {
        try {
            if (Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE)
                    == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) return -1;
            return Math.round(Settings.System.getInt(c.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS) * 100f / 255f);
        } catch (Settings.SettingNotFoundException e) {
            return -1;
        }
    }

    static boolean setBrightness(Context c, int value) {
        if (!Settings.System.canWrite(c)) return false;
        if (value < 0) {
            Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        } else {
            Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS,
                    Math.max(3, Math.min(255, Math.round(value * 255f / 100f))));
        }
        return true;
    }

    static JSONObject toJson(Context c) throws Exception {
        return new JSONObject()
                .put("intervalSeconds", intervalSeconds(c))
                .put("transition", transition(c))
                .put("transitionMs", transitionMs(c))
                .put("order", order(c))
                .put("fit", fit(c))
                .put("recency", recency(c))
                .put("showClock", showClock(c))
                .put("albumId", albumId(c) == null ? JSONObject.NULL : albumId(c))
                .put("idleMinutes", idleMinutes(c))
                .put("scheduleEnabled", Schedule.enabled(c))
                .put("sleepTime", hhmm(Schedule.sleepMinute(c)))
                .put("wakeTime", hhmm(Schedule.wakeMinute(c)))
                .put("slideshowOnWake", Schedule.fotooOnWake(c))
                .put("slideshowOnBoot", Schedule.fotooOnBoot(c))
                .put("brightness", brightness(c));
    }

    /** Applies any recognised keys from the web remote. Returns an error message or null. */
    static String apply(Context c, JSONObject o) {
        SharedPreferences.Editor e = p(c).edit();
        boolean reloadPhotos = false, reschedule = false, reshuffle = false;
        try {
            if (o.has("intervalSeconds")) e.putInt("intervalSeconds", clamp(o.getInt("intervalSeconds"), 5, 7200));
            if (o.has("transition")) e.putString("transition", oneOf(o.getString("transition"), TRANSITIONS));
            if (o.has("transitionMs")) e.putInt("transitionMs", clamp(o.getInt("transitionMs"), 0, 5000));
            if (o.has("fit")) e.putString("fit", oneOf(o.getString("fit"), FITS));
            if (o.has("recency")) {
                e.putString("recency", oneOf(o.getString("recency"), RECENCY));
                reshuffle = true;
            }
            if (o.has("showClock")) e.putBoolean("showClock", o.getBoolean("showClock"));
            if (o.has("idleMinutes")) e.putInt("idleMinutes", clamp(o.getInt("idleMinutes"), 0, 240));
            if (o.has("order")) {
                e.putString("order", oneOf(o.getString("order"), ORDERS));
                reloadPhotos = true;
            }
            if (o.has("albumId")) {
                String album = o.getString("albumId");
                if (!album.matches("[0-9a-fA-F-]{36}")) throw new IllegalArgumentException("album");
                e.putString("albumId", album);
                reloadPhotos = true;
            }
            if (o.has("scheduleEnabled")) { e.putBoolean("enabled", o.getBoolean("scheduleEnabled")); reschedule = true; }
            if (o.has("sleepTime")) { e.putInt("sleep", minutes(o.getString("sleepTime"))); reschedule = true; }
            if (o.has("wakeTime")) { e.putInt("wake", minutes(o.getString("wakeTime"))); reschedule = true; }
            if (o.has("slideshowOnWake")) e.putBoolean("fotooOnWake", o.getBoolean("slideshowOnWake"));
            if (o.has("slideshowOnBoot")) e.putBoolean("fotooOnBoot", o.getBoolean("slideshowOnBoot"));
        } catch (Exception ex) {
            return "Invalid value: " + ex.getMessage();
        }
        e.commit();
        if (o.has("brightness")) {
            try {
                if (!setBrightness(c, o.getInt("brightness"))) return "Brightness permission missing";
            } catch (Exception ex) {
                return "Invalid brightness";
            }
        }
        if (reschedule) Schedule.reschedule(c);
        if (reloadPhotos) PhotoSource.get(c).invalidate();
        else if (reshuffle) PhotoSource.get(c).reshuffle();
        SlideshowView.broadcast(reloadPhotos ? "reload" : "settings");
        return null;
    }

    static String hhmm(int minutes) {
        return String.format(java.util.Locale.US, "%02d:%02d", minutes / 60, minutes % 60);
    }

    private static int minutes(String hhmm) {
        String[] parts = hhmm.trim().split(":");
        int h = Integer.parseInt(parts[0]), m = Integer.parseInt(parts[1]);
        if (h < 0 || h > 23 || m < 0 || m > 59) throw new IllegalArgumentException(hhmm);
        return h * 60 + m;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static String oneOf(String v, String[] allowed) {
        for (String a : allowed) if (a.equals(v)) return v;
        throw new IllegalArgumentException(v);
    }
}
