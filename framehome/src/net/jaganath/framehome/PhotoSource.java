package net.jaganath.framehome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The slideshow's photo list and cache, independent of where photos come from (see
 * {@link Providers}). Photos are prepared once (downscaled to screen size, rotated upright) and
 * kept on the device, so the slideshow keeps running when the source is offline.
 * All methods except config() do network/disk I/O: call them off the main thread.
 */
final class PhotoSource {
    private static final long REFRESH_MS = 15 * 60_000L;
    private static final int MAX_CACHED = 1500;
    private static final int MAX_SIDE = 2048;

    /** Legacy Immich config pushed by build.sh (immich.json); settings from the remote override it. */
    static final class Config {
        String url, apiKey, albumId;
        int intervalSeconds = 30;
    }

    /** What the info line shows for a photo: when and where it was taken. */
    static final class Meta {
        String when;  // "2026-10-25 12:31", local time where the photo was taken
        String place; // "Poway, CA" (Immich only)

        static Meta fromImmich(JSONObject a) {
            Meta m = new Meta();
            String local = a.optString("localDateTime", "");
            if (local.length() >= 16) m.when = local.substring(0, 16).replace('T', ' ');
            JSONObject e = a.optJSONObject("exifInfo");
            if (e != null) m.place = placeName(str(e, "city"), str(e, "state"), str(e, "country"));
            return m;
        }

        static Meta fromExif(ExifInterface x) {
            Meta m = new Meta();
            String dt = x.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
            if (dt == null) dt = x.getAttribute(ExifInterface.TAG_DATETIME);
            if (dt != null && dt.length() >= 16) {
                m.when = dt.substring(0, 10).replace(':', '-') + " " + dt.substring(11, 16);
            }
            return m;
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            if (when != null) o.put("w", when);
            if (place != null) o.put("p", place);
            return o;
        }

        static Meta fromJson(JSONObject o) {
            Meta m = new Meta();
            m.when = o.optString("w", null);
            m.place = o.optString("p", null);
            return m;
        }

        private static String str(JSONObject o, String k) {
            String v = o.isNull(k) ? null : o.optString(k, null);
            return v == null || v.trim().isEmpty() ? null : v.trim();
        }

        static String placeName(String city, String state, String country) {
            if (city == null && state == null && country == null) return null;
            boolean us = country != null && (country.equals("United States") || country.equals("USA")
                    || country.equalsIgnoreCase("us"));
            String region = us ? usState(state) : country;
            if (city != null) return region == null || region.equalsIgnoreCase(city) ? city : city + ", " + region;
            if (state != null) return us || country == null ? state : state + ", " + country;
            return country;
        }

        private static String usState(String state) {
            if (state == null) return null;
            String[] names = {"Alabama","AL","Alaska","AK","Arizona","AZ","Arkansas","AR","California","CA",
                "Colorado","CO","Connecticut","CT","Delaware","DE","Florida","FL","Georgia","GA","Hawaii","HI",
                "Idaho","ID","Illinois","IL","Indiana","IN","Iowa","IA","Kansas","KS","Kentucky","KY",
                "Louisiana","LA","Maine","ME","Maryland","MD","Massachusetts","MA","Michigan","MI",
                "Minnesota","MN","Mississippi","MS","Missouri","MO","Montana","MT","Nebraska","NE",
                "Nevada","NV","New Hampshire","NH","New Jersey","NJ","New Mexico","NM","New York","NY",
                "North Carolina","NC","North Dakota","ND","Ohio","OH","Oklahoma","OK","Oregon","OR",
                "Pennsylvania","PA","Rhode Island","RI","South Carolina","SC","South Dakota","SD",
                "Tennessee","TN","Texas","TX","Utah","UT","Vermont","VT","Virginia","VA","Washington","WA",
                "West Virginia","WV","Wisconsin","WI","Wyoming","WY","District of Columbia","DC"};
            for (int i = 0; i < names.length; i += 2) if (names[i].equalsIgnoreCase(state)) return names[i + 1];
            return state;
        }
    }

    private static PhotoSource instance;

    static synchronized PhotoSource get(Context c) {
        if (instance == null) instance = new PhotoSource(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final File cacheDir;
    private final File listFile;
    private final Random random = new Random();
    private Providers.Provider provider;
    private List<String> ids = new ArrayList<>();
    private final Map<String, Long> taken = new HashMap<>();
    private final Map<String, Meta> metas = new HashMap<>();
    private final Map<String, String> fileToId = new HashMap<>();
    private final Deque<String> deck = new ArrayDeque<>();
    private long lastRefresh;
    private String keyOfList;
    private String lastError;

    private PhotoSource(Context c) {
        ctx = c;
        cacheDir = new File(c.getCacheDir(), "photos");
        cacheDir.mkdirs();
        listFile = new File(c.getFilesDir(), "album.json");
        readList();
    }

    static Config loadConfig(Context c) {
        Config cfg = new Config();
        try {
            File f = new File(c.getExternalFilesDir(null), "immich.json");
            JSONObject o = new JSONObject(new String(Providers.readAll(new FileInputStream(f)), StandardCharsets.UTF_8));
            cfg.url = o.optString("url", "").replaceAll("/+$", "");
            cfg.apiKey = o.optString("apiKey", null);
            cfg.albumId = o.optString("albumId", null);
            cfg.intervalSeconds = Math.max(5, o.optInt("intervalSeconds", 30));
            if (cfg.url.isEmpty()) cfg.url = null;
            if (cfg.albumId != null && cfg.albumId.isEmpty()) cfg.albumId = null;
        } catch (Exception ignored) {
        }
        return cfg;
    }

    synchronized String lastError() { return lastError; }

    synchronized int count() { return ids.size(); }

    /** Details for a photo returned by next(), or null if unknown. */
    synchronized Meta metaFor(File f) {
        String id = f == null ? null : fileToId.get(f.getPath());
        return id == null ? null : metas.get(id);
    }

    /** Place to show for a photo (Immich knows it; other sources don't). */
    String placeFor(Meta m) {
        return m == null ? null : m.place;
    }

    /** Source settings changed or the user asked to reload: rebuild the provider and list. */
    synchronized void invalidate() {
        if (provider != null) provider.close();
        provider = null;
        lastRefresh = 0;
    }

    /** Settings that only affect ordering changed: start a fresh pass with the new order. */
    synchronized void reshuffle() {
        deck.clear();
    }

    private Providers.Provider provider() {
        if (provider == null) provider = Providers.fromSettings(ctx);
        return provider;
    }

    /** Next photo to show, as a prepared file, or null if nothing is available at all. */
    synchronized File next() {
        refreshIfDue();
        for (int attempts = 0; attempts < 5; attempts++) {
            if (deck.isEmpty()) {
                List<String> list = new ArrayList<>(ids);
                if (list.isEmpty()) return null;
                if ("shuffle".equals(Prefs.order(ctx))) {
                    list = recencyShuffle(list);
                } else {
                    final int dir = "oldest".equals(Prefs.order(ctx)) ? 1 : -1;
                    list.sort((a, b) -> dir * Long.compare(takenOf(a), takenOf(b)));
                }
                deck.addAll(list);
            }
            File f = prepared(deck.poll());
            if (f != null) return f;
        }
        return null;
    }

    private long takenOf(String id) {
        Long t = taken.get(id);
        return t == null ? 0 : t;
    }

    private void refreshIfDue() {
        Providers.Provider p = provider();
        boolean sourceChanged = !p.key().equals(keyOfList);
        if (!sourceChanged && !ids.isEmpty() && System.currentTimeMillis() - lastRefresh < REFRESH_MS) return;
        try {
            List<Providers.Photo> photos = p.list();
            List<String> fresh = new ArrayList<>(photos.size());
            for (Providers.Photo ph : photos) {
                fresh.add(ph.id);
                // Keep a better date (from EXIF) found earlier; otherwise use the source's date.
                if (ph.taken > 0 && (!taken.containsKey(ph.id) || sourceChanged)) taken.put(ph.id, ph.taken);
                if (ph.meta != null) metas.put(ph.id, ph.meta);
            }
            List<String> previous = ids;
            ids = fresh;
            lastRefresh = System.currentTimeMillis();
            lastError = null;
            if (sourceChanged) {
                deck.clear();
                keyOfList = p.key();
                writeList();
            } else if (p instanceof Providers.ImmichProvider) {
                writeList(); // keep photo details current (place names can change in Immich)
            } else if (!fresh.equals(previous)) {
                // Photos added/removed: keep the current pass so every photo is still shown, drop
                // removed ones, and show newly added photos soon.
                Set<String> now = new HashSet<>(fresh);
                deck.removeIf(id -> !now.contains(id));
                Set<String> before = new HashSet<>(previous);
                for (String id : fresh) if (!before.contains(id)) deck.addFirst(id);
                writeList();
            }
        } catch (Exception e) {
            lastError = "Can't read photos: " + e.getMessage();
            lastRefresh = System.currentTimeMillis() - REFRESH_MS + 60_000L; // retry in a minute
            p.close(); // reconnect next time
        }
    }

    /** Cached, screen-ready file for a photo; prepares (downloads/scales/rotates) it if needed. */
    private File prepared(String id) {
        if (id == null) return null;
        Providers.Provider p = provider();
        File f = new File(cacheDir, cacheName(p, id));
        fileToId.put(f.getPath(), id);
        if (f.length() > 0) {
            f.setLastModified(System.currentTimeMillis());
            return f;
        }
        File tmp = new File(cacheDir, f.getName() + ".tmp");
        try {
            p.download(new Providers.Photo(id, takenOf(id)), tmp);
            if (!p.readyToShow()) {
                long exifTime = exifTaken(tmp);
                if (exifTime > 0) taken.put(id, exifTime);
                try {
                    metas.put(id, Meta.fromExif(new ExifInterface(tmp.getPath())));
                    writeList();
                } catch (Exception ignored) {
                }
                if (!normalize(tmp, f)) return null;
            } else if (!tmp.renameTo(f)) {
                return null;
            }
            trimCache();
            return f;
        } catch (Exception e) {
            lastError = "Can't load photo: " + e.getMessage();
            if (!p.readyToShow()) p.close(); // reconnect next time (e.g. SMB session dropped)
            return null;
        } finally {
            tmp.delete();
        }
    }

    /** Immich assets keep their id as the file name; other sources use a hash of source+path. */
    private static String cacheName(Providers.Provider p, String id) {
        if (p instanceof Providers.ImmichProvider) return id + ".jpg";
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest((p.key() + "|" + id).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("f");
            for (int i = 0; i < 12; i++) sb.append(String.format(Locale.ROOT, "%02x", d[i]));
            return sb.append(".jpg").toString();
        } catch (Exception e) {
            return Integer.toHexString((p.key() + id).hashCode()) + ".jpg";
        }
    }

    /** Downscales to at most MAX_SIDE, applies EXIF rotation, saves as JPEG. */
    private static boolean normalize(File src, File dest) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(src.getPath(), o);
        if (o.outWidth <= 0) return false;
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeFile(src.getPath(), o);
        if (bmp == null) return false;
        Matrix m = new Matrix();
        float scale = Math.min(1f, (float) MAX_SIDE / Math.max(bmp.getWidth(), bmp.getHeight()));
        if (scale < 1f) m.postScale(scale, scale);
        int rotation = 0;
        boolean flip = false;
        try {
            switch (new ExifInterface(src.getPath()).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90: rotation = 90; break;
                case ExifInterface.ORIENTATION_ROTATE_180: rotation = 180; break;
                case ExifInterface.ORIENTATION_ROTATE_270: rotation = 270; break;
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: flip = true; break;
                default: break;
            }
        } catch (Exception ignored) {
        }
        if (flip) m.postScale(-1, 1);
        if (rotation != 0) m.postRotate(rotation);
        Bitmap out = m.isIdentity() ? bmp
                : Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        File tmp = new File(dest.getPath() + ".part");
        try (OutputStream os = new FileOutputStream(tmp)) {
            out.compress(Bitmap.CompressFormat.JPEG, 88, os);
        } finally {
            if (out != bmp) out.recycle();
            bmp.recycle();
        }
        return tmp.renameTo(dest);
    }

    private static long exifTaken(File f) {
        try {
            String s = new ExifInterface(f.getPath()).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
            if (s == null) return 0;
            java.util.Date d = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.ROOT).parse(s);
            return d == null ? 0 : d.getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private void trimCache() {
        File[] files = cacheDir.listFiles((d, n) -> n.endsWith(".jpg"));
        if (files == null || files.length <= MAX_CACHED) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < files.length - MAX_CACHED; i++) files[i].delete();
    }

    // ------------------------------------------------------------------ persistence

    private void readList() {
        try {
            String raw = new String(Providers.readAll(new FileInputStream(listFile)), StandardCharsets.UTF_8);
            JSONArray a;
            if (raw.trim().startsWith("{")) {
                JSONObject o = new JSONObject(raw);
                keyOfList = o.optString("source", null);
                a = o.getJSONArray("photos");
            } else {
                a = new JSONArray(raw); // older format (Immich only)
            }
            for (int i = 0; i < a.length(); i++) {
                Object item = a.get(i);
                if (item instanceof JSONObject) {
                    String id = ((JSONObject) item).getString("id");
                    ids.add(id);
                    long t = ((JSONObject) item).optLong("t", 0);
                    if (t > 0) taken.put(id, t);
                    JSONObject mj = ((JSONObject) item).optJSONObject("i");
                    if (mj != null) metas.put(id, Meta.fromJson(mj));
                } else {
                    ids.add(String.valueOf(item));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void writeList() {
        try (OutputStream out = new FileOutputStream(listFile)) {
            JSONArray a = new JSONArray();
            for (String id : ids) {
                JSONObject o = new JSONObject().put("id", id);
                Long t = taken.get(id);
                if (t != null) o.put("t", t);
                Meta m = metas.get(id);
                if (m != null) o.put("i", m.toJson());
                a.put(o);
            }
            JSONObject root = new JSONObject().put("source", keyOfList == null ? "" : keyOfList).put("photos", a);
            out.write(root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ recency-biased shuffle

    /**
     * One pass through the album, shuffled with a bias towards recent photos. Every photo appears
     * at least once per pass; recent ones tend to come earlier and may appear an extra time, kept
     * well apart. The boost halves every 6 months of age.
     */
    private List<String> recencyShuffle(List<String> list) {
        double k;
        switch (Prefs.recency(ctx)) {
            case "off": k = 0; break;
            case "moderate": k = 2.5; break;
            case "strong": k = 5; break;
            default: k = 1; // slight
        }
        if (k == 0) {
            Collections.shuffle(list, random);
            return list;
        }
        long now = System.currentTimeMillis();
        final class Slot { String id; double key; }
        List<Slot> slots = new ArrayList<>();
        for (String id : list) {
            Long t = taken.get(id);
            double ageDays = t == null ? 3650 : Math.max(0, (now - t) / 86_400_000.0);
            double boost = k * Math.pow(0.5, ageDays / 182.0);
            double weight = 1 + boost;
            int copies = 1 + (int) Math.floor(boost * 0.5 + random.nextDouble());
            for (int c = 0; c < copies; c++) {
                Slot s = new Slot();
                s.id = id;
                s.key = Math.pow(random.nextDouble(), 1.0 / weight); // weighted random order
                slots.add(s);
            }
        }
        slots.sort((a, b) -> Double.compare(b.key, a.key));
        int gap = Math.max(1, Math.min(40, list.size() / 3));
        List<String> out = new ArrayList<>(), later = new ArrayList<>();
        Map<String, Integer> lastAt = new HashMap<>();
        for (Slot s : slots) {
            Integer last = lastAt.get(s.id);
            if (last != null && out.size() - last < gap) {
                later.add(s.id);
                continue;
            }
            lastAt.put(s.id, out.size());
            out.add(s.id);
        }
        for (String id : later) {
            Integer last = lastAt.get(id);
            if (last == null || out.size() - last >= gap) {
                lastAt.put(id, out.size());
                out.add(id);
            }
        }
        return out;
    }
}
