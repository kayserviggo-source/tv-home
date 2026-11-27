package se.tvhome.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * A short diary of remote presses, to find out why a direction button sometimes keeps repeating
 * on its own although nobody holds it.
 *
 * The Home button service sees each press as it comes from the remote; the home screen sees what
 * Android hands on after that. Each writes its own file in the app's folder on the TV's storage
 * (Android/data/se.tvhome.launcher/files), which tv-check.bat collects. Only presses and releases
 * are written (and when a repeat runs on too long), the writing happens off the main thread, and
 * each file stays small.
 */
final class KeyJournal {

    private static final long MAX_BYTES = 48 * 1024;
    private static Handler writer;

    private KeyJournal() {
    }

    static void note(Context context, String file, KeyEvent event, String remark) {
        try {
            final long lag = SystemClock.uptimeMillis() - event.getEventTime();
            final long held = event.getEventTime() - event.getDownTime();
            final String action = event.getAction() == KeyEvent.ACTION_DOWN ? "down"
                    : event.getAction() == KeyEvent.ACTION_UP ? "up" : "other";
            final String key = KeyEvent.keyCodeToString(event.getKeyCode()).replace("KEYCODE_", "");
            final int repeat = event.getRepeatCount();
            final int deviceId = event.getDeviceId();
            final int flags = event.getFlags();
            final long downTime = event.getDownTime();
            final long wall = System.currentTimeMillis();
            final Context app = context.getApplicationContext();
            handler().post(() -> {
                InputDevice device = InputDevice.getDevice(deviceId);
                String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date(wall))
                        + " " + action + " " + key + " repeat=" + repeat + " held=" + held + "ms"
                        + " late=" + lag + "ms press=" + downTime + " flags=0x" + Integer.toHexString(flags)
                        + " from=" + deviceId + ":" + (device == null ? "?" : device.getName())
                        + (remark == null ? "" : "  << " + remark) + "\n";
                write(app, file, line);
            });
        } catch (RuntimeException ignored) {
            // The diary must never get in the way of a key press.
        }
    }

    private static synchronized Handler handler() {
        if (writer == null) {
            HandlerThread thread = new HandlerThread("key-journal");
            thread.start();
            writer = new Handler(thread.getLooper());
        }
        return writer;
    }

    private static void write(Context context, String name, String line) {
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) {
                return;
            }
            File out = new File(dir, name + ".txt");
            if (out.length() > MAX_BYTES) {
                File old = new File(dir, name + "-older.txt");
                old.delete();
                out.renameTo(old);
            }
            try (FileWriter w = new FileWriter(out, true)) {
                w.write(line);
            }
        } catch (IOException | RuntimeException ignored) {
            // Storage full or not there: nothing to note it in.
        }
    }
}
