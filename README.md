# HLS Player for Android

The [HLS Player browser extension](../hls-player) as a Kotlin Multiplatform app: a library of HLS
and DASH movies, Stremio addons as sources, a per-movie `Referer`, server-side ad stripping,
intro and outro skipping, resume, subtitles and a stream picker. Android ships; iOS compiles but
does not play yet.

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

## Build and test

```shell
./gradlew :shared:jvmTest                          # the shared suite, fast
./gradlew :shared:iosSimulatorArm64Test            # the same suite on the iOS simulator
./gradlew :composeApp:assembleDebug                # -> composeApp/build/outputs/apk/debug
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
```

JDK 17 and the Android SDK (API 36) are needed; Xcode for the iOS tasks.

`shared/src/commonTest/kotlin/.../ExtensionFixture.kt` is a backup written by the extension's own
`buildBackup`, not typed out — it is what the format tests import. Regenerate it the same way if
the extension's format changes.

## Not done yet

- **iOS playback and file picking.** AVPlayer has no playlist hook, so ad stripping and PNG-wrapped
  segments need a local proxy or an `AVAssetResourceLoaderDelegate`; `Referer` goes through
  `AVURLAsset` header options. The UI runs; the player says it is not built.
- **Subtitle tracks inside an HLS manifest** (`EXT-X-MEDIA TYPE=SUBTITLES`) are not offered. Files
  attached to a movie or shipped by an addon's stream are.
- **Editing a movie from the player.** The extension offers it in the side panel; here it is on
  the library card.
