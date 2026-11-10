# TV Home

A home screen for Android TV, built for a Philips 43PUS8507. It replaces the stock home
screen's ads and recommendation rows with a calm layout centred on what is playing.

## What it does

- **Now playing.** Shows the current track from Spotify (or any app that plays media): cover,
  title, artist, progress, and play/pause/skip. It reads this from Android's media sessions,
  so it needs no Spotify login.
- **Player.** A full-screen view of the same thing, with ±10 second seek. It can open by
  itself when music is started from a phone.
- **Clock screen.** After a couple of minutes without a button press while music plays, a
  large clock with the current track.
- **Continue watching.** What Netflix, SVT Play, YouTube and other apps publish for TV home
  screens. Each app decides what it shares, so this can be fuller or emptier per app.
- **Apps.** Four pinned apps on Home and a row with the rest; hold OK on any app to pin or
  unpin it.
- **Look.** Three typefaces and four accent colours to choose from, and the player and clock
  screen take their background colour from the current cover.
- **Weather.** Temperature and conditions beside the clock, from open-meteo.com. The place is
  set in `PLACE` at the top of `app.js`.
- **Control Centre.** Sleep timer, HDMI inputs, look, TV settings, and setup status. The Menu
  button on the remote opens it from anywhere in TV Home.

Not in this version: Spotify playlists, liking tracks, the queue, and moving playback between
devices (these need a Spotify developer login), and Ambilight control.

## How it is put together

- `app/src/main/assets/ui/` is the interface: plain HTML, CSS and JavaScript, laid out at
  1920×1080. Open `index.html?mock=1` in a desktop browser to try it with sample content and
  the arrow keys.
- `app/src/main/java/` is a small Android shell that shows that page full screen and gives it
  access to the TV: installed apps, media sessions, Continue watching, inputs.
- The stock home screen is left installed. TV Home takes over through an accessibility
  service that answers the Home button and steps in when the stock home screen appears. If
  TV Home crashes, it leaves the Home button alone for two minutes so the stock home screen
  stays reachable.

## Building

Every push to `main` builds the APK with GitHub Actions and attaches it to a new entry on the
repository's Releases page as `tv-home.apk`. Nothing needs to be installed locally.

The build is signed with the key in `app/tvhome.jks`. It is kept in the repository on purpose,
so each build installs over the last one. This is a personal, sideloaded app; do not reuse the
key for anything published.

## Installing on the TV

One-time preparation on the TV: Settings → Device Preferences → About → press **Build** seven
times, then turn on **USB debugging** (or network debugging) under Developer options. Note the
TV's IP address under Network.

From a computer on the same network, with `adb` (Android platform-tools) installed:

```sh
adb connect TV_IP_ADDRESS          # accept the prompt that appears on the TV
adb install -r tv-home.apk

# Let it see what is playing in other apps
adb shell cmd notification allow_listener se.tvhome.launcher/se.tvhome.launcher.NowPlayingListener

# Let it read what apps publish for Continue watching
adb shell pm grant se.tvhome.launcher android.permission.READ_TV_LISTINGS

# Make it the home screen (answers the Home button, replaces the stock home screen)
adb shell settings get secure enabled_accessibility_services
```

If that last command prints `null` or nothing:

```sh
adb shell settings put secure enabled_accessibility_services se.tvhome.launcher/se.tvhome.launcher.HomeKeyService
adb shell settings put secure accessibility_enabled 1
```

If it prints other services, keep them and add TV Home after a colon:

```sh
adb shell settings put secure enabled_accessibility_services "WHAT_IT_PRINTED:se.tvhome.launcher/se.tvhome.launcher.HomeKeyService"
adb shell settings put secure accessibility_enabled 1
```

Press Home on the remote. Control Centre → Setup shows which of the three permissions are on.

## Going back

- Turn it off: TV Settings → Accessibility → TV Home: Home button → off. The stock home
  screen answers the Home button again.
- Remove it: `adb uninstall se.tvhome.launcher`.

## Fonts

Inter, Manrope and Instrument Serif, each under the SIL Open Font License 1.1.
