package se.tvhome.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;

/** The home screen: one full-screen page that draws the interface, plus the bridge to the TV. */
public class MainActivity extends Activity {

    static final String EXTRA_VIEW = "view";
    static final int REQUEST_TV_LISTINGS = 7;

    private WebView web;
    private Bridge bridge;
    private MediaWatcher media;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        media = MediaWatcher.get(this);
        bridge = new Bridge(this, media);
        bridge.setPendingView(getIntent() == null ? null : getIntent().getStringExtra(EXTRA_VIEW));

        web = new WebView(this);
        web.setBackgroundColor(0xFF1A1613);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        setContentView(web);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // The page is laid out 1920 wide and scaled to the screen, whatever the TV reports.
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        // Only this app's own page is ever loaded here, so only it can reach the bridge.
        web.addJavascriptInterface(bridge, "Native");
        web.setWebViewClient(new AssetClient(this));
        web.loadUrl(App.ORIGIN + "/index.html");
        web.requestFocus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String view = intent == null ? null : intent.getStringExtra(EXTRA_VIEW);
        emit("show", view == null ? "home" : view);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Foreground.homeVisible = true;
        Foreground.spotifyOpenedFromTile = false;
        media.setListener(() -> emit("media", ""));
        media.start();
        web.onResume();
        web.requestFocus();
        emit("resume", "");
    }

    @Override
    protected void onPause() {
        Foreground.homeVisible = false;
        emit("pause", "");
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        media.setListener(null);
        web.destroy();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_TV_LISTINGS) {
            emit("permissions", "");
        }
    }

    @Override
    public void onBackPressed() {
        // Back never leaves the home screen; the interface decides what it closes.
        emit("back", "");
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        // The remote's OK button reaches the page as a plain Enter key, whatever the TV calls it.
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
                || code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_BUTTON_SELECT) {
            return super.dispatchKeyEvent(new KeyEvent(event.getDownTime(), event.getEventTime(),
                    event.getAction(), KeyEvent.KEYCODE_ENTER, event.getRepeatCount(), event.getMetaState(),
                    event.getDeviceId(), event.getScanCode(), event.getFlags(), event.getSource()));
        }
        if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SETTINGS) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                emit("menu", "");
            }
            return true;
        }
        String command = null;
        switch (code) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                command = "toggle";
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                command = "play";
                break;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                command = "pause";
                break;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                command = "pause";
                break;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                command = "next";
                break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                command = "prev";
                break;
            default:
                break;
        }
        if (command != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                media.command(command, 0);
                emit("input", "");
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /** Tells the interface that something happened. */
    void emit(final String name, final String detail) {
        if (web == null) {
            return;
        }
        final String script = "window.TVHome&&window.TVHome.emit(" + quote(name) + "," + quote(detail) + ")";
        web.post(() -> web.evaluateJavascript(script, null));
    }

    private static String quote(String value) {
        return org.json.JSONObject.quote(value == null ? "" : value);
    }
}
