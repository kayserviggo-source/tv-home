package se.tvhome.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Makes this the TV's home screen without removing the one it shipped with: the remote's Home
 * button comes here, and whenever the stock home screen appears (after the TV wakes, say) this
 * one is shown instead. The stock home screen stays installed as a fallback.
 */
public class HomeKeyService extends AccessibilityService {

    /** Home screens Android TV and Google TV ship with. */
    private static final Set<String> KNOWN_STOCK_HOMES = new HashSet<>(Arrays.asList(
            "com.google.android.tvlauncher",
            "com.google.android.apps.tv.launcherx",
            "com.google.android.leanbacklauncher"));

    private final Set<String> stockHomes = new HashSet<>();
    private long lastTakeover;

    @Override
    protected void onServiceConnected() {
        findStockHomes();
        // Right after the TV starts, show this home screen straight away. Later reconnects
        // (an update, Android restarting the service) must not pull the user out of an app.
        if (SystemClock.elapsedRealtime() < 3 * 60 * 1000 && !App.crashedRecently(this)) {
            openHome("home");
        }
    }

    private void findStockHomes() {
        stockHomes.clear();
        stockHomes.addAll(KNOWN_STOCK_HOMES);
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo info : getPackageManager().queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)) {
                if (info.activityInfo == null) {
                    continue;
                }
                String pkg = info.activityInfo.packageName;
                // Settings apps register a low-priority stand-in home screen; that is not one to replace.
                if (info.priority < 0 || pkg.contains("settings") || pkg.equals(getPackageName())) {
                    continue;
                }
                stockHomes.add(pkg);
            }
        } catch (RuntimeException ignored) {
            // The known list above still applies.
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) {
            return false;
        }
        if (App.crashedRecently(this)) {
            // Let the stock home screen answer, so a broken build cannot lock the TV.
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            return openHome("home");
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null
                || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || event.getPackageName() == null) {
            return;
        }
        String pkg = event.getPackageName().toString();
        Foreground.onWindow(pkg);
        if (!stockHomes.contains(pkg) || App.crashedRecently(this)) {
            return;
        }
        if (!"1".equals(App.prefs(this).getString("takeOver", "1"))) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastTakeover < 1500) {
            return; // Never fight over the screen in a tight loop.
        }
        lastTakeover = now;
        openHome("home");
    }

    private boolean openHome(String view) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(MainActivity.EXTRA_VIEW, view);
        try {
            startActivity(intent);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public void onInterrupt() {
    }

    static boolean isEnabled(Context context) {
        String enabled = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) {
            return false;
        }
        ComponentName me = new ComponentName(context, HomeKeyService.class);
        return enabled.contains(me.flattenToString()) || enabled.contains(me.flattenToShortString());
    }
}
