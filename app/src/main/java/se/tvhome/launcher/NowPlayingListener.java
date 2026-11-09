package se.tvhome.launcher;

import android.service.notification.NotificationListenerService;

/**
 * Android only lets an app see other apps' media sessions (what is playing) when it holds
 * notification access. This service exists to hold that access; it never reads notifications.
 */
public class NowPlayingListener extends NotificationListenerService {

    @Override
    public void onListenerConnected() {
        MediaWatcher.get(this).start();
    }
}
