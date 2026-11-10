package se.tvhome.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Posters and covers, shrunk to the size they are shown at and kept.
 *
 * Apps publish posters at full HD or larger. Decoding pictures that size while the remote is
 * moving is what made Home stutter, so they are fetched and shrunk here, off the interface's
 * thread, once, and the page only ever decodes small JPEGs. Kept in memory and on disk.
 */
final class Posters {

    private static final int MAX_DOWNLOAD = 12 * 1024 * 1024;
    private static final long DISK_DAYS = 14;

    private static final LruCache<String, byte[]> MEMORY = new LruCache<String, byte[]>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, byte[] value) {
            return value.length;
        }
    };
    private static volatile boolean pruned;

    private Posters() {
    }

    /** The address the page uses for a picture shrunk to the given width. */
    static String url(String uri, int width) {
        if (uri == null || uri.isEmpty()) {
            return "";
        }
        if (!(uri.startsWith("https://") || uri.startsWith("http://")
                || uri.startsWith("content://") || uri.startsWith("android.resource://"))) {
            return "";
        }
        return App.ORIGIN + "/poster?w=" + width + "&u=" + Uri.encode(uri);
    }

    static byte[] get(Context context, String uri, int width) throws IOException {
        if (uri == null || uri.isEmpty()) {
            return null;
        }
        width = Math.max(64, Math.min(1280, width));
        String key = width + "|" + uri;
        byte[] bytes = MEMORY.get(key);
        if (bytes != null) {
            return bytes;
        }
        File dir = new File(context.getCacheDir(), "posters");
        pruneOnce(dir);
        File file = new File(dir, hash(key) + ".jpg");
        if (file.exists()) {
            bytes = read(new FileInputStream(file), MAX_DOWNLOAD);
            if (bytes != null && bytes.length > 0) {
                MEMORY.put(key, bytes);
                return bytes;
            }
        }
        byte[] raw = fetch(context, uri);
        if (raw == null) {
            return null;
        }
        bytes = shrink(raw, width);
        if (bytes == null) {
            return null;
        }
        MEMORY.put(key, bytes);
        save(dir, file, bytes);
        return bytes;
    }

    private static byte[] fetch(Context context, String uri) throws IOException {
        if (uri.startsWith("content://") || uri.startsWith("android.resource://")) {
            InputStream in = context.getContentResolver().openInputStream(Uri.parse(uri));
            return in == null ? null : read(in, MAX_DOWNLOAD);
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(uri).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(12000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "TVHome/1 (Android TV)");
        try {
            int code = connection.getResponseCode();
            if (code / 100 == 3) {
                // Redirects between http and https are not followed automatically.
                String next = connection.getHeaderField("Location");
                connection.disconnect();
                if (next == null) {
                    return null;
                }
                connection = (HttpURLConnection) new URL(new URL(uri), next).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(12000);
                code = connection.getResponseCode();
            }
            if (code != 200) {
                return null;
            }
            return read(connection.getInputStream(), MAX_DOWNLOAD);
        } finally {
            connection.disconnect();
        }
    }

    /** Decodes no more pixels than needed, then scales to the exact width. */
    private static byte[] shrink(byte[] raw, int width) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= width) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.length, options);
        if (bitmap == null) {
            return null;
        }
        if (bitmap.getWidth() > width * 1.1f) {
            int height = Math.max(1, Math.round(bitmap.getHeight() * (width / (float) bitmap.getWidth())));
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, width, height, true);
            if (scaled != bitmap) {
                bitmap.recycle();
                bitmap = scaled;
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean png = bitmap.hasAlpha();
        bitmap.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 84, out);
        bitmap.recycle();
        return out.toByteArray();
    }

    static String type(byte[] bytes) {
        return bytes != null && bytes.length > 4 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' ? "image/png" : "image/jpeg";
    }

    private static byte[] read(InputStream in, int limit) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int total = 0;
            int n;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > limit) {
                    return null;
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void save(File dir, File file, byte[] bytes) {
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            File temp = new File(dir, file.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(bytes);
            }
            if (!temp.renameTo(file)) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        } catch (IOException ignored) {
            // The memory copy still serves this session.
        }
    }

    private static void pruneOnce(File dir) {
        if (pruned) {
            return;
        }
        pruned = true;
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - DISK_DAYS * 24 * 60 * 60 * 1000;
        for (File f : files) {
            if (f.lastModified() < cutoff) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    private static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(text.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException | IOException e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
