package se.tvhome.launcher;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Serves the interface from the app's own files under a private address, plus the images
 * that only native code can reach (app banners, the current cover, other apps' posters).
 */
final class AssetClient extends WebViewClient {

    private static final String HOST = "tvhome.local";

    private final Context context;

    AssetClient(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        // The interface never navigates away from its own pages.
        return !HOST.equals(request.getUrl().getHost());
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        Uri url = request.getUrl();
        if (!HOST.equals(url.getHost())) {
            return null;
        }
        String path = url.getPath() == null ? "/" : url.getPath();
        try {
            if (path.startsWith("/banner/")) {
                return image(Apps.banner(context, path.substring("/banner/".length())), "image/png", true);
            }
            if (path.startsWith("/icon/")) {
                return image(Apps.icon(context, path.substring("/icon/".length())), "image/png", true);
            }
            if (path.equals("/art/now")) {
                return image(MediaWatcher.get(context).currentArt(), "image/jpeg", true);
            }
            if (path.equals("/img")) {
                return foreignImage(url.getQueryParameter("u"));
            }
            String file = path.equals("/") ? "index.html" : path.substring(1);
            if (file.contains("..")) {
                return notFound();
            }
            InputStream in = context.getAssets().open("ui/" + file);
            String type = mime(file);
            // Never let an older copy of the interface outlive an update of the app.
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", file.endsWith(".woff2") ? "max-age=86400" : "no-cache");
            return new WebResourceResponse(type, type.startsWith("text/") || type.contains("javascript")
                    ? "utf-8" : null, 200, "OK", headers, in);
        } catch (IOException | RuntimeException e) {
            return notFound();
        }
    }

    private WebResourceResponse foreignImage(String address) throws IOException {
        if (address == null
                || !(address.startsWith("content://") || address.startsWith("android.resource://"))) {
            return notFound();
        }
        Uri uri = Uri.parse(address);
        InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) {
            return notFound();
        }
        String type = context.getContentResolver().getType(uri);
        return new WebResourceResponse(type == null || !type.startsWith("image/") ? "image/jpeg" : type, null, in);
    }

    private static WebResourceResponse image(byte[] bytes, String type, boolean cache) {
        if (bytes == null) {
            return notFound();
        }
        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", cache ? "max-age=86400" : "no-store");
        return new WebResourceResponse(type, null, 200, "OK", headers, new ByteArrayInputStream(bytes));
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
    }

    private static String mime(String file) {
        String name = file.toLowerCase();
        if (name.endsWith(".html")) {
            return "text/html";
        }
        if (name.endsWith(".css")) {
            return "text/css";
        }
        if (name.endsWith(".js")) {
            return "application/javascript";
        }
        if (name.endsWith(".json")) {
            return "application/json";
        }
        if (name.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".woff2")) {
            return "font/woff2";
        }
        return "application/octet-stream";
    }
}
