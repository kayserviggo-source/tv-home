package se.tvhome.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TV Home's own sign-in to Spotify, so Home can show the owner's music: what they played
 * recently, their playlists, their top artists and their name.
 *
 * Signing in happens on a phone. The TV shows a QR code with Spotify's sign-in address; after
 * the owner agrees, Spotify sends the phone to a small page on GitHub Pages (docs/spotify.html),
 * which forwards it to this TV on the home network, where a short-lived listener takes the code.
 * The code is useless without a secret that never leaves the TV (PKCE). Tokens are kept in the
 * app's private storage; nothing is sent anywhere but to Spotify.
 */
final class SpotifyLink {

    /** From the Spotify app made for TV Home at developer.spotify.com. Not a secret. */
    static final String CLIENT_ID = "";
    static final String REDIRECT = "https://kayserviggo-source.github.io/tv-home/spotify.html";
    private static final String SCOPES =
            "user-read-recently-played user-top-read playlist-read-private playlist-read-collaborative user-library-read";
    private static final long LISTEN_MS = 10 * 60 * 1000;
    private static final long FRESH_MS = 10 * 60 * 1000;

    interface Listener {
        void onSpotifyChanged();
    }

    private static SpotifyLink instance;

    static synchronized SpotifyLink get(Context context) {
        if (instance == null) {
            instance = new SpotifyLink(context.getApplicationContext());
        }
        return instance;
    }

    private final Context app;
    private final SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final SecureRandom random = new SecureRandom();
    private volatile Listener listener;

    private volatile String state;          // off, disconnected, waiting, connected
    private volatile String message = "";
    private volatile String authUrl = "";
    private volatile String shortUrl = "";
    private volatile String library = "{}";
    private volatile long fetchedAt;
    private String accessToken;
    private long accessUntil;
    private ServerSocket server;
    private String verifier;
    private String nonce;

    private SpotifyLink(Context app) {
        this.app = app;
        this.prefs = app.getSharedPreferences("spotify", Context.MODE_PRIVATE);
        File saved = libraryFile();
        if (saved.exists()) {
            try {
                library = new String(read(new FileInputStream(saved)), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // Fetched again once connected.
            }
        }
        state = !configured() ? "off" : connected() ? "connected" : "disconnected";
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    private void changed() {
        Listener l = listener;
        if (l != null) {
            l.onSpotifyChanged();
        }
    }

    String clientId() {
        String override = prefs.getString("clientId", "");
        return override.isEmpty() ? CLIENT_ID : override;
    }

    boolean configured() {
        return !clientId().isEmpty();
    }

    boolean connected() {
        return !prefs.getString("refresh", "").isEmpty();
    }

    /** Everything the page needs: the state of the link, the QR address while waiting, and the music. */
    String snapshot() {
        try {
            JSONObject o = new JSONObject();
            o.put("state", state);
            o.put("message", message);
            // The QR code holds a short address on this TV, which forwards the phone to Spotify:
            // Spotify's own sign-in address is too long to scan comfortably from across a room.
            o.put("url", "waiting".equals(state) ? shortUrl : "");
            o.put("configured", configured());
            o.put("connected", connected());
            o.put("library", new JSONObject(library));
            return o.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    /* ---------- Signing in ---------- */

    synchronized void startConnect() {
        if (!configured()) {
            state = "off";
            changed();
            return;
        }
        stopServer();
        String ip = lanAddress();
        if (ip == null) {
            state = connected() ? "connected" : "disconnected";
            message = "The TV is not on the home network.";
            changed();
            return;
        }
        try {
            server = new ServerSocket(0);
            server.setSoTimeout(30 * 1000);
        } catch (IOException e) {
            message = "Could not start listening on the TV.";
            changed();
            return;
        }
        verifier = randomText(64);
        nonce = randomText(16);
        String challenge = base64Url(sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
        String st = base64Url((ip + ":" + server.getLocalPort() + ":" + nonce).getBytes(StandardCharsets.US_ASCII));
        authUrl = "https://accounts.spotify.com/authorize"
                + "?client_id=" + Uri.encode(clientId())
                + "&response_type=code"
                + "&redirect_uri=" + Uri.encode(REDIRECT)
                + "&code_challenge_method=S256"
                + "&code_challenge=" + challenge
                + "&scope=" + Uri.encode(SCOPES)
                + "&state=" + st;
        shortUrl = "http://" + ip + ":" + server.getLocalPort() + "/s";
        state = "waiting";
        message = "";
        final ServerSocket listening = server;
        new Thread(() -> serve(listening), "spotify-sign-in").start();
        changed();
    }

    synchronized void cancelConnect() {
        stopServer();
        if ("waiting".equals(state)) {
            state = connected() ? "connected" : "disconnected";
        }
        changed();
    }

    synchronized void disconnect() {
        stopServer();
        prefs.edit().remove("refresh").apply();
        accessToken = null;
        library = "{}";
        //noinspection ResultOfMethodCallIgnored
        libraryFile().delete();
        state = configured() ? "disconnected" : "off";
        message = "";
        changed();
    }

    private synchronized void stopServer() {
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
                // Closed either way.
            }
            server = null;
        }
    }

    private void serve(ServerSocket listening) {
        long until = System.currentTimeMillis() + LISTEN_MS;
        try {
            while (!listening.isClosed() && System.currentTimeMillis() < until) {
                Socket client;
                try {
                    client = listening.accept();
                } catch (SocketTimeoutException e) {
                    continue;
                }
                if (handle(client)) {
                    break;
                }
            }
        } catch (IOException ignored) {
            // Closed by cancel or by a finished sign-in.
        } finally {
            synchronized (this) {
                if (server == listening) {
                    stopServer();
                    if ("waiting".equals(state)) {
                        state = connected() ? "connected" : "disconnected";
                        message = "The sign-in timed out. Try again.";
                    }
                }
            }
            changed();
        }
    }

    /** One request from the phone. True when the sign-in is over (either way). */
    private boolean handle(Socket client) {
        try (Socket c = client) {
            c.setSoTimeout(8000);
            BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.US_ASCII));
            String line = in.readLine();
            String header;
            do {
                header = in.readLine();
            } while (header != null && !header.isEmpty());
            if (line != null && (line.startsWith("GET /s ") || line.startsWith("GET /s?"))) {
                // The phone scanned the QR code: send it on to Spotify's sign-in.
                OutputStream out = c.getOutputStream();
                out.write(("HTTP/1.1 302 Found\r\nLocation: " + authUrl + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
                return false;
            }
            if (line == null || !line.startsWith("GET /spotify?")) {
                respond(c, 404, "Not here", "This is TV Home on your TV.");
                return false;
            }
            String target = line.split(" ")[1];
            Uri uri = Uri.parse("http://tv" + target);
            String code = uri.getQueryParameter("code");
            String error = uri.getQueryParameter("error");
            String st = uri.getQueryParameter("state");
            if (!validState(st)) {
                respond(c, 400, "Start again from the TV", "This sign-in did not come from TV Home on this TV.");
                return false;
            }
            if (error != null || code == null) {
                message = "Spotify said: " + (error == null ? "no code" : error);
                respond(c, 200, "Not connected", "Spotify did not give access. You can try again from the TV.");
                return true;
            }
            JSONObject token = tokenRequest("grant_type=authorization_code"
                    + "&code=" + Uri.encode(code)
                    + "&redirect_uri=" + Uri.encode(REDIRECT)
                    + "&client_id=" + Uri.encode(clientId())
                    + "&code_verifier=" + Uri.encode(verifier));
            if (token == null || token.optString("refresh_token").isEmpty()) {
                message = "Spotify did not accept the sign-in. Try again.";
                respond(c, 200, "Not connected", "Something went wrong. Try again from the TV.");
                return true;
            }
            synchronized (this) {
                prefs.edit().putString("refresh", token.optString("refresh_token")).apply();
                accessToken = token.optString("access_token");
                accessUntil = System.currentTimeMillis() + token.optLong("expires_in", 3600) * 1000;
                state = "connected";
                message = "";
            }
            respond(c, 200, "Connected", "Spotify is connected to TV Home. You can close this page.");
            refreshLibrary(true);
            return true;
        } catch (IOException | JSONException e) {
            return false;
        }
    }

    private boolean validState(String st) {
        if (st == null || nonce == null) {
            return false;
        }
        try {
            String decoded = new String(Base64.decode(st, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING),
                    StandardCharsets.US_ASCII);
            return decoded.endsWith(":" + nonce);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void respond(Socket c, int status, String title, String text) throws IOException {
        String html = "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width\">"
                + "<title>TV Home</title><body style=\"margin:0;min-height:100vh;display:flex;align-items:center;"
                + "justify-content:center;background:#1A1613;color:#EFE7DA;font:18px/1.5 system-ui,sans-serif;"
                + "text-align:center;padding:24px\"><div><h1 style=\"font-size:28px;margin:0 0 8px\">" + title
                + "</h1><p style=\"margin:0;color:#C4B8A6\">" + text + "</p></div>";
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        OutputStream out = c.getOutputStream();
        out.write(("HTTP/1.1 " + status + " OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
                + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    /* ---------- Tokens ---------- */

    private JSONObject tokenRequest(String form) throws IOException, JSONException {
        HttpURLConnection http = (HttpURLConnection) new URL("https://accounts.spotify.com/api/token").openConnection();
        http.setConnectTimeout(10000);
        http.setReadTimeout(15000);
        http.setRequestMethod("POST");
        http.setDoOutput(true);
        http.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try {
            try (OutputStream out = http.getOutputStream()) {
                out.write(form.getBytes(StandardCharsets.UTF_8));
            }
            int code = http.getResponseCode();
            InputStream in = code < 400 ? http.getInputStream() : http.getErrorStream();
            String text = in == null ? "" : new String(read(in), StandardCharsets.UTF_8);
            JSONObject o = text.isEmpty() ? new JSONObject() : new JSONObject(text);
            if (code >= 400) {
                if ("invalid_grant".equals(o.optString("error"))) {
                    // Access was taken back (or the sign-in expired): start over.
                    prefs.edit().remove("refresh").apply();
                    state = "disconnected";
                    message = "Spotify asked to connect again.";
                }
                return null;
            }
            return o;
        } finally {
            http.disconnect();
        }
    }

    private synchronized String token() throws IOException, JSONException {
        if (accessToken != null && System.currentTimeMillis() < accessUntil - 60 * 1000) {
            return accessToken;
        }
        String refresh = prefs.getString("refresh", "");
        if (refresh.isEmpty()) {
            return null;
        }
        JSONObject o = tokenRequest("grant_type=refresh_token&refresh_token=" + Uri.encode(refresh)
                + "&client_id=" + Uri.encode(clientId()));
        if (o == null) {
            return null;
        }
        accessToken = o.optString("access_token");
        accessUntil = System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000;
        String next = o.optString("refresh_token");
        if (!next.isEmpty()) {
            prefs.edit().putString("refresh", next).apply();
        }
        return accessToken;
    }

    /* ---------- The music ---------- */

    /** Fetches the owner's music again, at most every ten minutes unless forced. */
    void refreshLibrary(boolean force) {
        if (!connected() || (!force && System.currentTimeMillis() - fetchedAt < FRESH_MS)) {
            return;
        }
        fetchedAt = System.currentTimeMillis();
        worker.execute(() -> {
            try {
                fetchLibrary();
            } catch (IOException | JSONException | RuntimeException e) {
                fetchedAt = System.currentTimeMillis() - FRESH_MS + 60 * 1000; // try again in a minute
            }
        });
    }

    private void fetchLibrary() throws IOException, JSONException {
        String t = token();
        if (t == null) {
            changed();
            return;
        }
        JSONObject me = api(t, "/v1/me");
        JSONObject playlistsPage = api(t, "/v1/me/playlists?limit=30");
        JSONObject recent = api(t, "/v1/me/player/recently-played?limit=50");
        JSONObject top = api(t, "/v1/me/top/artists?limit=12&time_range=short_term");

        Map<String, JSONObject> playlistsByUri = new HashMap<>();
        JSONArray playlists = new JSONArray();
        playlists.put(item("Liked Songs", "Your library", "", "spotify:collection:tracks"));
        JSONArray pl = playlistsPage == null ? new JSONArray() : playlistsPage.optJSONArray("items");
        for (int i = 0; pl != null && i < pl.length(); i++) {
            JSONObject p = pl.optJSONObject(i);
            if (p == null) {
                continue;
            }
            JSONObject owner = p.optJSONObject("owner");
            String by = owner == null ? "" : owner.optString("display_name");
            JSONObject entry = item(p.optString("name"), "Playlist" + (by.isEmpty() ? "" : " · " + by),
                    image(p.optJSONArray("images")), p.optString("uri"));
            playlistsByUri.put(p.optString("uri"), entry);
            playlists.put(entry);
        }

        // Jump back in: the albums and playlists played most recently, one entry each.
        JSONArray jump = new JSONArray();
        Set<String> seen = new HashSet<>();
        JSONArray items = recent == null ? null : recent.optJSONArray("items");
        for (int i = 0; items != null && i < items.length() && jump.length() < 12; i++) {
            JSONObject it = items.optJSONObject(i);
            JSONObject track = it == null ? null : it.optJSONObject("track");
            if (track == null) {
                continue;
            }
            JSONObject album = track.optJSONObject("album");
            JSONObject context = it.optJSONObject("context");
            String contextUri = context == null ? "" : context.optString("uri");
            String type = context == null ? "" : context.optString("type");
            if ("playlist".equals(type) && !contextUri.isEmpty()) {
                if (!seen.add(contextUri)) {
                    continue;
                }
                JSONObject known = playlistsByUri.get(contextUri);
                if (known == null) {
                    known = playlist(t, contextUri);
                }
                if (known != null) {
                    jump.put(known);
                    continue;
                }
            }
            if (album == null) {
                continue;
            }
            String albumUri = album.optString("uri");
            if (!seen.add(albumUri)) {
                continue;
            }
            jump.put(item(album.optString("name"), artists(track.optJSONArray("artists")),
                    image(album.optJSONArray("images")), albumUri));
        }

        JSONArray artists = new JSONArray();
        JSONArray ta = top == null ? null : top.optJSONArray("items");
        for (int i = 0; ta != null && i < ta.length(); i++) {
            JSONObject a = ta.optJSONObject(i);
            if (a != null) {
                artists.put(item(a.optString("name"), "Artist", image(a.optJSONArray("images")), a.optString("uri")));
            }
        }

        JSONObject out = new JSONObject();
        String name = me == null ? "" : me.optString("display_name").trim();
        out.put("name", name.contains(" ") ? name.substring(0, name.indexOf(' ')) : name);
        out.put("jump", jump);
        out.put("playlists", playlists);
        out.put("artists", artists);
        out.put("at", System.currentTimeMillis());
        library = out.toString();
        try (FileOutputStream f = new FileOutputStream(libraryFile())) {
            f.write(library.getBytes(StandardCharsets.UTF_8));
        }
        changed();
    }

    /** A playlist the owner played but does not keep in their library. Spotify's own mixes are not shared. */
    private JSONObject playlist(String token, String uri) {
        String id = uri.substring(uri.lastIndexOf(':') + 1);
        try {
            JSONObject p = api(token, "/v1/playlists/" + Uri.encode(id) + "?fields=name,uri,images,owner(display_name)");
            if (p == null) {
                return null;
            }
            JSONObject owner = p.optJSONObject("owner");
            String by = owner == null ? "" : owner.optString("display_name");
            return item(p.optString("name"), "Playlist" + (by.isEmpty() ? "" : " · " + by), image(p.optJSONArray("images")), uri);
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    private JSONObject api(String token, String path) throws IOException, JSONException {
        HttpURLConnection http = (HttpURLConnection) new URL("https://api.spotify.com" + path).openConnection();
        http.setConnectTimeout(10000);
        http.setReadTimeout(15000);
        http.setRequestProperty("Authorization", "Bearer " + token);
        try {
            int code = http.getResponseCode();
            if (code == 401) {
                synchronized (this) {
                    accessToken = null;
                }
                return null;
            }
            if (code != 200) {
                return null;
            }
            return new JSONObject(new String(read(http.getInputStream()), StandardCharsets.UTF_8));
        } finally {
            http.disconnect();
        }
    }

    private static JSONObject item(String title, String sub, String image, String link) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("title", title);
        o.put("subtitle", sub);
        o.put("image", image);
        o.put("link", link);
        return o;
    }

    /** The picture closest to 300 pixels wide. */
    private static String image(JSONArray images) {
        String best = "";
        int bestScore = Integer.MAX_VALUE;
        for (int i = 0; images != null && i < images.length(); i++) {
            JSONObject img = images.optJSONObject(i);
            if (img == null) {
                continue;
            }
            int w = img.optInt("width", 300);
            int score = w >= 280 ? w - 280 : 1000 + (280 - w);
            if (score < bestScore) {
                bestScore = score;
                best = img.optString("url");
            }
        }
        return best;
    }

    private static String artists(JSONArray list) {
        StringBuilder names = new StringBuilder();
        for (int i = 0; list != null && i < list.length() && i < 2; i++) {
            JSONObject a = list.optJSONObject(i);
            if (a == null) {
                continue;
            }
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(a.optString("name"));
        }
        return names.toString();
    }

    /* ---------- Small helpers ---------- */

    private File libraryFile() {
        return new File(app.getFilesDir(), "spotify-library.json");
    }

    /** The TV's address on the home network, for the phone to come back to. */
    private static String lanAddress() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(nif.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // No network.
        }
        return null;
    }

    private String randomText(int length) {
        String letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder s = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            s.append(letters.charAt(random.nextInt(letters.length())));
        }
        return s.toString();
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String base64Url(byte[] data) {
        return Base64.encodeToString(data, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static byte[] read(InputStream in) throws IOException {
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }
}
