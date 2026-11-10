package se.tvhome.launcher;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class App extends Application {

    static final String PREFS = "tvhome";
    static final String ORIGIN = "https://tvhome.local";

    /** Only this app's own processes may send each other these broadcasts. */
    static final String PERMISSION_INTERNAL = "se.tvhome.launcher.permission.INTERNAL";
    /** From the Home button's process to the main one: which app is now in front. */
    static final String ACTION_WINDOW = "se.tvhome.launcher.WINDOW";
    /** From the main process to the Home button's: open TV Home (only that process may start it from the background). */
    static final String ACTION_OPEN = "se.tvhome.launcher.OPEN";

    /** How long the Home button is left alone after a crash, so the stock home screen stays reachable. */
    static final long CRASH_GRACE_MS = 2 * 60 * 1000;

    /*
     * The Home button's service runs in a process of its own, so it keeps working when the
     * main process (with the web view) crashes or is closed for memory. The two processes
     * share small marker files rather than preferences, which each process caches.
     */
    private static final String MARK_CRASH = "crashed";
    private static final String MARK_WANTED = "home-wanted";
    private static final String MARK_REOPEN = "reopen";

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                mark(this, MARK_CRASH, true);
            } catch (Throwable ignored) {
                // Nothing useful can be done here; fall through to the default handler.
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
        if (isMainProcess()) {
            listenForWindows();
        }
    }

    private boolean isMainProcess() {
        String name = null;
        if (Build.VERSION.SDK_INT >= 28) {
            name = Application.getProcessName();
        } else {
            try (FileInputStream in = new FileInputStream("/proc/self/cmdline")) {
                byte[] buffer = new byte[256];
                int length = in.read(buffer);
                int end = 0;
                while (end < Math.max(0, length) && buffer[end] != 0) {
                    end++;
                }
                name = new String(buffer, 0, end, "UTF-8");
            } catch (IOException ignored) {
                // Assume the main process; registering twice does no harm.
            }
        }
        return name == null || name.equals(getPackageName());
    }

    /** Hears from the Home button's process which app is in front, for the player's auto-open. */
    private void listenForWindows() {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Foreground.onWindow(intent.getStringExtra("pkg"));
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_WINDOW);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, PERMISSION_INTERNAL, null, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter, PERMISSION_INTERNAL, null);
        }
    }

    static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    static boolean crashedRecently(Context context) {
        return markAge(context, MARK_CRASH) < CRASH_GRACE_MS;
    }

    /** The user wants the Home button here: set while the service runs, cleared when they turn it off. */
    static void setHomeWanted(Context context, boolean wanted) {
        mark(context, MARK_WANTED, wanted);
    }

    static boolean homeWanted(Context context) {
        return markAge(context, MARK_WANTED) < Long.MAX_VALUE;
    }

    /** Set just before an update installs, so the new build opens itself afterwards. */
    static void setReopen(Context context, boolean reopen) {
        mark(context, MARK_REOPEN, reopen);
    }

    /** True once, within a quarter of an hour of an update being started from here. */
    static boolean takeReopen(Context context) {
        boolean recent = markAge(context, MARK_REOPEN) < 15 * 60 * 1000;
        mark(context, MARK_REOPEN, false);
        return recent;
    }

    private static File markFile(Context context, String name) {
        return new File(context.getApplicationContext().getFilesDir(), name);
    }

    private static void mark(Context context, String name, boolean on) {
        File file = markFile(context, name);
        try {
            if (!on) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                return;
            }
            if (!file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.createNewFile();
            }
            //noinspection ResultOfMethodCallIgnored
            file.setLastModified(System.currentTimeMillis());
        } catch (IOException | SecurityException ignored) {
            // Markers are a convenience; without one the defaults apply.
        }
    }

    /** How long ago a marker was set, or Long.MAX_VALUE if it is not set. */
    private static long markAge(Context context, String name) {
        File file = markFile(context, name);
        if (!file.exists()) {
            return Long.MAX_VALUE;
        }
        return Math.max(0, System.currentTimeMillis() - file.lastModified());
    }
}
