package se.tvhome.launcher;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Follows music playing on Sonos speakers on the home network, such as Spotify sent from a
 * phone, which never passes through the TV. Talks to the speakers directly over the local
 * network (the same way the Sonos app does), so it needs no account or login.
 *
 * Only runs while the home screen is on screen.
 */
final class SonosWatcher {

    private static final String SPOTIFY_TV = "com.spotify.tv.android";

    private static SonosWatcher instance;

    static synchronized SonosWatcher get(Context context) {
        if (instance == null) {
            instance = new SonosWatcher(context.getApplicationContext());
        }
        return instance;
    }

    private static final class Player {
        final String ip;
        String room = "";
        String state = "";
        String uri = "";
        long checkedAt;
        int failures;

        Player(String ip) {
            this.ip = ip;
        }

        /** Music, not a speaker that merely follows another one or plays the TV's own sound. */
        boolean playsMusic() {
            return !uri.isEmpty()
                    && !uri.startsWith("x-rincon:")              // grouped with another speaker
                    && !uri.startsWith("x-sonos-htastream:")     // the TV's sound
                    && !uri.startsWith("x-rincon-stream:");      // line-in
        }

        boolean playing() {
            return "PLAYING".equals(state) || "TRANSITIONING".equals(state);
        }
    }

    private final Context app;
    private final Map<String, Player> players = new LinkedHashMap<>();
    private volatile boolean running;
    private Thread thread;
    private long lastDiscovery;
    private volatile boolean pollNow;

    // What the speaker on screen is playing.
    private Player current;
    private String title = "";
    private String artist = "";
    private String album = "";
    private String service = "";
    private boolean lossless;
    private String format = "";
    private long duration;
    private long position;
    private long positionAt;
    private long lastPlayingAt;
    private String artRemote = "";
    private byte[] artBytes;
    private String artUrl = "";

    private SonosWatcher(Context app) {
        this.app = app;
    }

    /* ---------- Running ---------- */

    synchronized void resume() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "sonos");
        thread.setDaemon(true);
        thread.start();
    }

    synchronized void pause() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    private void loop() {
        loadKnown();
        while (running) {
            try {
                poll();
                long sinceDiscovery = SystemClock.elapsedRealtime() - lastDiscovery;
                boolean none;
                synchronized (this) {
                    none = players.isEmpty();
                }
                if (lastDiscovery == 0 || sinceDiscovery > (none ? 60_000 : 10 * 60_000)) {
                    discover();
                }
                for (int i = 0; i < 15 && running && !pollNow; i++) {
                    Thread.sleep(100);
                }
                pollNow = false;
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException ignored) {
                // A speaker answered with something unexpected; try again next round.
            }
        }
    }

    /* ---------- Finding speakers ---------- */

    private void loadKnown() {
        String saved = App.prefs(app).getString("sonosIps", "");
        for (String ip : saved.split(",")) {
            if (!ip.isEmpty()) {
                synchronized (this) {
                    players.put(ip, new Player(ip));
                }
            }
        }
    }

    private void saveKnown() {
        StringBuilder b = new StringBuilder();
        synchronized (this) {
            for (String ip : players.keySet()) {
                if (b.length() > 0) {
                    b.append(',');
                }
                b.append(ip);
            }
        }
        App.prefs(app).edit().putString("sonosIps", b.toString()).apply();
    }

    /** Asks the network who is a Sonos speaker (SSDP), the same way the Sonos app finds them. */
    private void discover() {
        lastDiscovery = SystemClock.elapsedRealtime();
        WifiManager.MulticastLock lock = null;
        try {
            WifiManager wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                lock = wifi.createMulticastLock("tvhome-sonos");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
        } catch (RuntimeException ignored) {
            // No Wi-Fi (the TV may be on a cable); searching works without the lock then.
        }
        List<String> found = new ArrayList<>();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(600);
            byte[] search = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n"
                    + "MX: 1\r\nST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            socket.send(new DatagramPacket(search, search.length, group, 1900));
            socket.send(new DatagramPacket(search, search.length, group, 1900));
            byte[] buffer = new byte[2048];
            long end = SystemClock.elapsedRealtime() + 2500;
            while (running && SystemClock.elapsedRealtime() < end) {
                DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(reply);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                String text = new String(reply.getData(), 0, reply.getLength(), StandardCharsets.ISO_8859_1);
                if (text.contains("ZonePlayer") || text.toLowerCase(Locale.ROOT).contains("sonos")) {
                    String ip = reply.getAddress().getHostAddress();
                    if (!found.contains(ip)) {
                        found.add(ip);
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // The network refused the search; known speakers are still polled.
        } finally {
            if (lock != null) {
                try {
                    lock.release();
                } catch (RuntimeException ignored) {
                    // Already released.
                }
            }
        }
        boolean added = false;
        for (String ip : found) {
            synchronized (this) {
                if (players.containsKey(ip)) {
                    continue;
                }
                players.put(ip, new Player(ip));
            }
            added = true;
        }
        if (added) {
            saveKnown();
        }
    }

    /* ---------- Following playback ---------- */

    private void poll() {
        List<Player> list;
        synchronized (this) {
            list = new ArrayList<>(players.values());
        }
        long now = SystemClock.elapsedRealtime();
        boolean removed = false;
        for (Player p : list) {
            boolean due = p == current || pollNow || now - p.checkedAt > 6000;
            if (!due) {
                continue;
            }
            p.checkedAt = now;
            String info = soap(p.ip, "GetTransportInfo", "");
            if (info == null) {
                if (++p.failures > 8) {
                    synchronized (this) {
                        players.remove(p.ip);
                        if (current == p) {
                            clearCurrent();
                        }
                    }
                    removed = true;
                }
                continue;
            }
            p.failures = 0;
            p.state = tag(info, "CurrentTransportState");
            if (p.room.isEmpty()) {
                p.room = roomName(p.ip);
            }
            if (p == current || p.playing()) {
                String position = soap(p.ip, "GetPositionInfo", "");
                if (position != null) {
                    p.uri = unescape(tag(position, "TrackURI"));
                    if (p == current || choose(p)) {
                        read(p, position);
                    }
                }
            }
        }
        if (removed) {
            saveKnown();
        }
    }

    /** Whether {@code p} should become the speaker on screen. */
    private synchronized boolean choose(Player p) {
        if (!p.playing() || !p.playsMusic()) {
            return false;
        }
        if (current != null && current != p && current.playing() && current.playsMusic()) {
            return false; // Keep following the one already on screen.
        }
        if (current != p) {
            current = p;
            artRemote = "";
        }
        return true;
    }

    private void read(Player p, String positionInfo) {
        String meta = unescape(tag(positionInfo, "TrackMetaData"));
        String newTitle = unescape(tag(meta, "dc:title"));
        String newArtist = unescape(tag(meta, "dc:creator"));
        String newAlbum = unescape(tag(meta, "upnp:album"));
        String stream = unescape(tag(meta, "r:streamContent"));
        if (!stream.isEmpty()) {
            // Radio: the station is the title, and the song arrives as "Artist - Title".
            newAlbum = newTitle;
            int dash = stream.indexOf(" - ");
            if (dash > 0) {
                newArtist = stream.substring(0, dash);
                newTitle = stream.substring(dash + 3);
            } else {
                newTitle = stream;
            }
        }
        String art = unescape(tag(meta, "upnp:albumArtURI"));
        // Sonos does not document the stream's quality. The format, when the speaker names one,
        // sits in the res element's protocolInfo or in the track address; FLAC or ALAC there
        // means lossless. When neither says, no badge is shown rather than a guess.
        String res = "";
        int at = meta.indexOf("protocolInfo=\"");
        if (at >= 0) {
            int end = meta.indexOf('"', at + 14);
            res = end > at ? meta.substring(at + 14, end) : "";
        }
        String hints = (res + " " + p.uri).toLowerCase(Locale.ROOT);
        boolean newLossless = hints.contains("flac") || hints.contains("alac") || hints.contains("lossless");
        long newDuration = clockToMs(tag(positionInfo, "TrackDuration"));
        long newPosition = clockToMs(tag(positionInfo, "RelTime"));

        synchronized (this) {
            if (current != p) {
                return;
            }
            boolean playing = p.playing() && p.playsMusic();
            title = newTitle;
            artist = newArtist;
            album = newAlbum;
            service = serviceName(p.uri, stream);
            lossless = newLossless;
            format = res;
            duration = newDuration;
            position = newPosition;
            positionAt = SystemClock.elapsedRealtime();
            if (playing) {
                lastPlayingAt = System.currentTimeMillis();
            }
        }
        if (!art.equals(artRemote)) {
            fetchArt(p.ip, art);
        }
        if (!p.playsMusic() || newTitle.isEmpty()) {
            synchronized (this) {
                if (current == p && !p.playing()) {
                    clearCurrent();
                }
            }
        }
    }

    private void fetchArt(String ip, String uri) {
        byte[] data = null;
        if (!uri.isEmpty()) {
            String address = uri.startsWith("http") ? uri
                    : "http://" + ip + ":1400" + (uri.startsWith("/") ? "" : "/") + uri;
            data = httpGet(address, 4 * 1024 * 1024);
        }
        synchronized (this) {
            artRemote = uri;
            // Keep the previous cover if this one could not be fetched, rather than flashing blank.
            if (data != null) {
                artBytes = data;
                artUrl = App.ORIGIN + "/art/sonos?k=" + Integer.toHexString(Arrays.hashCode(data));
            } else if (uri.isEmpty()) {
                artBytes = null;
                artUrl = "";
            }
        }
    }

    private void clearCurrent() {
        current = null;
        title = "";
        artist = "";
        album = "";
        lossless = false;
        format = "";
        artRemote = "";
        artBytes = null;
        artUrl = "";
    }

    private static String serviceName(String uri, String stream) {
        String u = uri.toLowerCase(Locale.ROOT);
        if (u.contains("spotify")) {
            return "Spotify";
        }
        if (u.contains("sid=204")) {
            return "Apple Music";
        }
        if (u.contains("sid=303") || u.contains("tidal")) {
            return "Tidal";
        }
        if (!stream.isEmpty() || u.startsWith("x-sonosapi-stream:") || u.startsWith("x-rincon-mp3radio:")
                || u.startsWith("aac:") || u.startsWith("x-sonosapi-radio:")) {
            return "Radio";
        }
        return "Sonos";
    }

    /* ---------- For the interface ---------- */

    /** What is playing on Sonos, or null when nothing is. */
    synchronized JSONObject snapshot() {
        Player p = current;
        if (p == null || title.isEmpty() || !p.playsMusic()
                || !("PLAYING".equals(p.state) || "TRANSITIONING".equals(p.state) || "PAUSED_PLAYBACK".equals(p.state))) {
            return null;
        }
        boolean playing = p.playing();
        long pos = position;
        if (playing) {
            pos += SystemClock.elapsedRealtime() - positionAt;
        }
        if (duration > 0 && pos > duration) {
            pos = duration;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("access", true);
            o.put("active", true);
            o.put("source", "sonos");
            o.put("room", p.room);
            o.put("key", title + "\n" + artist);
            o.put("pkg", "Spotify".equals(service) ? SPOTIFY_TV : "");
            o.put("app", service);
            o.put("title", title);
            o.put("artist", artist);
            o.put("album", album);
            o.put("duration", duration);
            o.put("position", Math.max(0, pos));
            o.put("playing", playing);
            o.put("art", artUrl);
            o.put("canNext", true);
            o.put("canPrev", true);
            o.put("canSeek", duration > 0);
            o.put("lossless", lossless);
            o.put("format", format);
            return o;
        } catch (JSONException e) {
            return null;
        }
    }

    synchronized long lastPlayingAt() {
        return lastPlayingAt;
    }

    synchronized byte[] currentArt() {
        return artBytes;
    }

    /** Play, pause, skip or seek on the speaker on screen. Runs off the main thread. */
    void command(final String cmd, final long arg) {
        final Player p;
        synchronized (this) {
            p = current;
            if (p == null) {
                return;
            }
            // Show the result straight away; the next poll confirms it.
            if ("toggle".equals(cmd) || "play".equals(cmd) || "pause".equals(cmd)) {
                boolean play = "play".equals(cmd) || ("toggle".equals(cmd) && !p.playing());
                if (!play) {
                    position += p.playing() ? SystemClock.elapsedRealtime() - positionAt : 0;
                }
                positionAt = SystemClock.elapsedRealtime();
                p.state = play ? "PLAYING" : "PAUSED_PLAYBACK";
            } else if ("seek".equals(cmd)) {
                position = Math.max(0, arg);
                positionAt = SystemClock.elapsedRealtime();
            }
        }
        // For a toggle, p.state above already holds the state being asked for.
        final boolean nowPlay = "PLAYING".equals(p.state);
        new Thread(() -> {
            switch (cmd) {
                case "toggle":
                    soap(p.ip, nowPlay ? "Play" : "Pause", nowPlay ? "<Speed>1</Speed>" : "");
                    break;
                case "play":
                    soap(p.ip, "Play", "<Speed>1</Speed>");
                    break;
                case "pause":
                    soap(p.ip, "Pause", "");
                    break;
                case "next":
                    soap(p.ip, "Next", "");
                    break;
                case "prev":
                    soap(p.ip, "Previous", "");
                    break;
                case "seek":
                    soap(p.ip, "Seek", "<Unit>REL_TIME</Unit><Target>" + msToClock(arg) + "</Target>");
                    break;
                default:
                    break;
            }
            pollNow = true;
        }, "sonos-command").start();
    }

    /** For the sleep timer. */
    void pauseIfPlaying() {
        Player p;
        synchronized (this) {
            p = current;
        }
        if (p != null && p.playing()) {
            command("pause", 0);
        }
    }

    /* ---------- Talking to a speaker ---------- */

    private static String soap(String ip, String action, String args) {
        String urn = "urn:schemas-upnp-org:service:AVTransport:1";
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:" + action + " xmlns:u=\"" + urn + "\"><InstanceID>0</InstanceID>" + args + "</u:" + action + ">"
                + "</s:Body></s:Envelope>";
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://" + ip + ":1400/MediaRenderer/AVTransport/Control").openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(2500);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            c.setRequestProperty("SOAPACTION", "\"" + urn + "#" + action + "\"");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = c.getOutputStream()) {
                out.write(bytes);
            }
            if (c.getResponseCode() != 200) {
                return null;
            }
            try (InputStream in = c.getInputStream()) {
                return new String(readAll(in, 256 * 1024), StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private static String roomName(String ip) {
        byte[] data = httpGet("http://" + ip + ":1400/xml/device_description.xml", 256 * 1024);
        return data == null ? "" : unescape(tag(new String(data, StandardCharsets.UTF_8), "roomName"));
    }

    private static byte[] httpGet(String address, int limit) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(address).openConnection();
            c.setConnectTimeout(2000);
            c.setReadTimeout(4000);
            if (c.getResponseCode() != 200) {
                return null;
            }
            try (InputStream in = c.getInputStream()) {
                return readAll(in, limit);
            }
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private static byte[] readAll(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
            if (out.size() > limit) {
                throw new IOException("too large");
            }
        }
        return out.toByteArray();
    }

    /** The text inside the first {@code <name>...</name>}, whatever attributes the tag has. */
    private static String tag(String xml, String name) {
        if (xml == null || xml.isEmpty()) {
            return "";
        }
        Matcher m = Pattern.compile("<" + Pattern.quote(name) + "(?:\\s[^>]*)?>(.*?)</" + Pattern.quote(name) + ">",
                Pattern.DOTALL).matcher(xml);
        return m.find() ? m.group(1).trim() : "";
    }

    private static String unescape(String s) {
        if (s == null || s.indexOf('&') < 0) {
            return s == null ? "" : s;
        }
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&#39;", "'").replace("&amp;", "&");
    }

    private static long clockToMs(String clock) {
        if (clock == null || clock.isEmpty() || clock.startsWith("NOT")) {
            return 0;
        }
        String[] parts = clock.split(":");
        long total = 0;
        try {
            for (String part : parts) {
                total = total * 60 + (long) Double.parseDouble(part);
            }
        } catch (NumberFormatException e) {
            return 0;
        }
        return total * 1000;
    }

    private static String msToClock(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }
}
