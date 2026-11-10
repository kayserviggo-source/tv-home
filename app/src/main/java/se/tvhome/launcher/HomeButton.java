package se.tvhome.launcher;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the remote's Home button coming here.
 *
 * Android stops an accessibility service for good when its app is force-stopped (it takes it
 * off the list of enabled services) and, on some versions, after its process dies. Either way
 * the Home button quietly goes back to the stock home screen until someone switches the
 * service off and on again in Settings. With the WRITE_SECURE_SETTINGS permission (granted
 * once over adb), this does that switching itself.
 */
final class HomeButton {

    static final String SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS";

    private static long lastFix;

    private HomeButton() {
    }

    private static ComponentName component(Context context) {
        return new ComponentName(context, HomeKeyService.class);
    }

    /** On in Settings → Accessibility. */
    static boolean isEnabled(Context context) {
        String enabled = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) {
            return false;
        }
        ComponentName me = component(context);
        for (String part : enabled.split(":")) {
            if (part.equalsIgnoreCase(me.flattenToString()) || part.equalsIgnoreCase(me.flattenToShortString())) {
                return true;
            }
        }
        return false;
    }

    /** Actually connected: Android lists only the services it has running. */
    static boolean isRunning(Context context) {
        AccessibilityManager manager = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager == null) {
            return false;
        }
        ComponentName me = component(context);
        List<AccessibilityServiceInfo> running =
                manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        if (running == null) {
            return false;
        }
        for (AccessibilityServiceInfo info : running) {
            String id = info.getId();
            if (me.flattenToShortString().equals(id) || me.flattenToString().equals(id)) {
                return true;
            }
        }
        return false;
    }

    static boolean canFix(Context context) {
        return context.checkSelfPermission(SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Called when TV Home opens, at boot and after an update. Mends the Home button only if the
     * user has it on (or had it on before Android dropped it), never if they turned it off.
     * With onlyIfOff, a service that is on but not (yet) connected is left alone.
     */
    static void fixIfNeeded(Context context, boolean onlyIfOff) {
        Context app = context.getApplicationContext();
        if (!canFix(app) || !App.homeWanted(app)) {
            return;
        }
        boolean enabled = isEnabled(app);
        if (!enabled) {
            fix(app);
        } else if (!onlyIfOff && SystemClock.elapsedRealtime() > 2 * 60 * 1000 && !isRunning(app)) {
            // Right after start-up Android may simply not have connected it yet.
            fix(app);
        }
    }

    /**
     * Turns the service on, or off and on again if Android has it on but not running.
     * Returns false without the permission.
     */
    static boolean fix(Context context) {
        final Context app = context.getApplicationContext();
        if (!canFix(app)) {
            return false;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastFix < 20 * 1000) {
            return true; // Already on its way.
        }
        lastFix = now;
        App.setHomeWanted(app, true);
        try {
            if (!isEnabled(app)) {
                write(app, true);
            } else if (!isRunning(app)) {
                // Off first, and on a moment later: Android forgets that the service had stopped
                // only when it sees it switched off.
                write(app, false);
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        write(app, true);
                    } catch (SecurityException ignored) {
                        // Permission taken away in between; nothing more to do.
                    }
                }, 1500);
            }
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    private static void write(Context context, boolean on) {
        ContentResolver resolver = context.getContentResolver();
        ComponentName me = component(context);
        String current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        List<String> parts = new ArrayList<>();
        if (current != null) {
            for (String part : current.split(":")) {
                if (!part.isEmpty() && !part.equalsIgnoreCase(me.flattenToString())
                        && !part.equalsIgnoreCase(me.flattenToShortString())) {
                    parts.add(part);
                }
            }
        }
        if (on) {
            parts.add(me.flattenToString());
        }
        Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, TextUtils.join(":", parts));
        if (on) {
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        }
    }
}
