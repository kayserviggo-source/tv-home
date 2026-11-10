package se.tvhome.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;

/**
 * Follows whatever is playing on the TV (Spotify, YouTube, ...) through Android's media
 * sessions: track, artist, cover, position, and play/pause/skip.
 */
final class MediaWatcher implements MediaSessionManager.OnActiveSessionsChangedListener {

    interface Listener {
        void onMediaChanged();
    }

    private static MediaWatcher instance;

    static synchronized MediaWatcher get(Context context) {
        if (instance == null) {
            instance = new MediaWatcher(context.getApplicationContext());
        }
        return instance;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ComponentName listenerComponent;
    private final MediaSessionManager manager;

    private boolean started;
    private MediaController controller;
    private Listener listener;
    private boolean wasPlaying;
    private long lastPlayingAt;

    // The cover shown for the current track. Apps often send no picture, or a stand-in such as
    // their own logo, for a moment while the real cover loads, and resend everything when the
    // volume changes. So a cover only changes once the new one has settled.
    private byte[] artBytes;
    private String artUrl = "";
    private String artTrack = "";
    private boolean artFlat;
    private byte[] nextBytes;
    private String nextUrl;
    private boolean nextFlat;
    private final Runnable commitArt = this::commitArt;

    // Sleep timer. Kept here rather than in the interface so it still fires when another
    // app (Spotify's own screen, say) is in front.
    private String sleepMode = "off"; // off | timer | track
    private long sleepAt;
    private int sleepMinutes;
    private String sleepTrack = "";
    private boolean sleepFired;
    private final Runnable sleepTask = this::fireSleep;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            changed();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            synchronized (MediaWatcher.this) {
                refreshArt();
            }
            changed();
        }

        @Override
        public void onSessionDestroyed() {
            reselect();
            changed();
        }
    };

    private MediaWatcher(Context app) {
        this.app = app;
        this.listenerComponent = new ComponentName(app, NowPlayingListener.class);
        this.manager = (MediaSessionManager) app.getSystemService(Context.MEDIA_SESSION_SERVICE);
    }

    synchronized void setListener(Listener l) {
        listener = l;
    }

    boolean hasAccess() {
        String enabled = Settings.Secure.getString(app.getContentResolver(), "enabled_notification_listeners");
        return enabled != null
                && (enabled.contains(listenerComponent.flattenToString())
                || enabled.contains(listenerComponent.flattenToShortString()));
    }

    synchronized boolean start() {
        if (started) {
            return true;
        }
        if (manager == null || !hasAccess()) {
            return false;
        }
        try {
            manager.addOnActiveSessionsChangedListener(this, listenerComponent, main);
            started = true;
            select(manager.getActiveSessions(listenerComponent));
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    @Override
    public void onActiveSessionsChanged(List<MediaController> controllers) {
        synchronized (this) {
            select(controllers);
        }
        changed();
    }

    private void reselect() {
        synchronized (this) {
            if (!started) {
                return;
            }
            try {
                select(manager.getActiveSessions(listenerComponent));
            } catch (SecurityException e) {
                started = false;
                detach();
            }
        }
    }

    /** Picks the session that is playing, or failing that the first one Android lists. */
    private void select(List<MediaController> controllers) {
        MediaController best = null;
        if (controllers != null) {
            for (MediaController c : controllers) {
                if (app.getPackageName().equals(c.getPackageName())) {
                    continue;
                }
                if (best == null) {
                    best = c;
                }
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                    best = c;
                    break;
                }
            }
        }
        if (controller != null && best != null
                && controller.getSessionToken().equals(best.getSessionToken())) {
            return;
        }
        detach();
        controller = best;
        if (controller != null) {
            controller.registerCallback(callback, main);
        }
        main.removeCallbacks(commitArt);
        nextUrl = null;
        artTrack = "";
        artUrl = "";
        artBytes = null;
        artFlat = false;
        refreshArt();
    }

    private void detach() {
        if (controller != null) {
            try {
                controller.unregisterCallback(callback);
            } catch (RuntimeException ignored) {
                // The session may already be gone.
            }
            controller = null;
        }
    }

    /**
     * Looks at the picture the app currently offers and decides whether, and when, it replaces
     * the cover on screen. The app's display icon is deliberately never used: for Spotify that
     * is its logo, which is what used to flash up between tracks.
     */
    private void refreshArt() {
        MediaMetadata m = controller == null ? null : controller.getMetadata();
        String track = m == null ? "" : trackKeyOf(m);
        byte[] bytes = null;
        String url = "";
        boolean flat = false;
        if (m != null) {
            Bitmap bitmap = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (bitmap == null) {
                bitmap = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            }
            if (bitmap != null) {
                bytes = encode(bitmap);
                if (bytes != null) {
                    url = App.ORIGIN + "/art/now?k=" + Integer.toHexString(Arrays.hashCode(bytes));
                    flat = isFlat(bitmap);
                }
            }
            if (url.isEmpty()) {
                url = imageUrl(firstText(m, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI));
                bytes = null;
            }
        }

        if (url.equals(artUrl)) {
            dropPending();
            return;
        }
        boolean sameTrack = !track.isEmpty() && track.equals(artTrack);
        if (sameTrack && !artUrl.isEmpty()) {
            // This track already has a cover. A missing picture or a stand-in never replaces it,
            // a real cover replaces a stand-in quickly, and any other change has to last a while.
            if (url.isEmpty() || (flat && !artFlat)) {
                dropPending();
                return;
            }
            stage(bytes, url, flat, artFlat ? 200 : 1500);
            return;
        }
        if (!sameTrack) {
            // A new track: its cover is blank until its own picture arrives. The interface keeps
            // the previous cover on screen during that moment and then crossfades.
            artTrack = track;
            artBytes = null;
            artUrl = "";
            artFlat = false;
        }
        if (url.isEmpty()) {
            dropPending();
            return;
        }
        // Give a stand-in a little time to be replaced by the real cover before showing it.
        stage(bytes, url, flat, flat ? 2500 : 200);
    }

    private void stage(byte[] bytes, String url, boolean flat, long delay) {
        if (url.equals(nextUrl)) {
            return; // Already waiting for this one; do not restart the wait.
        }
        main.removeCallbacks(commitArt);
        nextBytes = bytes;
        nextUrl = url;
        nextFlat = flat;
        main.postDelayed(commitArt, delay);
    }

    private void dropPending() {
        main.removeCallbacks(commitArt);
        nextUrl = null;
        nextBytes = null;
    }

    private void commitArt() {
        synchronized (this) {
            if (nextUrl == null) {
                return;
            }
            artBytes = nextBytes;
            artUrl = nextUrl;
            artFlat = nextFlat;
            nextUrl = null;
            nextBytes = null;
        }
        changed();
    }

    /** True for pictures that are mostly one colour, such as an app logo on a plain background. */
    private static boolean isFlat(Bitmap source) {
        try {
            Bitmap bitmap = source;
            if (bitmap.getConfig() == Bitmap.Config.HARDWARE) {
                bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            }
            if (bitmap == null) {
                return false;
            }
            Bitmap small = Bitmap.createScaledBitmap(bitmap, 24, 24, true);
            int[] pixels = new int[24 * 24];
            small.getPixels(pixels, 0, 24, 0, 0, 24, 24);
            int[] counts = new int[4096];
            int most = 0;
            for (int p : pixels) {
                int key = (((p >> 20) & 0xF) << 8) | (((p >> 12) & 0xF) << 4) | ((p >> 4) & 0xF);
                int n = ++counts[key];
                if (n > most) {
                    most = n;
                }
            }
            return most > pixels.length * 0.55;
        } catch (RuntimeException | OutOfMemoryError e) {
            return false;
        }
    }

    private static String trackKeyOf(MediaMetadata m) {
        String title = firstText(m, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (title.isEmpty()) {
            return "";
        }
        return title + "\n" + firstText(m, MediaMetadata.METADATA_KEY_ARTIST,
                MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
    }

    private static byte[] encode(Bitmap source) {
        try {
            Bitmap bitmap = source;
            if (bitmap.getConfig() == Bitmap.Config.HARDWARE) {
                bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            }
            if (bitmap == null) {
                return null;
            }
            int longest = Math.max(bitmap.getWidth(), bitmap.getHeight());
            if (longest > 1200) {
                float scale = 1200f / longest;
                bitmap = Bitmap.createScaledBitmap(bitmap,
                        Math.round(bitmap.getWidth() * scale), Math.round(bitmap.getHeight() * scale), true);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out);
            return out.toByteArray();
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** Turns an image address from another app into one the interface can load. */
    static String imageUrl(String uri) {
        if (uri == null || uri.isEmpty()) {
            return "";
        }
        if (uri.startsWith("https://") || uri.startsWith("http://")) {
            return uri;
        }
        if (uri.startsWith("content://") || uri.startsWith("android.resource://")) {
            return App.ORIGIN + "/img?u=" + Uri.encode(uri);
        }
        return "";
    }

    private static String firstText(MediaMetadata m, String... keys) {
        for (String key : keys) {
            CharSequence value = m.getText(key);
            if (value != null && value.length() > 0) {
                return value.toString();
            }
        }
        return "";
    }

    synchronized byte[] currentArt() {
        return artBytes;
    }

    synchronized String snapshot() {
        JSONObject o = new JSONObject();
        try {
            boolean access = hasAccess();
            o.put("access", access);
            JSONObject sleep = new JSONObject();
            sleep.put("mode", sleepMode);
            sleep.put("at", sleepAt);
            sleep.put("minutes", sleepMinutes);
            o.put("sleep", sleep);
            o.put("sleepFired", sleepFired);
            sleepFired = false;
            if (access && !started) {
                start();
            } else if (started) {
                reselect();
            }
            MediaController c = controller;
            MediaMetadata m = c == null ? null : c.getMetadata();
            String title = m == null ? "" : firstText(m,
                    MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            if (c == null || title.isEmpty()) {
                o.put("active", false);
                return o.toString();
            }
            PlaybackState s = c.getPlaybackState();
            int state = s == null ? PlaybackState.STATE_NONE : s.getState();
            boolean playing = state == PlaybackState.STATE_PLAYING;
            long duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
            long position = 0;
            long actions = 0;
            if (s != null) {
                position = s.getPosition();
                if (playing && s.getLastPositionUpdateTime() > 0) {
                    long elapsed = SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime();
                    position += (long) (elapsed * s.getPlaybackSpeed());
                }
                actions = s.getActions();
            }
            if (position < 0) {
                position = 0;
            }
            if (duration > 0 && position > duration) {
                position = duration;
            }
            o.put("active", true);
            o.put("source", "tv");
            o.put("key", trackKeyOf(m));
            o.put("pkg", c.getPackageName());
            o.put("app", appLabel(c.getPackageName()));
            o.put("title", title);
            o.put("artist", firstText(m,
                    MediaMetadata.METADATA_KEY_ARTIST,
                    MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                    MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE));
            o.put("album", firstText(m, MediaMetadata.METADATA_KEY_ALBUM));
            o.put("duration", duration);
            o.put("position", position);
            o.put("playing", playing);
            o.put("art", artUrl);
            o.put("canNext", (actions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0);
            o.put("canPrev", (actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0);
            o.put("canSeek", (actions & PlaybackState.ACTION_SEEK_TO) != 0);
        } catch (JSONException | RuntimeException e) {
            return "{\"access\":false,\"active\":false}";
        }
        return o.toString();
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = app.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    synchronized void command(String cmd, long arg) {
        MediaController c = controller;
        if (c == null) {
            return;
        }
        try {
            MediaController.TransportControls t = c.getTransportControls();
            PlaybackState s = c.getPlaybackState();
            boolean playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
            switch (cmd) {
                case "play":
                    t.play();
                    break;
                case "pause":
                    t.pause();
                    break;
                case "toggle":
                    if (playing) {
                        t.pause();
                    } else {
                        t.play();
                    }
                    break;
                case "next":
                    t.skipToNext();
                    break;
                case "prev":
                    t.skipToPrevious();
                    break;
                case "seek":
                    t.seekTo(Math.max(0, arg));
                    break;
                case "stop":
                    t.stop();
                    break;
                default:
                    break;
            }
        } catch (RuntimeException ignored) {
            // The session went away between the button press and now.
        }
    }

    synchronized long lastPlayingAt() {
        return lastPlayingAt;
    }

    /**
     * Sets the sleep timer: "off", "track" (pause when this track ends) or a number of minutes.
     * {@code currentTrack} is the track on screen, which may be playing on a Sonos speaker.
     */
    synchronized void setSleep(String mode, String currentTrack) {
        main.removeCallbacks(sleepTask);
        sleepMode = "off";
        sleepAt = 0;
        sleepMinutes = 0;
        sleepTrack = "";
        if ("track".equals(mode)) {
            sleepMode = "track";
            sleepTrack = currentTrack == null ? "" : currentTrack;
            return;
        }
        int minutes;
        try {
            minutes = Integer.parseInt(mode);
        } catch (NumberFormatException e) {
            minutes = 0;
        }
        if (minutes > 0 && minutes <= 24 * 60) {
            sleepMode = "timer";
            sleepMinutes = minutes;
            sleepAt = System.currentTimeMillis() + minutes * 60000L;
            main.postDelayed(sleepTask, minutes * 60000L);
        }
    }

    private void fireSleep() {
        Listener l;
        synchronized (this) {
            main.removeCallbacks(sleepTask);
            sleepMode = "off";
            sleepAt = 0;
            sleepMinutes = 0;
            sleepTrack = "";
            sleepFired = true;
            l = listener;
        }
        // Pause whatever is playing, here on the TV and on Sonos.
        MediaController c = controller;
        PlaybackState s = c == null ? null : c.getPlaybackState();
        if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
            command("pause", 0);
        }
        SonosWatcher.get(app).pauseIfPlaying();
        if (l != null) {
            l.onMediaChanged();
        }
    }

    private String trackKey() {
        MediaController c = controller;
        MediaMetadata m = c == null ? null : c.getMetadata();
        return m == null ? "" : trackKeyOf(m);
    }

    /** For "End of track": told about each track on screen, wherever it plays. */
    synchronized void noteTrack(String key) {
        if (!"track".equals(sleepMode) || key == null || key.isEmpty()) {
            return;
        }
        if (sleepTrack.isEmpty()) {
            sleepTrack = key; // Nothing was playing when it was set: this is the track to finish.
        } else if (!key.equals(sleepTrack)) {
            main.post(this::fireSleep);
        }
    }

    private void changed() {
        Listener l;
        boolean playing;
        String pkg;
        String key = "";
        synchronized (this) {
            l = listener;
            MediaController c = controller;
            PlaybackState s = c == null ? null : c.getPlaybackState();
            playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
            pkg = c == null ? null : c.getPackageName();
            boolean justStarted = playing && !wasPlaying;
            wasPlaying = playing;
            if (playing) {
                lastPlayingAt = System.currentTimeMillis();
                key = trackKey();
            }
            if (justStarted) {
                maybeBringPlayerForward(pkg);
            }
        }
        // Apps blank the details for a moment between tracks; only real tracks count.
        noteTrack(key);
        if (l != null) {
            l.onMediaChanged();
        }
    }

    /**
     * When music is started from a phone, Spotify's own app takes over the TV. If the user was
     * on this home screen just before, bring the player here to the front instead. Never done
     * when the user opened Spotify themselves, or when they are in some other app.
     */
    private void maybeBringPlayerForward(String playingPackage) {
        if (!Foreground.isSpotify(playingPackage)) {
            return;
        }
        if (!"1".equals(App.prefs(app).getString("autoOpen", "1"))) {
            return;
        }
        if (Foreground.homeVisible) {
            return; // The interface switches to the player by itself.
        }
        if (Foreground.spotifyOpenedFromTile || !HomeKeyService.isEnabled(app)) {
            return;
        }
        String current = Foreground.currentPackage;
        String previous = Foreground.previousPackage;
        boolean cameFromHere = app.getPackageName().equals(previous) || Foreground.isScreensaver(previous);
        if (!Foreground.isSpotify(current) || !cameFromHere) {
            return;
        }
        main.postDelayed(() -> {
            Intent intent = new Intent(app, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(MainActivity.EXTRA_VIEW, "player");
            try {
                app.startActivity(intent);
            } catch (RuntimeException ignored) {
                // Android refused the background start; the Spotify app simply stays in front.
            }
        }, 1200);
    }
}
