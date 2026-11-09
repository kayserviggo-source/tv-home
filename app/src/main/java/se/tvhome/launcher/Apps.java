package se.tvhome.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The apps installed on the TV, with the artwork each one ships for TV home screens. */
final class Apps {

    private Apps() {
    }

    private static final Map<String, byte[]> IMAGE_CACHE = new HashMap<>();

    static JSONArray list(Context context) {
        PackageManager pm = context.getPackageManager();
        Map<String, JSONObject> found = new LinkedHashMap<>();
        collect(context, pm, Intent.CATEGORY_LEANBACK_LAUNCHER, true, found);
        collect(context, pm, Intent.CATEGORY_LAUNCHER, false, found);
        List<JSONObject> sorted = new ArrayList<>(found.values());
        Collections.sort(sorted, (a, b) ->
                a.optString("label").compareToIgnoreCase(b.optString("label")));
        JSONArray out = new JSONArray();
        for (JSONObject o : sorted) {
            out.put(o);
        }
        return out;
    }

    private static void collect(Context context, PackageManager pm, String category, boolean tv,
                                Map<String, JSONObject> found) {
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> infos;
        try {
            infos = pm.queryIntentActivities(intent, 0);
        } catch (RuntimeException e) {
            return;
        }
        for (ResolveInfo info : infos) {
            ActivityInfo activity = info.activityInfo;
            if (activity == null) {
                continue;
            }
            String pkg = activity.packageName;
            if (pkg.equals(context.getPackageName()) || found.containsKey(pkg)) {
                continue;
            }
            try {
                JSONObject o = new JSONObject();
                o.put("pkg", pkg);
                o.put("label", String.valueOf(info.loadLabel(pm)));
                o.put("tv", tv);
                boolean banner = activity.banner != 0
                        || (activity.applicationInfo != null && activity.applicationInfo.banner != 0);
                o.put("banner", banner);
                found.put(pkg, o);
            } catch (JSONException ignored) {
                // Skip an app whose details cannot be read.
            }
        }
    }

    static Intent launchIntent(PackageManager pm, String pkg) {
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (intent == null) {
            intent = pm.getLaunchIntentForPackage(pkg);
        }
        return intent;
    }

    /** The app's 16:9 TV banner as PNG, or null if it has none. */
    static byte[] banner(Context context, String pkg) {
        String key = "banner:" + pkg;
        synchronized (IMAGE_CACHE) {
            if (IMAGE_CACHE.containsKey(key)) {
                return IMAGE_CACHE.get(key);
            }
        }
        PackageManager pm = context.getPackageManager();
        Drawable drawable = null;
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(pkg);
            for (ResolveInfo info : pm.queryIntentActivities(intent, 0)) {
                if (info.activityInfo != null) {
                    drawable = info.activityInfo.loadBanner(pm);
                    if (drawable != null) {
                        break;
                    }
                }
            }
            if (drawable == null) {
                drawable = pm.getApplicationBanner(pkg);
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            drawable = null;
        }
        byte[] bytes = drawable == null ? null : png(drawable, 640, 360);
        synchronized (IMAGE_CACHE) {
            IMAGE_CACHE.put(key, bytes);
        }
        return bytes;
    }

    /** The app's square icon as PNG, for apps without a TV banner. */
    static byte[] icon(Context context, String pkg) {
        String key = "icon:" + pkg;
        synchronized (IMAGE_CACHE) {
            if (IMAGE_CACHE.containsKey(key)) {
                return IMAGE_CACHE.get(key);
            }
        }
        byte[] bytes = null;
        try {
            Drawable drawable = context.getPackageManager().getApplicationIcon(pkg);
            bytes = png(drawable, 192, 192);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            bytes = null;
        }
        synchronized (IMAGE_CACHE) {
            IMAGE_CACHE.put(key, bytes);
        }
        return bytes;
    }

    private static byte[] png(Drawable drawable, int width, int height) {
        try {
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, width, height);
            drawable.draw(canvas);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            bitmap.recycle();
            return out.toByteArray();
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }
}
