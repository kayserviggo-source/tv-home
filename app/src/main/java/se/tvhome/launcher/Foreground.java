package se.tvhome.launcher;

/**
 * What is in front on the TV, as far as this app can tell. Written by MainActivity and
 * HomeKeyService, read by MediaWatcher to decide whether to bring the player forward.
 */
final class Foreground {

    private Foreground() {
    }

    /** True while MainActivity is resumed. */
    static volatile boolean homeVisible;

    /** Package now in front and the one before it, from accessibility window events. */
    static volatile String currentPackage;
    static volatile String previousPackage;

    /** True when the user opened Spotify from a tile here, so it is not taken away from them. */
    static volatile boolean spotifyOpenedFromTile;

    static void onWindow(String pkg) {
        if (pkg == null || pkg.equals(currentPackage)) {
            return;
        }
        previousPackage = currentPackage;
        currentPackage = pkg;
    }

    static boolean isSpotify(String pkg) {
        return pkg != null && pkg.startsWith("com.spotify.");
    }

    static boolean isScreensaver(String pkg) {
        return pkg != null && (pkg.contains("dreams") || pkg.equals("com.google.android.backdrop"));
    }
}
