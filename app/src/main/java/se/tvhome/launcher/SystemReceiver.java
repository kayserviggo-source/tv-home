package se.tvhome.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After the TV starts, and after TV Home is updated: make sure the Home button still comes here. */
public class SystemReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            // Android is still connecting services at these moments, so only switch it back on
            // if it was taken off the list altogether.
            HomeButton.fixIfNeeded(context, true);
        }
    }
}
