package se.tvhome.launcher;

import android.content.Intent;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URISyntaxException;

/** Everything the interface can ask the TV to do. Exposed to the page as {@code Native}. */
final class Bridge {

    private final MainActivity activity;
    private final MediaWatcher media;
    private String pendingView;

    Bridge(MainActivity activity, MediaWatcher media) {
        this.activity = activity;
        this.media = media;
    }

    synchronized void setPendingView(String view) {
        pendingView = view;
    }

    /** The screen the app was asked to open with (for example "player"), reported once. */
    @JavascriptInterface
    public synchronized String takePendingView() {
        String view = pendingView == null ? "" : pendingView;
        pendingView = null;
        return view;
    }

    @JavascriptInterface
    public String getApps() {
        return Apps.list(activity).toString();
    }

    @JavascriptInterface
    public boolean launch(String pkg) {
        Intent intent = Apps.launchIntent(activity.getPackageManager(), pkg);
        if (intent == null) {
            return false;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Foreground.isSpotify(pkg)) {
            Foreground.spotifyOpenedFromTile = true;
        }
        return start(intent);
    }

    // Which source the screen shows: "tv" (an app on the TV) or "sonos". Buttons go to it.
    private volatile String source = "tv";
    private volatile String trackOnScreen = "";

    /**
     * What to show as now playing: music on the TV itself, or on a Sonos speaker. Whatever is
     * actually playing wins; with both paused, the one that played last.
     */
    @JavascriptInterface
    public String getNowPlaying() {
        String tvJson = media.snapshot();
        JSONObject sonos = SonosWatcher.get(activity).snapshot();
        if (sonos == null) {
            source = "tv";
            trackOnScreen = keyOf(tvJson);
            return tvJson;
        }
        try {
            JSONObject tv = new JSONObject(tvJson);
            boolean tvActive = tv.optBoolean("active");
            boolean tvPlaying = tvActive && tv.optBoolean("playing");
            boolean sonosPlaying = sonos.optBoolean("playing");
            boolean useSonos = !tvActive
                    || (sonosPlaying && !tvPlaying)
                    || (!sonosPlaying && !tvPlaying
                    && SonosWatcher.get(activity).lastPlayingAt() > media.lastPlayingAt());
            if (!useSonos) {
                source = "tv";
                trackOnScreen = tv.optString("key");
                return tvJson;
            }
            // The sleep timer lives on the TV side whichever source is shown.
            sonos.put("sleep", tv.opt("sleep"));
            sonos.put("sleepFired", tv.optBoolean("sleepFired"));
            source = "sonos";
            trackOnScreen = sonos.optString("key");
            if (sonosPlaying) {
                media.noteTrack(trackOnScreen);
            }
            return sonos.toString();
        } catch (JSONException e) {
            return tvJson;
        }
    }

    private static String keyOf(String json) {
        try {
            return new JSONObject(json).optString("key");
        } catch (JSONException e) {
            return "";
        }
    }

    @JavascriptInterface
    public void media(String command, String argument) {
        long value = 0;
        try {
            value = argument == null || argument.isEmpty() ? 0 : (long) Double.parseDouble(argument);
        } catch (NumberFormatException ignored) {
            value = 0;
        }
        if ("sonos".equals(source)) {
            SonosWatcher.get(activity).command(command, value);
        } else {
            media.command(command, value);
        }
    }

    /** "off", "track" (pause when the current track ends) or a number of minutes. */
    @JavascriptInterface
    public void setSleep(String mode) {
        media.setSleep(mode == null ? "off" : mode, trackOnScreen);
    }

    /* ---------- Updates ---------- */

    @JavascriptInterface
    public String getUpdate() {
        return Updater.get(activity).status();
    }

    @JavascriptInterface
    public void checkUpdate(boolean force) {
        Updater.get(activity).check(force);
    }

    @JavascriptInterface
    public void installUpdate() {
        Updater.get(activity).install();
    }

    /*
     * What other apps share for Home is read in the background and kept here. Reading it is a
     * database query per app, which used to hold up the interface (and the remote) while it ran.
     */
    private static final java.util.concurrent.ExecutorService CONTENT =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile String watchNextJson = "[]";
    private volatile String previewsJson = "[]";
    private volatile long contentAt;
    private final java.util.concurrent.atomic.AtomicBoolean reading = new java.util.concurrent.atomic.AtomicBoolean();

    @JavascriptInterface
    public String getWatchNext() {
        return watchNextJson;
    }

    @JavascriptInterface
    public String getPreviews() {
        return previewsJson;
    }

    /** Reads again unless that was done in the last half minute; tells the page if anything changed. */
    @JavascriptInterface
    public void refreshContent() {
        refreshContent(false);
    }

    void refreshContent(boolean force) {
        if (!force && System.currentTimeMillis() - contentAt < 30 * 1000) {
            return;
        }
        if (!reading.compareAndSet(false, true)) {
            return;
        }
        CONTENT.execute(() -> {
            try {
                String next = TvData.allowed(activity) ? TvData.watchNext(activity).toString() : "[]";
                String previews = TvData.allowed(activity) ? TvData.previews(activity).toString() : "[]";
                boolean changed = !next.equals(watchNextJson) || !previews.equals(previewsJson);
                watchNextJson = next;
                previewsJson = previews;
                contentAt = System.currentTimeMillis();
                if (changed) {
                    activity.emit("content", "");
                }
            } catch (RuntimeException ignored) {
                // Keep what was read before.
            } finally {
                reading.set(false);
            }
        });
    }

    /**
     * Opens a Spotify link (spotify:playlist:..., or an open.spotify.com address) in the Spotify
     * app, falling back to just opening the app.
     */
    @JavascriptInterface
    public boolean openSpotify(String link) {
        String pkg = null;
        for (String candidate : new String[]{"com.spotify.tv.android", "com.spotify.music"}) {
            if (Apps.launchIntent(activity.getPackageManager(), candidate) != null) {
                pkg = candidate;
                break;
            }
        }
        if (pkg == null) {
            return false;
        }
        Foreground.spotifyOpenedFromTile = true;
        if (link != null && !link.isEmpty()) {
            String uri = link;
            String web = link;
            // spotify:playlist:ID and https://open.spotify.com/playlist/ID name the same thing.
            if (link.startsWith("https://open.spotify.com/")) {
                String[] parts = Uri.parse(link).getPathSegments().toArray(new String[0]);
                if (parts.length >= 2) {
                    uri = "spotify:" + parts[parts.length - 2] + ":" + parts[parts.length - 1];
                }
            } else if (link.startsWith("spotify:")) {
                String[] parts = link.split(":");
                if (parts.length >= 3) {
                    web = "https://open.spotify.com/" + parts[1] + "/" + parts[2];
                }
            }
            for (String address : new String[]{uri, web}) {
                Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(address)).setPackage(pkg);
                view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (view.resolveActivity(activity.getPackageManager()) != null && start(view)) {
                    return true;
                }
            }
        }
        return launch(pkg);
    }

    /** Opens an entry another app published, using the link that app supplied for it. */
    @JavascriptInterface
    public boolean openIntent(String uri, String pkg) {
        if (uri == null || uri.isEmpty()) {
            return false;
        }
        try {
            Intent intent = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (intent.getComponent() == null && intent.getPackage() == null
                    && pkg != null && !pkg.isEmpty()) {
                Intent scoped = new Intent(intent).setPackage(pkg);
                if (scoped.resolveActivity(activity.getPackageManager()) != null) {
                    intent = scoped;
                }
            }
            if (start(intent)) {
                return true;
            }
        } catch (URISyntaxException | RuntimeException ignored) {
            // Fall through to opening the app itself.
        }
        return pkg != null && !pkg.isEmpty() && launch(pkg);
    }

    @JavascriptInterface
    public String getInputs() {
        return TvData.inputs(activity).toString();
    }

    @JavascriptInterface
    public boolean openInput(String id) {
        Intent intent = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return start(intent);
    }

    @JavascriptInterface
    public boolean openSettings(String which) {
        Intent intent;
        if ("accessibility".equals(which)) {
            intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        } else if ("notifications".equals(which)) {
            intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        } else if ("install".equals(which) && Build.VERSION.SDK_INT >= 26) {
            intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.fromParts("package", activity.getPackageName(), null));
        } else if ("app".equals(which)) {
            intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", activity.getPackageName(), null));
        } else {
            intent = new Intent(Settings.ACTION_SETTINGS);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return start(intent);
    }

    @JavascriptInterface
    public String getStatus() {
        JSONObject o = new JSONObject();
        try {
            o.put("nowPlayingAccess", media.hasAccess());
            boolean homeOn = HomeButton.isEnabled(activity);
            o.put("homeButton", homeOn);
            o.put("homeButtonRunning", homeOn && HomeButton.isRunning(activity));
            o.put("canFixHomeButton", HomeButton.canFix(activity));
            o.put("tvListings", TvData.allowed(activity));
            o.put("version", activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).versionName);
            o.put("pkg", activity.getPackageName());
            o.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            o.put("android", Build.VERSION.RELEASE);
        } catch (JSONException | android.content.pm.PackageManager.NameNotFoundException e) {
            return "{}";
        }
        return o.toString();
    }

    /** Switches the Home button service on, or off and on again. False without the permission. */
    @JavascriptInterface
    public boolean fixHomeButton() {
        return HomeButton.fix(activity);
    }

    @JavascriptInterface
    public void requestTvListings() {
        activity.runOnUiThread(() -> activity.requestPermissions(
                new String[]{TvData.PERMISSION, TvData.LEGACY_PERMISSION}, MainActivity.REQUEST_TV_LISTINGS));
    }

    @JavascriptInterface
    public void setPref(String key, String value) {
        App.prefs(activity).edit().putString(key, value).apply();
    }

    /** Keeps the TV's own screensaver away while music is on screen. */
    @JavascriptInterface
    public void keepAwake(final boolean on) {
        activity.runOnUiThread(() -> {
            if (on) {
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });
    }

    private boolean start(Intent intent) {
        try {
            activity.startActivity(intent);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
