package se.tvhome.launcher;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Keeps the app up to date from the repository's Releases page. Each build there is tagged
 * "build-N", and N is also that build's version code, so a higher N is a newer build.
 * New builds are downloaded in the background; installing needs one press and Android's
 * own confirmation on the TV.
 */
final class Updater {

    private static final String RELEASES = "https://api.github.com/repos/kayserviggo-source/tv-home/releases/latest";
    private static final long CHECK_EVERY_MS = 3 * 60 * 60 * 1000L;

    private static Updater instance;

    static synchronized Updater get(Context context) {
        if (instance == null) {
            instance = new Updater(context.getApplicationContext());
        }
        return instance;
    }

    private final Context app;

    // idle | checking | current | downloading | ready | installing | error
    private String state = "idle";
    private int latest;
    private String notes = "";
    private String apkUrl = "";
    private long apkSize;
    private int progress;
    private String error = "";
    private long checkedAt;

    private Updater(Context app) {
        this.app = app;
    }

    private File apkFile() {
        return new File(app.getCacheDir(), "update.apk");
    }

    int installedBuild() {
        try {
            PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) info.getLongVersionCode() : info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    boolean mayInstall() {
        return Build.VERSION.SDK_INT < 26 || app.getPackageManager().canRequestPackageInstalls();
    }

    synchronized String status() {
        JSONObject o = new JSONObject();
        try {
            o.put("state", state);
            o.put("installed", installedBuild());
            o.put("latest", latest);
            o.put("notes", notes);
            o.put("progress", progress);
            o.put("error", error);
            o.put("mayInstall", mayInstall());
        } catch (JSONException e) {
            return "{}";
        }
        return o.toString();
    }

    /** Looks for a newer build, at most every few hours unless {@code force}. */
    void check(boolean force) {
        synchronized (this) {
            if ("checking".equals(state) || "downloading".equals(state) || "installing".equals(state)) {
                return;
            }
            if ("ready".equals(state) && apkFile().exists()) {
                return;
            }
            if (!force && checkedAt > 0 && SystemClock.elapsedRealtime() - checkedAt < CHECK_EVERY_MS) {
                return;
            }
            state = "checking";
            error = "";
            checkedAt = SystemClock.elapsedRealtime();
        }
        new Thread(this::checkNow, "updater").start();
    }

    private void checkNow() {
        try {
            JSONObject release = new JSONObject(new String(get(RELEASES), StandardCharsets.UTF_8));
            int build = buildNumber(release.optString("tag_name"));
            String url = "";
            long size = 0;
            JSONArray assets = release.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (a.optString("name").endsWith(".apk")) {
                    url = a.optString("browser_download_url");
                    size = a.optLong("size");
                    break;
                }
            }
            synchronized (this) {
                latest = build;
                notes = firstLine(release.optString("body"));
                apkUrl = url;
                apkSize = size;
                if (build <= installedBuild() || url.isEmpty()) {
                    state = "current";
                    return;
                }
                state = "downloading";
                progress = 0;
            }
            download();
        } catch (IOException | JSONException | RuntimeException e) {
            fail("Could not reach GitHub");
        }
    }

    private void download() {
        File target = apkFile();
        String url;
        long size;
        synchronized (this) {
            url = apkUrl;
            size = apkSize;
        }
        HttpURLConnection c = null;
        try {
            c = open(url);
            if (c.getResponseCode() != 200) {
                throw new IOException("HTTP " + c.getResponseCode());
            }
            long total = size > 0 ? size : c.getContentLength();
            long done = 0;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[32 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    done += n;
                    if (total > 0) {
                        synchronized (this) {
                            progress = (int) Math.min(100, done * 100 / total);
                        }
                    }
                }
            }
            if (size > 0 && target.length() != size) {
                throw new IOException("incomplete download");
            }
            synchronized (this) {
                state = "ready";
                progress = 100;
            }
        } catch (IOException | RuntimeException e) {
            target.delete();
            fail("The download did not finish");
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** Hands the downloaded build to Android, which asks for confirmation on the TV. */
    void install() {
        synchronized (this) {
            if (!"ready".equals(state)) {
                return;
            }
            state = "installing";
        }
        new Thread(() -> {
            File file = apkFile();
            try {
                PackageInstaller installer = app.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(app.getPackageName());
                if (Build.VERSION.SDK_INT >= 31) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                }
                int id = installer.createSession(params);
                try (PackageInstaller.Session session = installer.openSession(id)) {
                    try (InputStream in = new FileInputStream(file);
                         OutputStream out = session.openWrite("tvhome.apk", 0, file.length())) {
                        byte[] buffer = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buffer)) > 0) {
                            out.write(buffer, 0, n);
                        }
                        session.fsync(out);
                    }
                    Intent done = new Intent(app, UpdateReceiver.class);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT
                            | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    PendingIntent result = PendingIntent.getBroadcast(app, id, done, flags);
                    session.commit(result.getIntentSender());
                }
            } catch (IOException | RuntimeException e) {
                synchronized (Updater.this) {
                    error = mayInstall() ? "Android would not install it" : "TV Home is not allowed to install updates yet";
                    // Keep the download so Install can be tried again.
                    state = file.exists() ? "ready" : "error";
                }
            }
        }, "updater-install").start();
    }

    /** Called with Android's answer to an install. */
    void onResult(boolean success, String message) {
        synchronized (this) {
            if (success) {
                state = "current";
                apkFile().delete();
            } else {
                // Keep the download, so pressing Install again does not fetch it a second time.
                state = apkFile().exists() ? "ready" : "error";
                error = message == null || message.isEmpty() ? "The update was not installed" : message;
            }
        }
    }

    private synchronized void fail(String message) {
        state = "error";
        error = message;
    }

    private static int buildNumber(String tag) {
        try {
            return Integer.parseInt(tag.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                return t.length() > 90 ? t.substring(0, 89) + "…" : t;
            }
        }
        return "";
    }

    private static HttpURLConnection open(String address) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "tv-home-updater");
        c.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream");
        return c;
    }

    private static byte[] get(String address) throws IOException {
        HttpURLConnection c = open(address);
        try {
            if (c.getResponseCode() != 200) {
                throw new IOException("HTTP " + c.getResponseCode());
            }
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[16 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    if (out.size() > 2 * 1024 * 1024) {
                        throw new IOException("too large");
                    }
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }
}
