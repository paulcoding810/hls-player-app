# hls-player-app

Kotlin Multiplatform port of the `hls-player` browser extension (sibling repo `../hls-player`).
Read `README.md` for the layout and what is not done yet.

```shell
./gradlew :shared:jvmTest
./gradlew :shared:iosSimulatorArm64Test
./gradlew :composeApp:assembleDebug
```

## Rules

- **Logic lives in `shared`, never in the UI or the engine.** A playback rule goes in
  `PlayerController`, which is tested against `FakeEngine`; `Media3Engine` only plays what it is
  told. A screen calls `AppData`, `StremioClient` or the controller — it does not decide.
- **The backup format is the extension's.** Field names, `null` meaning "inherit", optional fields
  left out rather than written `null`. `BackupTest` imports `EXTENSION_BACKUP` and re-exports it;
  `carriesEveryMovieField` fails when a new `Movie` field is not carried by `sanitizeMovie`.
- **Untrusted JSON is read with `util/Js.kt`**, which spells out JavaScript's coercions so the app
  accepts and refuses what the extension does — except where the extension is wrong, which is
  then commented (a `null` skip stays `null`; see `numberOrNull` in `backup/Backup.kt`).
- **`episodeKey()` is an episode's identity**, not its URL: an addon's episodes resolve a fresh URL
  on every play. Anything that filters stored keys goes through `isEpisodeKey()`.
- **Every write goes through `JsonStore`'s lock.** Writes that must outlive a screen use
  `AppDeps.persistScope`.
- **Tests on a coroutine clock**: `backgroundScope` work is skipped by `advanceUntilIdle`, so the
  controller gets its own scope on the test scheduler; Ktor's mock engine is given the test
  dispatcher so its requests cost no real time.

## Design

The extension's design rules carry over: dark only, red-accented, flat. Colors come from `Palette`
in `ui/Theme.kt` and nowhere else; icons are `ui/Icons.kt`, generated from the extension's SVGs.
At most one `PrimaryButton` per view, `GhostButton` otherwise; something that just happened is a
`Toast`, something still true is a `Banner`; a confirmation is `Confirm`, where dismissing is the
negative answer.
