package se.tvhome.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Makes this the TV's home screen without removing the one it shipped with: the remote's Home
 * button comes here, and whenever the stock home screen appears (after the TV wakes, say) this
 * one is shown instead. The stock home screen stays installed as a fallback.
 *
 * Runs in a small process of its own (see the manifest), so a crash in the main process, or
 * Android closing it to free memory, does not take the Home button with it.
 */
public class HomeKeyService extends AccessibilityService {

    /** Home screens Android TV and Google TV ship with. */
    private static final Set<String> KNOWN_STOCK_HOMES = new HashSet<>(Arrays.asList(
            "com.google.android.tvlauncher",
            "com.google.android.apps.tv.launcherx",
            "com.google.android.leanbacklauncher"));

    private final Set<String> stockHomes = new HashSet<>();
    private long lastTakeover;
    private String lastWindow;

    /** The main process asks through this to bring TV Home forward (the player, when music starts). */
    private final BroadcastReceiver openRequests = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String view = intent.getStringExtra(MainActivity.EXTRA_VIEW);
            openHome(view == null ? "home" : view);
        }
    };
    private boolean listening;

    @Override
    protected void onServiceConnected() {
        App.setHomeWanted(this, true);
        findStockHomes();
        if (!listening) {
            IntentFilter filter = new IntentFilter(App.ACTION_OPEN);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(openRequests, filter, App.PERMISSION_INTERNAL, null, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(openRequests, filter, App.PERMISSION_INTERNAL, null);
            }
            listening = true;
        }
        // Right after the TV starts, show this home screen straight away, and after an update
        // started from here, bring the new build up. Other reconnects (Android restarting the
        // service, say) must not pull the user out of an app.
        boolean updated = App.takeReopen(this);
        if ((updated || SystemClock.elapsedRealtime() < 3 * 60 * 1000) && !App.crashedRecently(this)) {
            openHome("home");
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // Android unbinds like this only when the service is switched off in Settings (a crash or
        // a force stop ends the process without it), so it was the user's choice: respect it.
        App.setHomeWanted(this, false);
        return super.onUnbind(intent);
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
        if (!pkg.equals(lastWindow)) {
            lastWindow = pkg;
            // Tell the main process, which decides whether to bring the player forward.
            Intent window = new Intent(App.ACTION_WINDOW).setPackage(getPackageName()).putExtra("pkg", pkg);
            sendBroadcast(window, App.PERMISSION_INTERNAL);
        }
        if (!stockHomes.contains(pkg) || App.crashedRecently(this)) {
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

    @Override
    public void onDestroy() {
        if (listening) {
            try {
                unregisterReceiver(openRequests);
            } catch (IllegalArgumentException ignored) {
                // Already gone.
            }
            listening = false;
        }
        super.onDestroy();
    }

    static boolean isEnabled(Context context) {
        return HomeButton.isEnabled(context);
    }
}
