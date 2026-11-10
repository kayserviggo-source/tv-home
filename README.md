# TV Home

A home screen for Android TV, built for a Philips 43PUS8507. It replaces the stock home
screen's ads and recommendation rows with a calm layout centred on what is playing.

## What it does

- **Home.** A shelf above a dock of apps, laid out like Apple TV. The shelf shows the music
  (record, sleeve and controls); move to Netflix, YouTube or SVT Play in the dock and it shows
  what that app has lined up instead, and the screen takes its colours from the poster in
  focus. Up goes into the shelf, Down comes back to the app. With nothing playing, the shelf
  is a greeting with the date.
- **Now playing.** Shows the current track from Spotify (or any app that plays media): cover,
  title, artist, progress, and play/pause/skip. It reads this from Android's media sessions,
  so it needs no Spotify login.
- **Sonos.** Music playing on a Sonos speaker (Spotify sent from a phone, say) shows up
  the same way, with play/pause/skip, even though it never passes through the TV. The app
  finds the speakers on the home network by itself; no login. The TV's own sound on a Sonos
  soundbar is ignored.
- **Player.** A full-screen view of the same thing, with ±10 second seek. It can open by
  itself when music is started from a phone.
- **Clock screen.** After a couple of minutes without a button press while music plays, a
  large clock with the current track.
- **Continue watching.** What Netflix, SVT Play, YouTube and other apps publish for TV home
  screens, on each app's shelf. Each app decides what it shares, so this can be fuller or
  emptier per app. Spotify's own suggestion rows are left out: its shelf is the music.
- **Apps.** The dock holds the apps you put on Home, then All apps and Control Centre. Hold OK
  on an app in the dock to move it (left and right, Down takes it off Home, OK puts it down).
  On All apps, hold OK on an app to add it to Home or take it off.
- **Look.** Inter throughout. The accent colour follows the cover that is playing, or is one
  of five fixed colours; the player and clock screen take their background from the cover.
- **Updates.** The app checks this repository's Releases every few hours, downloads a newer
  build by itself, and offers to install it (Android asks for confirmation on the TV).
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
- That service runs in a small process of its own, so a crash in the main app, or Android
  closing it for memory, does not take the Home button with it. Android still switches the
  service off if the app is force-stopped. With the optional permission below, TV Home switches
  it back on by itself (when it opens, after the TV starts and after an update); without it,
  Control Centre → Setup says when the Home button has stopped.

## Building

Every push to `main` builds the APK with GitHub Actions and attaches it to a new entry on the
repository's Releases page as `tv-home.apk`, tagged `build-N`. N is the workflow's run
number and also the app's version code, which is how the app on the TV recognises a newer
build. The first line of the commit message is shown on the TV as what is new.

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

# Let it install its own updates from GitHub
adb shell appops set se.tvhome.launcher REQUEST_INSTALL_PACKAGES allow

# Optional: let it switch the Home button back on by itself if Android turns it off
adb shell pm grant se.tvhome.launcher android.permission.WRITE_SECURE_SETTINGS

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
  screen answers the Home button again. TV Home takes this as your choice and does not switch
  it back on.
- Remove it: `adb uninstall se.tvhome.launcher`.

## Fonts

Inter, under the SIL Open Font License 1.1.
