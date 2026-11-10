package se.tvhome.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** Android's answer to an update install: ask the user to confirm, or report how it went. */
public class UpdateReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @SuppressWarnings("deprecation")
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(confirm);
                } catch (RuntimeException e) {
                    Updater.get(context).onResult(false, "Android did not show the install question");
                }
            }
            return;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) {
            Updater.get(context).onResult(true, null);
        } else if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            Updater.get(context).onResult(false, "Install cancelled");
        } else {
            Updater.get(context).onResult(false, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        }
    }
}
