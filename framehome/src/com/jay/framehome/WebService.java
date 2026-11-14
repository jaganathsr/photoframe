package com.jay.framehome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tiny HTTP server for the phone remote: serves the web page and a JSON API on port 8080.
 * LAN only (the frame isn't reachable from the internet). Every /api call needs the PIN shown
 * on the frame's home screen; too many wrong PINs lock the remote for a few minutes.
 */
public class WebService extends Service {
    static final int PORT = 8080;
    private static final int MAX_BODY = 64 * 1024;

    // Browsers open spare/speculative connections, so allow plenty of workers.
    private final ExecutorService pool = Executors.newFixedThreadPool(16);
    private final Handler main = new Handler(Looper.getMainLooper());
    private ServerSocket server;
    /** Wrong-PIN tracking per phone/computer (IP address), so one device can't lock out others. */
    private final Map<String, Integer> badPins = new HashMap<>();
    private final Map<String, Long> lockedUntil = new HashMap<>();
    private static final int MAX_BAD_PINS = 10;
    private static final long LOCK_MS = 5 * 60_000L;

    static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, WebService.class));
        } catch (RuntimeException ignored) {
        }
    }

    /** "http://192.168.x.y:8080" or null when there's no network. */
    static String address() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        return "http://" + a.getHostAddress() + ":" + PORT;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("remote", "Remote control",
                NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "remote")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Frame Home remote is on")
                .setContentText(String.valueOf(address()))
                .build();
        startForeground(1, n);
        Thread t = new Thread(this::acceptLoop, "framehome-web");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        pool.shutdownNow();
        super.onDestroy();
    }

    private void acceptLoop() {
        while (true) {
            try {
                server = new ServerSocket(PORT);
                while (!server.isClosed()) {
                    Socket s = server.accept();
                    pool.execute(() -> handle(s));
                }
            } catch (Exception e) {
                if (server != null && server.isClosed() && pool.isShutdown()) return;
                try {
                    Thread.sleep(5000); // port busy or network change: retry
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------------ HTTP plumbing

    private static final class Request {
        String method, path;
        Map<String, String> query = new HashMap<>();
        Map<String, String> headers = new HashMap<>();
        byte[] body = new byte[0];
        InputStream in;   // for streamed uploads (/api/update)
        String ip = "?";
        int length;
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(8_000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            // Browsers now try https:// first. That starts with a TLS handshake (byte 0x16), which
            // this plain-HTTP server can't answer: close at once so the browser falls back to
            // http:// immediately instead of hanging until it times out.
            in.mark(1);
            int first = in.read();
            String ip = sock.getInetAddress().getHostAddress();
            if (first == 0x16) android.util.Log.i("FrameHome", "web: " + ip + " tried https, closed");
            if (first == -1 || first == 0x16) return;
            in.reset();
            OutputStream out = sock.getOutputStream();
            Request r = parse(in);
            if (r == null) return;
            r.ip = sock.getInetAddress().getHostAddress();
            android.util.Log.i("FrameHome", "web: " + r.ip + " " + r.method + " " + r.path
                    + " ua=" + r.headers.getOrDefault("user-agent", "?"));
            route(r, out);
        } catch (Exception ignored) {
        }
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new Exception("line too long");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private static Request parse(InputStream in) throws Exception {
        String first = readLine(in);
        if (first == null) return null;
        String[] parts = first.split(" ");
        if (parts.length < 2) return null;
        Request r = new Request();
        r.method = parts[0];
        String target = parts[1];
        int q = target.indexOf('?');
        r.path = q >= 0 ? target.substring(0, q) : target;
        if (q >= 0) {
            for (String kv : target.substring(q + 1).split("&")) {
                int eq = kv.indexOf('=');
                if (eq > 0) r.query.put(URLDecoder.decode(kv.substring(0, eq), "UTF-8"),
                        URLDecoder.decode(kv.substring(eq + 1), "UTF-8"));
            }
        }
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) r.headers.put(line.substring(0, colon).trim().toLowerCase(),
                    line.substring(colon + 1).trim());
        }
        int len = 0;
        try {
            len = Integer.parseInt(r.headers.getOrDefault("content-length", "0"));
        } catch (NumberFormatException ignored) {
        }
        r.in = in;
        r.length = len;
        if (r.path.equals("/api/update")) return r; // streamed to a file after the PIN check
        if (len > MAX_BODY) throw new Exception("body too large");
        if (len > 0) {
            byte[] body = new byte[len];
            int off = 0;
            while (off < len) {
                int n = in.read(body, off, len - off);
                if (n < 0) break;
                off += n;
            }
            r.body = body;
        }
        return r;
    }

    private static void send(OutputStream out, int code, String type, byte[] body) throws Exception {
        String status = code == 200 ? "OK" : code == 401 ? "Unauthorized" : code == 404 ? "Not Found"
                : code == 429 ? "Too Many Requests" : code == 400 ? "Bad Request" : "Error";
        String head = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private static void json(OutputStream out, int code, JSONObject o) throws Exception {
        send(out, code, "application/json; charset=utf-8", o.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static JSONObject err(String msg) throws Exception {
        return new JSONObject().put("ok", false).put("error", msg);
    }

    // ------------------------------------------------------------------ routes

    private void route(Request r, OutputStream out) throws Exception {
        if (r.path.equals("/") || r.path.equals("/index.html")) {
            send(out, 200, "text/html; charset=utf-8", readRaw(R.raw.webui));
            return;
        }
        if (!r.path.startsWith("/api/")) {
            send(out, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        long wait = checkPin(r);
        if (wait != 0) {
            if (wait > 0) {
                long min = Math.max(1, (wait + 59_999) / 60_000);
                json(out, 429, err("Too many wrong PINs from this device. Try again in " + min
                        + (min == 1 ? " minute." : " minutes.")));
            } else {
                json(out, 401, err("Wrong PIN"));
            }
            return;
        }
        Context c = this;
        switch (r.path) {
            case "/api/state":
                json(out, 200, new JSONObject().put("ok", true)
                        .put("settings", Prefs.toJson(c)).put("status", status())
                        .put("sourceSettings", Prefs.sourceJson(c)));
                return;
            case "/api/settings": {
                if (!"POST".equals(r.method)) break;
                String e = Prefs.apply(c, new JSONObject(new String(r.body, StandardCharsets.UTF_8)));
                if (e != null) {
                    json(out, 400, err(e));
                } else {
                    json(out, 200, new JSONObject().put("ok", true).put("settings", Prefs.toJson(c)));
                }
                return;
            }
            case "/api/action": {
                if (!"POST".equals(r.method)) break;
                String e = action(r.query.getOrDefault("do", ""));
                json(out, e == null ? 200 : 400, e == null ? new JSONObject().put("ok", true) : err(e));
                return;
            }
            case "/api/update": {
                if (!"POST".equals(r.method)) break;
                String e = receiveUpdate(r);
                json(out, e == null ? 200 : 400, e == null
                        ? new JSONObject().put("ok", true).put("message", "Installing – the frame will be back in a few seconds")
                        : err(e));
                return;
            }
            case "/api/source": {
                if ("POST".equals(r.method)) {
                    JSONObject res = Prefs.testAndSaveSource(c, new JSONObject(new String(r.body, StandardCharsets.UTF_8)));
                    json(out, res.optBoolean("ok") ? 200 : 400, res.put("sourceSettings", Prefs.sourceJson(c)));
                } else {
                    json(out, 200, new JSONObject().put("ok", true).put("sourceSettings", Prefs.sourceJson(c)));
                }
                return;
            }
            case "/api/folders":
                json(out, 200, folders(r.query.get("path")).put("ok", true));
                return;
            case "/api/albums":
                send(out, 200, "application/json; charset=utf-8", albums());
                return;
            case "/api/current.jpg": {
                File f = SlideshowView.currentFile();
                if (f == null || !f.exists()) {
                    send(out, 404, "text/plain", "No photo".getBytes(StandardCharsets.UTF_8));
                } else {
                    send(out, 200, "image/jpeg", readFile(f));
                }
                return;
            }
            default:
                break;
        }
        send(out, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
    }

    /** 0 = PIN ok, -1 = wrong PIN, >0 = this device is locked out for that many ms. */
    private synchronized long checkPin(Request r) {
        long now = System.currentTimeMillis();
        Long until = lockedUntil.get(r.ip);
        if (until != null && now < until) return until - now;
        String given = r.headers.get("x-pin");
        if (given == null || given.isEmpty()) given = r.query.get("pin");
        if (Prefs.pin(this).equals(given)) {
            badPins.remove(r.ip);
            lockedUntil.remove(r.ip);
            return 0;
        }
        if (given == null || given.isEmpty()) return -1; // no PIN entered yet: don't count it
        int n = badPins.getOrDefault(r.ip, 0) + 1;
        android.util.Log.w("FrameHome", "wrong PIN from " + r.ip + " on " + r.path + " (" + n + ")");
        if (n >= MAX_BAD_PINS) {
            lockedUntil.put(r.ip, now + LOCK_MS);
            badPins.remove(r.ip);
            android.util.Log.w("FrameHome", "locked out " + r.ip + " for 5 minutes");
            return LOCK_MS;
        }
        badPins.put(r.ip, n);
        return -1;
    }

    private JSONObject status() throws Exception {
        PowerManager pm = getSystemService(PowerManager.class);
        boolean screenOn = pm != null && pm.isInteractive();
        String showing = !screenOn ? "asleep"
                : (SlideshowView.anyActive() ? (FrameDream.isDreaming() ? "screensaver" : "slideshow")
                : "home / other app");
        PhotoSource src = PhotoSource.get(this);
        JSONObject o = new JSONObject()
                .put("screenOn", screenOn)
                .put("showing", showing)
                .put("photoCount", src.count())
                .put("lastError", src.lastError() == null ? JSONObject.NULL : src.lastError())
                .put("hasAdmin", Schedule.hasAdmin(this))
                .put("canSetBrightness", android.provider.Settings.System.canWrite(this))
                .put("address", String.valueOf(address()))
                .put("adbWifi", adbWifiOn())
                .put("deviceOwner", isDeviceOwner())
                .put("canInstall", isDeviceOwner() || getPackageManager().canRequestPackageInstalls())
                .put("setup", Setup.toJson(this))
                .put("version", versionName());
        if (Schedule.enabled(this)) {
            o.put("nextSleep", Schedule.formatWhen(this, Schedule.nextOccurrence(Schedule.sleepMinute(this))));
            o.put("nextWake", Schedule.formatWhen(this, Schedule.nextOccurrence(Schedule.wakeMinute(this))));
        }
        return o;
    }

    private boolean isDeviceOwner() {
        android.app.admin.DevicePolicyManager dpm =
                getSystemService(android.app.admin.DevicePolicyManager.class);
        return dpm != null && dpm.isDeviceOwnerApp(getPackageName());
    }

    private String versionName() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName + " (" + pi.getLongVersionCode() + ")";
        } catch (Exception e) {
            return "?";
        }
    }

    /**
     * Receives an APK upload and installs it silently (device owner). Only accepts an update of
     * Frame Home signed with the same key, so the PIN can't be used to install other software.
     */
    private String receiveUpdate(Request r) throws Exception {
        if (!isDeviceOwner() && !getPackageManager().canRequestPackageInstalls()) {
            return "On the frame, open Setup and allow \"Updates from the phone remote\" first";
        }
        if (r.length <= 0 || r.length > 50 * 1024 * 1024) return "Upload an APK file (max 50 MB)";
        File apk = new File(getCacheDir(), "update.apk");
        try (OutputStream o = new java.io.FileOutputStream(apk)) {
            byte[] buf = new byte[64 * 1024];
            int left = r.length;
            while (left > 0) {
                int n = r.in.read(buf, 0, Math.min(buf.length, left));
                if (n < 0) return "Upload was cut off";
                o.write(buf, 0, n);
                left -= n;
            }
        }
        android.content.pm.PackageManager pm = getPackageManager();
        @SuppressWarnings("deprecation")
        android.content.pm.PackageInfo upd = pm.getPackageArchiveInfo(apk.getPath(),
                android.content.pm.PackageManager.GET_SIGNATURES);
        if (upd == null) return "That file isn't a valid APK";
        if (!getPackageName().equals(upd.packageName)) return "Only Frame Home updates are accepted";
        @SuppressWarnings("deprecation")
        android.content.pm.PackageInfo cur = pm.getPackageInfo(getPackageName(),
                android.content.pm.PackageManager.GET_SIGNATURES);
        if (upd.signatures == null || cur.signatures == null
                || !java.util.Arrays.equals(upd.signatures, cur.signatures)) {
            return "The APK isn't signed with Frame Home's key";
        }
        if (upd.getLongVersionCode() < cur.getLongVersionCode()) return "That's an older version";
        main.postDelayed(() -> install(apk), 800); // reply first: installing restarts this app
        return null;
    }

    private void install(File apk) {
        try {
            android.content.pm.PackageInstaller pi = getPackageManager().getPackageInstaller();
            android.content.pm.PackageInstaller.SessionParams params =
                    new android.content.pm.PackageInstaller.SessionParams(
                            android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(getPackageName());
            int id = pi.createSession(params);
            try (android.content.pm.PackageInstaller.Session session = pi.openSession(id)) {
                try (OutputStream o = session.openWrite("base.apk", 0, apk.length());
                     InputStream in = new FileInputStream(apk)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                    session.fsync(o);
                }
                android.app.PendingIntent done = android.app.PendingIntent.getBroadcast(this, id,
                        new Intent(this, InstallResultReceiver.class),
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT);
                session.commit(done.getIntentSender());
            }
        } catch (Exception e) {
            android.util.Log.e("FrameHome", "update failed", e);
        }
    }

    /** Wireless ADB can't be turned on by an app on Android 10, but we can see whether it's on. */
    private static boolean adbWifiOn() {
        try (Socket s = new Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", 5555), 300);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Runs a remote command on the main thread. Returns an error message or null. */
    private String action(String what) {
        Context c = this;
        Runnable r;
        switch (what) {
            case "start":
                r = () -> {
                    PowerManager pm = getSystemService(PowerManager.class);
                    if (pm != null && !pm.isInteractive()) Schedule.wakeNow(c); // wake also starts it
                    if (!FrameDream.isDreaming()) Schedule.startSlideshow(c);
                };
                break;
            case "home":
                r = () -> {
                    FrameDream.stopDreaming();
                    PowerManager pm = getSystemService(PowerManager.class);
                    if (pm != null && !pm.isInteractive()) {
                        Schedule.prefs(c).edit().putBoolean("homeOnNextWake", true).apply();
                        Schedule.wakeNow(c);
                    }
                    startActivity(new Intent(c, HomeActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
                };
                break;
            case "next":
            case "prev":
                if (!SlideshowView.anyActive()) return "The slideshow isn't running";
                r = () -> SlideshowView.broadcast(what);
                break;
            case "reload":
                r = () -> {
                    PhotoSource.get(c).invalidate();
                    SlideshowView.broadcast("reload");
                };
                break;
            case "sleep":
                if (!Schedule.hasAdmin(c)) return "Screen-off permission is missing";
                r = () -> Schedule.sleepNow(c);
                break;
            case "wake":
                r = () -> Schedule.wakeNow(c);
                break;
            case "restart":
                if (!isDeviceOwner()) return "Restart needs Frame Home to be device owner";
                r = () -> getSystemService(android.app.admin.DevicePolicyManager.class)
                        .reboot(Schedule.adminComponent(c));
                main.postDelayed(r, 1500); // let the HTTP reply go out first
                return null;
            default:
                return "Unknown action";
        }
        main.post(r);
        return null;
    }

    /** Immich albums for the album picker: [{id, name, count}]. */
    private byte[] albums() {
        try {
            return Providers.immichFromSettings(this).albums().toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "[]".getBytes(StandardCharsets.UTF_8);
        }
    }

    /** Sub-folders of a path on the frame, for the folder picker. */
    private JSONObject folders(String path) throws Exception {
        java.util.List<String> roots = new java.util.ArrayList<>();
        roots.add("/sdcard");
        File[] mounts = new File("/storage").listFiles();
        if (mounts != null) {
            for (File m : mounts) {
                if (!m.getName().equals("emulated") && !m.getName().equals("self") && m.canRead()) {
                    roots.add(m.getPath()); // USB sticks and SD cards
                }
            }
        }
        org.json.JSONArray rootArr = new org.json.JSONArray(roots);
        if (path == null || path.isEmpty()) {
            return new JSONObject().put("path", "").put("roots", rootArr).put("folders", rootArr);
        }
        File dir = new File(path);
        org.json.JSONArray subs = new org.json.JSONArray();
        int images = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            java.util.Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File f : files) {
                if (f.isDirectory() && !f.getName().startsWith(".")) subs.put(f.getPath());
                else if (Providers.isImage(f.getName())) images++;
            }
        }
        String parent = dir.getParent();
        return new JSONObject().put("path", dir.getPath()).put("parent", parent == null ? "" : parent)
                .put("roots", rootArr).put("folders", subs).put("images", images)
                .put("readable", dir.canRead());
    }

    private byte[] readRaw(int id) throws Exception {
        try (InputStream in = getResources().openRawResource(id)) {
            return readAll(in);
        }
    }

    private static byte[] readFile(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[32 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }
}
