package se.tvhome.launcher;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What other apps publish for TV home screens: Continue watching entries and the rows of
 * suggestions each app keeps. Apps decide what they share, so any of this can be empty.
 */
final class TvData {

    private TvData() {
    }

    static final String PERMISSION = "android.permission.READ_TV_LISTINGS";
    static final String LEGACY_PERMISSION = "com.android.providers.tv.permission.READ_EPG_DATA";

    private static final long MAX_AGE_MS = 60L * 24 * 60 * 60 * 1000;

    static boolean allowed(Context context) {
        return context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(LEGACY_PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Continue watching. The query passes no filter and names its columns: without system
     * rights a filter is refused, and some TVs (Philips among them) fail on the default column list.
     */
    static JSONArray watchNext(Context context) {
        List<JSONObject> rows = new ArrayList<>();
        String[] columns = {
                TvContract.WatchNextPrograms._ID,
                TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
                TvContract.WatchNextPrograms.COLUMN_TITLE,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
                TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
                TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
                TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
                TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
        };
        long now = System.currentTimeMillis();
        try (Cursor c = context.getContentResolver().query(
                TvContract.WatchNextPrograms.CONTENT_URI, columns, null, null, null)) {
            if (c == null) {
                return new JSONArray();
            }
            while (c.moveToNext()) {
                String pkg = c.getString(1);
                String title = text(c, 2);
                String poster = MediaWatcher.imageUrl(text(c, 6));
                String intent = text(c, 7);
                if (pkg == null || pkg.equals(context.getPackageName()) || intent.isEmpty()) {
                    continue;
                }
                if (title.isEmpty() && poster.isEmpty()) {
                    continue;
                }
                long engaged = c.getLong(9);
                if (engaged > 0 && now - engaged > MAX_AGE_MS) {
                    continue;
                }
                long position = c.getLong(10);
                long duration = c.getLong(11);
                double progress = duration > 0 && position > 0 ? (double) position / duration : -1;
                int type = c.getInt(8);
                if (type == TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE && progress >= 0.97) {
                    continue;
                }
                String season = text(c, 4);
                String episode = text(c, 5);
                String episodeTitle = text(c, 3);
                StringBuilder sub = new StringBuilder();
                if (!season.isEmpty()) {
                    sub.append("S").append(season);
                }
                if (!episode.isEmpty()) {
                    sub.append(sub.length() > 0 ? " · " : "").append("E").append(episode);
                }
                if (!episodeTitle.isEmpty()) {
                    sub.append(sub.length() > 0 ? " · " : "").append(episodeTitle);
                }
                JSONObject o = new JSONObject();
                o.put("id", c.getLong(0));
                o.put("pkg", pkg);
                o.put("app", label(context, pkg));
                o.put("title", title);
                o.put("sub", sub.toString());
                o.put("poster", poster);
                o.put("intent", intent);
                o.put("progress", progress);
                o.put("left", duration > 0 && position > 0 ? duration - position : -1);
                o.put("engaged", engaged);
                rows.add(o);
            }
        } catch (JSONException | RuntimeException e) {
            return new JSONArray();
        }
        Collections.sort(rows, (a, b) -> Long.compare(b.optLong("engaged"), a.optLong("engaged")));
        JSONArray out = new JSONArray();
        for (int i = 0; i < rows.size() && i < 12; i++) {
            out.put(rows.get(i));
        }
        return out;
    }

    /** Each app's own rows of suggestions, grouped as the app publishes them. */
    static JSONArray previews(Context context) {
        Map<Long, JSONObject> channels = new LinkedHashMap<>();
        Map<String, Integer> perPackage = new HashMap<>();
        try {
            String[] channelColumns = {
                    TvContract.Channels._ID,
                    TvContract.Channels.COLUMN_PACKAGE_NAME,
                    TvContract.Channels.COLUMN_DISPLAY_NAME,
                    TvContract.Channels.COLUMN_TYPE,
            };
            try (Cursor c = context.getContentResolver().query(
                    TvContract.Channels.CONTENT_URI, channelColumns, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    if (!TvContract.Channels.TYPE_PREVIEW.equals(c.getString(3))) {
                        continue;
                    }
                    String pkg = c.getString(1);
                    if (pkg == null || pkg.equals(context.getPackageName())) {
                        continue;
                    }
                    JSONObject o = new JSONObject();
                    o.put("pkg", pkg);
                    o.put("app", label(context, pkg));
                    o.put("name", text(c, 2));
                    o.put("items", new JSONArray());
                    channels.put(c.getLong(0), o);
                }
            }
            String[] programColumns = {
                    TvContract.PreviewPrograms._ID,
                    TvContract.PreviewPrograms.COLUMN_CHANNEL_ID,
                    TvContract.PreviewPrograms.COLUMN_TITLE,
                    TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
                    TvContract.PreviewPrograms.COLUMN_INTENT_URI,
            };
            try (Cursor c = context.getContentResolver().query(
                    TvContract.PreviewPrograms.CONTENT_URI, programColumns, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    JSONObject channel = channels.get(c.getLong(1));
                    if (channel == null) {
                        continue;
                    }
                    JSONArray items = channel.getJSONArray("items");
                    String poster = MediaWatcher.imageUrl(text(c, 3));
                    String intent = text(c, 4);
                    if (items.length() >= 12 || poster.isEmpty() || intent.isEmpty()) {
                        continue;
                    }
                    JSONObject o = new JSONObject();
                    o.put("id", c.getLong(0));
                    o.put("pkg", channel.getString("pkg"));
                    o.put("title", text(c, 2));
                    o.put("poster", poster);
                    o.put("intent", intent);
                    items.put(o);
                }
            }
        } catch (JSONException | RuntimeException e) {
            return new JSONArray();
        }
        JSONArray out = new JSONArray();
        for (JSONObject channel : channels.values()) {
            JSONArray items = channel.optJSONArray("items");
            if (items == null || items.length() < 3) {
                continue;
            }
            String pkg = channel.optString("pkg");
            int count = perPackage.containsKey(pkg) ? perPackage.get(pkg) : 0;
            if (count >= 2) {
                continue;
            }
            perPackage.put(pkg, count + 1);
            out.put(channel);
        }
        return out;
    }

    /** HDMI and other pass-through inputs. */
    static JSONArray inputs(Context context) {
        JSONArray out = new JSONArray();
        try {
            TvInputManager manager = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
            if (manager == null) {
                return out;
            }
            for (TvInputInfo info : manager.getTvInputList()) {
                if (!info.isPassthroughInput() || info.isHidden(context)) {
                    continue;
                }
                CharSequence label = info.loadCustomLabel(context);
                if (label == null || label.length() == 0) {
                    label = info.loadLabel(context);
                }
                JSONObject o = new JSONObject();
                o.put("id", info.getId());
                o.put("label", String.valueOf(label));
                out.put(o);
            }
        } catch (JSONException | RuntimeException e) {
            return new JSONArray();
        }
        return out;
    }

    private static String text(Cursor c, int column) {
        String value = c.getString(column);
        return value == null ? "" : value;
    }

    private static String label(Context context, String pkg) {
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }
}
