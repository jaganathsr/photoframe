package com.jay.framehome;

import android.content.Context;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;

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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Where photos come from: Immich, a folder on the frame, or an SMB (Windows/NAS) share. */
final class Providers {
    private Providers() {}

    static final class Photo {
        final String id;   // stable within the source (asset id or relative path)
        final long taken;  // ms since epoch, 0 if unknown
        Photo(String id, long taken) { this.id = id; this.taken = taken; }
    }

    interface Provider {
        /** Identifies the source and its settings; a change means "different photo set". */
        String key();
        /** True if downloaded files are already screen-sized and upright (no processing needed). */
        boolean readyToShow();
        List<Photo> list() throws Exception;
        /** Writes the original (or preview) image for this photo into {@code dest}. */
        void download(Photo p, File dest) throws Exception;
        default void close() {}
    }

    static final String[] IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif"};

    static boolean isImage(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.startsWith(".")) return false; // hidden files, macOS "._" files
        for (String e : IMAGE_EXTENSIONS) if (n.endsWith(e)) return true;
        return false;
    }

    /** Builds the provider for the current settings. */
    static Provider fromSettings(Context c) {
        switch (Prefs.source(c)) {
            case "folder":
                return new FolderProvider(Prefs.str(c, "folderPath", Prefs.DEFAULT_FOLDER),
                        Prefs.bool(c, "recursive", true));
            case "smb":
                return new SmbProvider(Prefs.str(c, "smbHost", ""), Prefs.str(c, "smbShare", ""),
                        Prefs.str(c, "smbPath", ""), Prefs.str(c, "smbUser", ""),
                        Prefs.str(c, "smbPass", ""), Prefs.str(c, "smbDomain", ""),
                        Prefs.bool(c, "recursive", true));
            default:
                return immichFromSettings(c);
        }
    }

    /** Immich settings (from the phone remote, falling back to the installer's immich.json). */
    static ImmichProvider immichFromSettings(Context c) {
        PhotoSource.Config cfg = PhotoSource.loadConfig(c);
        String url = Prefs.str(c, "immichUrl", cfg.url == null ? "" : cfg.url);
        String key = Prefs.str(c, "immichKey", cfg.apiKey == null ? "" : cfg.apiKey);
        String album = Prefs.albumId(c);
        return new ImmichProvider(url, key, album == null ? "" : album, "oldest".equals(Prefs.order(c)));
    }

    // ------------------------------------------------------------------ Immich

    static final class ImmichProvider implements Provider {
        final String url, apiKey, albumId;
        final boolean ascending;

        ImmichProvider(String url, String apiKey, String albumId, boolean ascending) {
            this.url = url.replaceAll("/+$", "");
            this.apiKey = apiKey;
            this.albumId = albumId;
            this.ascending = ascending;
        }

        @Override public String key() { return "immich:" + url + "/" + albumId; }
        @Override public boolean readyToShow() { return true; }

        @Override
        public List<Photo> list() throws Exception {
            if (url.isEmpty() || apiKey.isEmpty()) throw new Exception("Immich server or API key not set");
            if (albumId.isEmpty()) throw new Exception("Choose an Immich album");
            List<Photo> out = new ArrayList<>();
            int page = 1;
            while (page > 0) {
                JSONObject body = new JSONObject()
                        .put("albumIds", new JSONArray().put(albumId))
                        .put("type", "IMAGE").put("size", 1000).put("page", page)
                        .put("order", ascending ? "asc" : "desc");
                JSONObject res = new JSONObject(new String(
                        request("POST", "/api/search/metadata", body.toString()),
                        StandardCharsets.UTF_8)).getJSONObject("assets");
                JSONArray items = res.getJSONArray("items");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject a = items.getJSONObject(i);
                    out.add(new Photo(a.getString("id"), parseIso(a.optString("fileCreatedAt", ""))));
                }
                String nxt = res.optString("nextPage", "");
                page = (nxt.isEmpty() || "null".equals(nxt)) ? 0 : Integer.parseInt(nxt);
            }
            return out;
        }

        @Override
        public void download(Photo p, File dest) throws Exception {
            byte[] data = request("GET", "/api/assets/" + p.id + "/thumbnail?size=preview", null);
            try (OutputStream o = new FileOutputStream(dest)) {
                o.write(data);
            }
        }

        /** Albums for the picker: [{id, name, count}]. */
        JSONArray albums() throws Exception {
            JSONArray src = new JSONArray(new String(request("GET", "/api/albums", null), StandardCharsets.UTF_8));
            JSONArray out = new JSONArray();
            for (int i = 0; i < src.length(); i++) {
                JSONObject a = src.getJSONObject(i);
                out.put(new JSONObject().put("id", a.getString("id"))
                        .put("name", a.optString("albumName", "?"))
                        .put("count", a.optInt("assetCount", 0)));
            }
            return out;
        }

        byte[] request(String method, String path, String jsonBody) throws Exception {
            HttpURLConnection c = (HttpURLConnection) new URL(url + path).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(30_000);
            c.setRequestMethod(method);
            c.setRequestProperty("x-api-key", apiKey);
            c.setRequestProperty("Accept", "application/json");
            if (jsonBody != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            if (code == 401 || code == 403) {
                c.disconnect();
                throw new Exception("Immich rejected the API key (HTTP " + code + ")");
            }
            if (code / 100 != 2) {
                c.disconnect();
                throw new Exception("Immich returned HTTP " + code);
            }
            try (InputStream in = c.getInputStream()) {
                return readAll(in);
            } finally {
                c.disconnect();
            }
        }
    }

    // ------------------------------------------------------------------ Folder on the frame

    static final class FolderProvider implements Provider {
        final File root;
        final boolean recursive;

        FolderProvider(String path, boolean recursive) {
            this.root = new File(path);
            this.recursive = recursive;
        }

        @Override public String key() { return "folder:" + root.getAbsolutePath() + (recursive ? "/**" : ""); }
        @Override public boolean readyToShow() { return false; }

        @Override
        public List<Photo> list() throws Exception {
            if (!root.isDirectory()) throw new Exception("Folder not found: " + root.getPath());
            if (!root.canRead()) throw new Exception("No permission to read " + root.getPath());
            List<Photo> out = new ArrayList<>();
            walk(root, "", out, 0);
            return out;
        }

        private void walk(File dir, String rel, List<Photo> out, int depth) {
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                String name = f.getName();
                if (f.isDirectory()) {
                    if (recursive && depth < 12 && !name.startsWith(".")) {
                        walk(f, rel + name + "/", out, depth + 1);
                    }
                } else if (isImage(name)) {
                    // Date taken is refined from EXIF when the photo is first prepared.
                    out.add(new Photo(rel + name, f.lastModified()));
                }
            }
        }

        @Override
        public void download(Photo p, File dest) throws Exception {
            try (InputStream in = new FileInputStream(new File(root, p.id));
                 OutputStream o = new FileOutputStream(dest)) {
                copy(in, o);
            }
        }
    }

    // ------------------------------------------------------------------ SMB (Windows / NAS share)

    static final class SmbProvider implements Provider {
        final String host, shareName, basePath, user, pass, domain;
        final boolean recursive;
        private SMBClient client;
        private Connection conn;
        private DiskShare share;

        SmbProvider(String host, String shareName, String basePath, String user, String pass,
                    String domain, boolean recursive) {
            this.host = host.trim().replaceFirst("^(smb:)?/+", "").replaceAll("/+$", "");
            this.shareName = shareName.trim().replaceAll("^[/\\\\]+|[/\\\\]+$", "");
            this.basePath = basePath.trim().replace('/', '\\').replaceAll("^\\\\+|\\\\+$", "");
            this.user = user;
            this.pass = pass;
            this.domain = domain;
            this.recursive = recursive;
        }

        @Override public String key() {
            return "smb://" + user + "@" + host + "/" + shareName + "/" + basePath + (recursive ? "/**" : "");
        }
        @Override public boolean readyToShow() { return false; }

        private synchronized DiskShare share() throws Exception {
            if (share != null && share.isConnected()) return share;
            close();
            if (host.isEmpty() || shareName.isEmpty()) throw new Exception("Set the server and share name");
            client = new SMBClient(SmbConfig.builder()
                    .withTimeout(30, TimeUnit.SECONDS)
                    .withSoTimeout(60, TimeUnit.SECONDS)
                    .build());
            conn = client.connect(host);
            AuthenticationContext auth = user.isEmpty()
                    ? AuthenticationContext.guest()
                    : new AuthenticationContext(user, pass.toCharArray(), domain.isEmpty() ? null : domain);
            Session session = conn.authenticate(auth);
            share = (DiskShare) session.connectShare(shareName);
            return share;
        }

        @Override
        public List<Photo> list() throws Exception {
            try {
                return listRaw();
            } catch (Exception e) {
                throw new Exception(friendly(e), e);
            }
        }

        /** Plain-language versions of the common SMB failures. */
        private String friendly(Exception e) {
            String m = String.valueOf(e.getMessage());
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            String all = m + " " + root;
            if (all.contains("STATUS_LOGON_FAILURE")) {
                return user.isEmpty() ? "The server doesn't allow guest access; enter a username and password"
                        : "Wrong username or password";
            }
            if (all.contains("STATUS_BAD_NETWORK_NAME")) return "No share called \"" + shareName + "\" on " + host;
            if (all.contains("STATUS_ACCESS_DENIED")) return "Access denied to that share or folder";
            if (all.contains("STATUS_ACCOUNT_DISABLED") || all.contains("STATUS_ACCOUNT_LOCKED_OUT")) {
                return "That account is disabled or locked";
            }
            if (all.contains("UnknownHost") || all.contains("Unable to resolve")) return "Can't find a server called " + host;
            if (all.contains("ConnectException") || all.contains("EHOSTUNREACH") || all.contains("ECONNREFUSED")
                    || all.contains("failed to connect") || all.contains("timed out")) {
                return "Can't reach " + host + " on the network (is it on, and is file sharing enabled?)";
            }
            return m;
        }

        private List<Photo> listRaw() throws Exception {
            DiskShare s = share();
            if (!basePath.isEmpty() && !s.folderExists(basePath)) {
                throw new Exception("Folder not found on the share: " + basePath);
            }
            List<Photo> out = new ArrayList<>();
            walk(s, basePath, "", out, 0);
            return out;
        }

        private void walk(DiskShare s, String dir, String rel, List<Photo> out, int depth) {
            for (FileIdBothDirectoryInformation f : s.list(dir)) {
                String name = f.getFileName();
                if (name.equals(".") || name.equals("..")) continue;
                boolean isDir = (f.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
                String child = dir.isEmpty() ? name : dir + "\\" + name;
                if (isDir) {
                    if (recursive && depth < 12 && !name.startsWith(".") && !name.startsWith("@")
                            && !name.equals("#recycle")) {
                        walk(s, child, rel + name + "/", out, depth + 1);
                    }
                } else if (isImage(name)) {
                    out.add(new Photo(rel + name, f.getLastWriteTime().toEpochMillis()));
                }
            }
        }

        @Override
        public void download(Photo p, File dest) throws Exception {
            String path = (basePath.isEmpty() ? "" : basePath + "\\") + p.id.replace('/', '\\');
            DiskShare s = share();
            try (com.hierynomus.smbj.share.File f = s.openFile(path, EnumSet.of(AccessMask.GENERIC_READ),
                    null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null);
                 InputStream in = f.getInputStream();
                 OutputStream o = new FileOutputStream(dest)) {
                copy(in, o);
            }
        }

        @Override
        public synchronized void close() {
            try { if (share != null) share.close(); } catch (Exception ignored) { }
            try { if (conn != null) conn.close(); } catch (Exception ignored) { }
            try { if (client != null) client.close(); } catch (Exception ignored) { }
            share = null;
            conn = null;
            client = null;
        }
    }

    // ------------------------------------------------------------------ helpers

    static long parseIso(String iso) {
        try {
            return java.time.Instant.parse(iso).toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[64 * 1024];
        int n;
        long total = 0;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            total += n;
            if (total > 80L * 1024 * 1024) throw new Exception("File too large");
        }
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        copy(in, bos);
        return bos.toByteArray();
    }
}
