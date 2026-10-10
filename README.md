# HLS Player app

The [HLS Player browser extension](../hls-player) as a Kotlin Multiplatform app: a library of HLS
and DASH movies, Stremio addons as sources, a per-movie `Referer`, server-side ad stripping,
intro and outro skipping, resume, subtitles and a stream picker. Android, iOS and the desktop
(macOS, Windows, Linux) all play; iOS and the desktop through a local proxy.

It reads and writes the extension's backup file (`hls-player-backup` v1), so a library moves
between the browser and the phone in either direction.

## What is where

```
shared/       all logic, no UI — a port of the extension's helpers, with their tests
composeApp/   Compose Multiplatform UI; androidMain holds the Media3 engine and the intents
iosApp/       Xcode shell hosting the same UI; its player is a placeholder
```

The extension's modules map one to one, under the same names where it reads naturally:

| Extension | App (`shared/src/commonMain`) |
| --- | --- |
| `helper/library.js`, `progress.js`, `settings.js`, `plugins.js` | `library/`, `store/`, `settings/` |
| `helper/stremio.js` | `stremio/` (Ktor) |
| `helper/backup.js` | `backup/` |
| `helper/preload.js`, `utils/playlist.js`, `utils/segments.js` | `playback/` |
| `utils/subtitles.js` | `subtitles/` (plus a WebVTT cue parser) |
| `utils/url.js`, `utils/time.js` | `util/` |
| the rules inside `player/Player.jsx` | `playback/PlayerController.kt` |

`PlayerController` holds every playback rule — resolving an addon's streams, resume, progress,
skip windows, the outro countdown, preloading the next episode, subtitle choice — and drives a
`VideoEngine` that only plays. Media3 implements it on Android; a fake implements it in tests.

## What is different on a phone

- **`Referer` is an ordinary header.** The extension needs declarativeNetRequest or blocking
  webRequest because a page cannot set it; the app sets it on Media3's HTTP source and on
  subtitle requests. Host permissions, the MV3 CSP and the `blob:` worker workaround have no
  counterpart.
- **Subtitles are drawn by the app**, from the fetched files, so two tracks can stack and a timing
  offset applies the same way on every engine.
- **Ad stripping** runs inside a wrapped Media3 HLS playlist parser — the counterpart of the VHS
  response hook. Patterns are written for the extension's JavaScript engine; Kotlin's (Java's on
  Android) accepts the common syntax, but not every corner of it.
- **Links**: `.m3u8` and `.mpd` links handed to the app open in the player, the extension's
  "take over manifest navigations".

## Android TV

One APK serves phones and TVs: neither a touchscreen nor the TV interface is required, and the TV
home screen lists the app with its banner (`res/drawable-xhdpi/banner.png`). Everything works with
a remote:

- **Focus** shows as a red ring on every control (light on the solid red button), and a focused
  poster dims with a play mark. A confirmation opens on its affirmative answer.
- **Player**: with the video focused, center plays and pauses, left and right seek 10 s, down and up
  move into the control bar and the top bar, and Back returns from either to the video before it
  leaves. Media keys work anywhere. Any key wakes the controls.
- **Text fields** take focus without opening the keyboard — center opens it — so a remote can walk
  down a form.
- **Backup**: many TVs ship without a file picker, so Export and Import are disabled there with a
  note, and **Import from a URL** reads an export from anywhere the TV can reach.

Tested on the `Television_720p` emulator (Android TV, API 36).

## iOS

AVPlayer has no hook for headers, playlists or segment bytes, so it plays through a local proxy
(`shared/…/proxy`): `ProxyServer` listens on `127.0.0.1` on a port the system picks, the player is
handed a loopback URL with the upstream path mirrored under `/s/<token>/`, and `ProxyHandler` sends
the `Referer`, cuts the ads from playlists and points their URIs back at itself, takes PNG wrappers
off segments, and passes ranges and upstream refusals through. Each playback opens a session with
its own token, so the port is no open relay for other apps. `IosEngine` is the AVPlayer side.

The Compose view controller is the window's root (`iosApp/iosApp/iOSApp.swift`), with no SwiftUI
around it. iOS has no back button, so back is a swipe in from the left edge: a pan limited to a
24 pt strip, handed to `goBack()`, which asks the newest enabled `BackHandler` — the sheet before
the panel before the screen, as Android's dispatcher decides. It is a plain pan rather than
`UIScreenEdgePanGestureRecognizer` because the edge recognizer relies on bezel information only
real touches carry, so no UI test can exercise it; Sources and Settings also show a back button.
A simulator that ran the earlier SwiftUI build keeps its old scene session, which shows a black
screen — uninstall the app once.

`hlsplayer://play?url=<manifest>` opens a link in the player outside the library, as Android's
`VIEW` intent does. Debug builds also take `SIMCTL_CHILD_HLS_OPEN_URL=<manifest>` at launch, which
drives a simulator run without tapping iOS's "Open in…?" prompt:

```shell
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,name=iPhone 17' -derivedDataPath build/ios CODE_SIGNING_ALLOWED=NO build
xcrun simctl install "iPhone 17" build/ios/Build/Products/Debug-iphonesimulator/HLSPlayer.app
SIMCTL_CHILD_HLS_OPEN_URL=https://example.com/one.m3u8 xcrun simctl launch "iPhone 17" com.paulcoding.hlsplayer.HLSPlayer
```

`Info.plist` allows local networking (the proxy) and arbitrary loads (plain-http stream hosts) —
Android's `usesCleartextTraffic`.

## Desktop

A JVM target of `composeApp` (`desktopMain`). Playback is libVLC through
[vlcj](https://github.com/caprica/vlcj), so **VLC 3 must be installed** — vlcj finds it in the usual
places (`/Applications/VLC.app` on macOS); without it the player says so. VLC is handed the same
loopback URL as AVPlayer, so the proxy does the `Referer`, ad cutting and segment unwrapping.
`VlcEngine` takes decoded frames as pixels and Compose draws them, because a native video view
cannot sit under Compose's own controls.

Escape is back (`BackStack`, shared with iOS's swipe), and leaves fullscreen first. A manifest URL
or `hlsplayer://play?url=…` link passed as the first argument, or opened through the OS, plays
outside the library. Data lives in `~/Library/Application Support/HLS Player` on macOS,
`%APPDATA%\HLS Player` on Windows and `~/.local/share/hls-player` on Linux; `HLS_DATA_DIR`
points a test run elsewhere.

```shell
./gradlew :composeApp:run
./gradlew :composeApp:packageDmg    # or packageMsi / packageDeb, on that OS
```

## Build and test

```shell
./gradlew :shared:jvmTest                          # the shared suite, fast
./gradlew :shared:iosSimulatorArm64Test            # the same suite on the iOS simulator
./gradlew :composeApp:assembleDebug                # -> composeApp/build/outputs/apk/debug
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
./gradlew :composeApp:run                          # the desktop app
```

JDK 17 and the Android SDK (API 36) are needed; Xcode for the iOS tasks.

`shared/src/commonTest/kotlin/.../ExtensionFixture.kt` is a backup written by the extension's own
`buildBackup`, not typed out — it is what the format tests import. Regenerate it the same way if
the extension's format changes.

## Not done yet

- **iOS file picking.** Backups move only through Android or the extension for now; import from a
  URL works on iOS too.
- **Choosing a rendition on iOS and the desktop.** AVPlayer and VLC pick the quality themselves.
- **Keeping the display awake on the desktop** while video plays.
- **The desktop `hlsplayer://` scheme** is handled when the OS delivers it, but the packaged app
  does not register it yet.
- **Subtitle tracks inside an HLS manifest** (`EXT-X-MEDIA TYPE=SUBTITLES`) are not offered. Files
  attached to a movie or shipped by an addon's stream are.
- **Editing a movie from the player.** The extension offers it in the side panel; here it is on
  the library card.
