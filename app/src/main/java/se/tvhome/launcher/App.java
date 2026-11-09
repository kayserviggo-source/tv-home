package se.tvhome.launcher;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

public class App extends Application {

    static final String PREFS = "tvhome";
    static final String ORIGIN = "https://tvhome.local";

    /** How long the Home button is left alone after a crash, so the stock home screen stays reachable. */
    static final long CRASH_GRACE_MS = 2 * 60 * 1000;

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                prefs(this).edit().putLong("lastCrash", System.currentTimeMillis()).commit();
            } catch (Throwable ignored) {
                // Nothing useful can be done here; fall through to the default handler.
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
    }

    static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    static boolean crashedRecently(Context context) {
        long last = prefs(context).getLong("lastCrash", 0);
        return last > 0 && System.currentTimeMillis() - last < CRASH_GRACE_MS;
    }
}
