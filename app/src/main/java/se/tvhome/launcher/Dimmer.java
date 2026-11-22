package se.tvhome.launcher;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Locale;

/**
 * Evening dimming: how dark the screen should be right now, from where the sun is.
 *
 * The Philips TV does not let other apps turn its backlight down, so the dimming is a dark,
 * see-through layer the Home button's service lays over everything (apps, video, inputs). It
 * fades in as the sun goes down, from a little before sunset until the end of dusk, and lifts
 * the same way in the morning.
 *
 * The setting is a small file shared by the main process (which writes it from Control Centre)
 * and the Home button's process (which draws the layer).
 */
final class Dimmer {

    /** Sent from Control Centre to the Home button's process when the setting changes. */
    static final String ACTION_DIM = "se.tvhome.launcher.DIM";

    static final String[] LEVELS = {"off", "light", "medium", "strong"};
    /** How dark each level makes the screen at night (0 none, 1 black). */
    private static final float[] STRENGTH = {0f, 0.22f, 0.38f, 0.52f};

    /** Fully bright while the sun is this high or higher (degrees). */
    private static final double BRIGHT_AT = 3;
    /** Fully dimmed once the sun is this far below the horizon (end of civil dusk). */
    private static final double DIM_AT = -6;

    private Dimmer() {
    }

    static final class Setting {
        int level;
        double lat = 56.674;  // Halmstad, until the page says otherwise
        double lon = 12.857;
    }

    private static File file(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), "dim.json");
    }

    static Setting read(Context context) {
        Setting s = new Setting();
        File f = file(context);
        if (!f.exists()) {
            return s;
        }
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] bytes = new byte[(int) Math.min(f.length(), 4096)];
            int n = in.read(bytes);
            JSONObject o = new JSONObject(new String(bytes, 0, Math.max(0, n), StandardCharsets.UTF_8));
            s.level = Math.max(0, Math.min(LEVELS.length - 1, o.optInt("level", 0)));
            s.lat = o.optDouble("lat", s.lat);
            s.lon = o.optDouble("lon", s.lon);
        } catch (IOException | JSONException ignored) {
            // Defaults: off.
        }
        return s;
    }

    static void write(Context context, int level, double lat, double lon) {
        try (FileOutputStream out = new FileOutputStream(file(context))) {
            JSONObject o = new JSONObject();
            o.put("level", Math.max(0, Math.min(LEVELS.length - 1, level)));
            o.put("lat", lat);
            o.put("lon", lon);
            out.write(o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | JSONException ignored) {
            // The old setting stays.
        }
    }

    /** How dark the layer should be at full strength for a level (for a short preview). */
    static float strength(int level) {
        return STRENGTH[Math.max(0, Math.min(STRENGTH.length - 1, level))];
    }

    /** How dark the layer should be now: 0 by day, the level's strength by night, a ramp between. */
    static float now(Setting s, long millis) {
        if (s.level <= 0) {
            return 0f;
        }
        double e = elevation(s.lat, s.lon, millis);
        double night = e >= BRIGHT_AT ? 0 : e <= DIM_AT ? 1 : (BRIGHT_AT - e) / (BRIGHT_AT - DIM_AT);
        // Ease the ramp so the change is gentle at both ends.
        night = night * night * (3 - 2 * night);
        return (float) (STRENGTH[s.level] * night);
    }

    /** Today's sunset and next sunrise as "HH:MM", for Control Centre. */
    static JSONObject describe(Context context) {
        Setting s = read(context);
        JSONObject o = new JSONObject();
        try {
            Calendar day = Calendar.getInstance();
            day.set(Calendar.HOUR_OF_DAY, 12);
            day.set(Calendar.MINUTE, 0);
            day.set(Calendar.SECOND, 0);
            long noon = day.getTimeInMillis();
            o.put("level", s.level);
            o.put("sunset", crossing(s, noon, 1));
            o.put("sunrise", crossing(s, noon + 12 * 3600 * 1000L, 1));
            o.put("amount", now(s, System.currentTimeMillis()));
        } catch (JSONException ignored) {
            // Empty description.
        }
        return o;
    }

    /** The first minute after start when the sun goes below (or, after midnight, above) the horizon. */
    private static String crossing(Setting s, long start, int stepMinutes) {
        boolean wasUp = elevation(s.lat, s.lon, start) > -0.833;
        for (int m = stepMinutes; m <= 18 * 60; m += stepMinutes) {
            long t = start + m * 60 * 1000L;
            boolean up = elevation(s.lat, s.lon, t) > -0.833;
            if (up != wasUp) {
                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(t);
                return String.format(Locale.ROOT, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
            }
        }
        return "";
    }

    /** The sun's height above the horizon in degrees (NOAA's approximation, accurate to a fraction of a degree). */
    static double elevation(double lat, double lon, long millis) {
        double days = millis / 86400000.0 + 2440587.5 - 2451545.0; // days since J2000
        double g = Math.toRadians((357.529 + 0.98560028 * days) % 360);
        double q = (280.459 + 0.98564736 * days) % 360;
        double l = Math.toRadians((q + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g)) % 360);
        double e = Math.toRadians(23.439 - 0.00000036 * days);
        double ra = Math.atan2(Math.cos(e) * Math.sin(l), Math.cos(l));
        double dec = Math.asin(Math.sin(e) * Math.sin(l));
        double gmst = (18.697374558 + 24.06570982441908 * days) % 24;
        double hourAngle = Math.toRadians(gmst * 15 + lon) - ra;
        double phi = Math.toRadians(lat);
        double alt = Math.asin(Math.sin(phi) * Math.sin(dec) + Math.cos(phi) * Math.cos(dec) * Math.cos(hourAngle));
        return Math.toDegrees(alt);
    }
}
