package com.jay.framehome;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * Photos from an Immich album. Config is a JSON file pushed by build.sh to the app's external
 * files dir: {"url": "...", "apiKey": "...", "albumId": "...", "intervalSeconds": 30}.
 * Previews are cached on the device so the slideshow keeps running if the NAS is unreachable.
 * All methods except config() do network/disk I/O: call them off the main thread.
 */
final class PhotoSource {
    private static final long REFRESH_MS = 15 * 60_000L;
    private static final int MAX_CACHED = 1500;

    static final class Config {
        String url, apiKey, albumId;
        int intervalSeconds = 30;
        boolean valid() { return url != null && apiKey != null && albumId != null; }
    }

    private static PhotoSource instance;

    static synchronized PhotoSource get(Context c) {
        if (instance == null) instance = new PhotoSource(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final File cacheDir;
    private final File listFile;
    private Config config;
    private List<String> ids = new ArrayList<>();
    /** When each photo was taken (ms since epoch), for the recency boost. */
    private final java.util.Map<String, Long> taken = new java.util.HashMap<>();
    private final java.util.Random random = new java.util.Random();
    private final Deque<String> deck = new ArrayDeque<>();
    private long lastRefresh;
    private String albumOfList;
    private String lastError;

    private PhotoSource(Context c) {
        ctx = c;
        cacheDir = new File(c.getCacheDir(), "photos");
        cacheDir.mkdirs();
        listFile = new File(c.getFilesDir(), "album.json");
    }

    synchronized Config config() {
        if (config == null) config = loadConfig(ctx);
        return config;
    }

    static Config loadConfig(Context c) {
        Config cfg = new Config();
        try {
            File f = new File(c.getExternalFilesDir(null), "immich.json");
            JSONObject o = new JSONObject(new String(readAll(new FileInputStream(f)), StandardCharsets.UTF_8));
            cfg.url = o.optString("url", "").replaceAll("/+$", "");
            cfg.apiKey = o.optString("apiKey", null);
            cfg.albumId = o.optString("albumId", null);
            cfg.intervalSeconds = Math.max(5, o.optInt("intervalSeconds", 30));
            if (cfg.url.isEmpty()) cfg.url = null;
        } catch (Exception ignored) {
        }
        return cfg;
    }

    synchronized String lastError() { return lastError; }

    synchronized int count() { return ids.isEmpty() ? cachedIds().size() : ids.size(); }

    /** Settings changed (album/order) or user asked to reload: fetch the list again soon. */
    synchronized void invalidate() {
        config = null;
        ids = new ArrayList<>();
        deck.clear();
        lastRefresh = 0;
        albumOfList = null;
    }

    /** Settings that only affect ordering changed: start a fresh pass with the new order. */
    synchronized void reshuffle() {
        deck.clear();
    }

    /** Next photo to show, as a cached file, or null if nothing is available at all. */
    synchronized File next() {
        refreshIfDue();
        for (int attempts = 0; attempts < 5; attempts++) {
            if (deck.isEmpty()) {
                List<String> list = new ArrayList<>(ids.isEmpty() ? cachedIds() : ids);
                if (list.isEmpty()) return null;
                if ("shuffle".equals(Prefs.order(ctx))) list = recencyShuffle(list);
                deck.addAll(list);
            }
            String id = deck.poll();
            File f = fetch(id);
            if (f != null) return f;
        }
        return null;
    }

    private void refreshIfDue() {
        if (!ids.isEmpty() && System.currentTimeMillis() - lastRefresh < REFRESH_MS) return;
        Config cfg = config();
        String album = Prefs.albumId(ctx);
        if (album != null) cfg.albumId = album;
        if (!cfg.valid()) {
            lastError = "Immich is not configured";
            if (ids.isEmpty()) ids = readList();
            return;
        }
        try {
            List<String> fresh = new ArrayList<>();
            int page = 1;
            while (page > 0) {
                JSONObject body = new JSONObject()
                        .put("albumIds", new JSONArray().put(cfg.albumId))
                        .put("type", "IMAGE").put("size", 1000).put("page", page)
                        .put("order", "oldest".equals(Prefs.order(ctx)) ? "asc" : "desc");
                JSONObject res = new JSONObject(new String(
                        request(cfg, "POST", "/api/search/metadata", body.toString()),
                        StandardCharsets.UTF_8)).getJSONObject("assets");
                JSONArray items = res.getJSONArray("items");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject a = items.getJSONObject(i);
                    String id = a.getString("id");
                    fresh.add(id);
                    long t = parseTime(a.optString("fileCreatedAt", ""));
                    if (t > 0) taken.put(id, t);
                }
                String nxt = res.optString("nextPage", "");
                page = (nxt.isEmpty() || "null".equals(nxt)) ? 0 : Integer.parseInt(nxt);
            }
            boolean changed = !fresh.equals(ids) || !cfg.albumId.equals(albumOfList);
            List<String> previous = ids;
            String albumBefore = albumOfList;
            ids = fresh;
            albumOfList = cfg.albumId;
            lastRefresh = System.currentTimeMillis();
            lastError = null;
            if (changed) {
                writeList(fresh);
                if (!cfg.albumId.equals(albumBefore)) {
                    deck.clear(); // different album: start over
                } else if (!deck.isEmpty()) {
                    // Same album, photos added/removed: keep the current pass (so every photo is
                    // still shown), drop removed ones, and show newly added photos soon.
                    java.util.Set<String> now = new java.util.HashSet<>(fresh);
                    deck.removeIf(id -> !now.contains(id));
                    java.util.Set<String> before = new java.util.HashSet<>(previous);
                    for (String id : fresh) if (!before.contains(id)) deck.addFirst(id);
                }
            }
        } catch (Exception e) {
            lastError = "Can't reach Immich: " + e.getMessage();
            lastRefresh = System.currentTimeMillis() - REFRESH_MS + 60_000L; // retry in a minute
            if (ids.isEmpty()) ids = readList();
        }
    }

    private File fetch(String id) {
        File f = new File(cacheDir, id + ".jpg");
        if (f.length() > 0) {
            f.setLastModified(System.currentTimeMillis());
            return f;
        }
        Config cfg = config();
        if (!cfg.valid()) return null;
        try {
            byte[] data = request(cfg, "GET", "/api/assets/" + id + "/thumbnail?size=preview", null);
            File tmp = new File(cacheDir, id + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(data);
            }
            if (!tmp.renameTo(f)) return null;
            trimCache();
            return f;
        } catch (Exception e) {
            lastError = "Can't load photo: " + e.getMessage();
            return null;
        }
    }

    private List<String> cachedIds() {
        List<String> out = new ArrayList<>();
        String[] names = cacheDir.list();
        if (names != null) for (String n : names) if (n.endsWith(".jpg")) out.add(n.substring(0, n.length() - 4));
        return out;
    }

    private void trimCache() {
        File[] files = cacheDir.listFiles((d, n) -> n.endsWith(".jpg"));
        if (files == null || files.length <= MAX_CACHED) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < files.length - MAX_CACHED; i++) files[i].delete();
    }

    private List<String> readList() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(new String(readAll(new FileInputStream(listFile)), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                Object o = a.get(i);
                if (o instanceof JSONObject) {
                    String id = ((JSONObject) o).getString("id");
                    out.add(id);
                    long t = ((JSONObject) o).optLong("t", 0);
                    if (t > 0) taken.put(id, t);
                } else {
                    out.add(String.valueOf(o)); // older format: ids only
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void writeList(List<String> list) {
        try (OutputStream out = new FileOutputStream(listFile)) {
            JSONArray a = new JSONArray();
            for (String id : list) {
                JSONObject o = new JSONObject().put("id", id);
                Long t = taken.get(id);
                if (t != null) o.put("t", t);
                a.put(o);
            }
            out.write(a.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static long parseTime(String iso) {
        try {
            return java.time.Instant.parse(iso).toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

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
                // Weighted random order (higher weight -> earlier on average).
                s.key = Math.pow(random.nextDouble(), 1.0 / weight);
                slots.add(s);
            }
        }
        slots.sort((a, b) -> Double.compare(b.key, a.key));
        // Keep repeats of the same photo well apart.
        int gap = Math.max(1, Math.min(40, list.size() / 3));
        List<String> out = new ArrayList<>(), later = new ArrayList<>();
        java.util.Map<String, Integer> lastAt = new java.util.HashMap<>();
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

    private static byte[] request(Config cfg, String method, String path, String jsonBody) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(cfg.url + path).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(30_000);
        c.setRequestMethod(method);
        c.setRequestProperty("x-api-key", cfg.apiKey);
        c.setRequestProperty("Accept", "application/json");
        if (jsonBody != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        if (code / 100 != 2) {
            c.disconnect();
            throw new Exception("HTTP " + code);
        }
        try (InputStream in = c.getInputStream()) {
            return readAll(in);
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}
