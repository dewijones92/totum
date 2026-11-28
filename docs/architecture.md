---
title: Architecture — unified seams & modules
kind: reference
updated: 2026-11-28
---

# Architecture

The twin laws (from `CLAUDE.md`): **Unified** — one seam per capability serving
both pillars — and **strictly DRY**. Every feature must be unified across
YouTube/video and podcasts by default; a pillar-specific split is allowed only
for a genuinely strong technical reason, which must be surfaced to Dewi.

## Modules (deps point inward)

| Module | Kind | Holds |
|---|---|---|
| `:core:domain` | pure JVM | `MediaItem`, `MediaSource` (VideoChannel \| PodcastFeed), `SourceId`, `Chapter`, `SkipSegment`, `DownloadState`. `explicitApi()`. No Android. |
| `:core:data` | JVM | Business logic: `RssParser`, `PodcastRepository`, search seam (`SearchSource` → `SearchHit`), `SearchHistoryStore`, downloads (`DownloadManager` + strategies), `SponsorBlockSegmentSource`, `ContentRefresher`/`SeenItemsTracker`, import/export. Ports here, impls in Android modules. |
| `:core:database` | Android/Room | Entities, DAOs, `Room*Store` impls. The only place entities meet domain types. Kover-exempt. |
| `:core:playback` | Android/Media3 | `PlaybackController` port + `Media3PlaybackController`, `PlaybackService`, `SleepTimer`, `PlaybackProgressStore`, `PlaybackSpeedStore`. Kover-exempt adapter. |
| `:core:cast` | pure JVM | The plain-Kotlin half of casting: `DashManifest`, the rung choice (`CastLadder`, `CastRungs`), container layouts and remuxers (`Mp4Video`, `MkvVideo`, `WebmLayout`, `Fmp4Aac`), `CastHttpServer`, `CastReach`, `ScreenLink`, `TvCaptions`, `VideoProbe`, the TV port (`CastTvPort`). Package `…playback.cast`. `explicitApi()`. Held by `koverVerify` (87.5% on 2026-11-07, [ADR 119](adr/0119-cast-and-sound-code-shared-with-the-pi.md)). Shared with the Pi's `:cli`. |
| `:core:sound` | pure JVM | The sound processing: `SilenceCutter`, `SpeechModel` (the Silero VAD in plain Kotlin, weights in this module's Java resources), `SpeechTrack`, `LoudnessBoost`, `VolumeBoost`, `LookaheadLimiter`, and the audio-quality suite. `explicitApi()`. The Media3 wrappers stay in `:core:playback`. Shared with the Pi's `:cli`. |
| `:core:video` | JVM | YouTube resolution: `VideoResolver`, `SignedInTvPlayer`, `PlayerStreams`, `VideoQuality`, `RangedFetch`, `LookupStore`, the `VideoCodecSupport` port. Pure JVM so the CLI's home-download service runs the same code as the app; the Android decoder list (`PlatformVideoCodecSupport`) stays in `:app`. |
| `:lib:ytdlp` | pure JVM | yt-dlp API: `YtDlpEngine` (extract/search/download), result types. Platform-neutral. |
| `:lib:ytdlp-chaquopy` | Android | Real engine on embedded CPython; `BridgeJson` (JSON in/out), wheel self-update. |
| `:lib:innertube` | pure JVM | YouTube InnerTube seams: auth (TV device-code OAuth), feeds, comments, related, actions, playlists, subscriptions, history. `InnerTubeClient`. |
| `:lib:common` | pure JVM | `HttpUrl` — the one validated URL type. |
| `:lib:exsurge` | pure JVM | Exsurge et Disce, the side quest: one sealed state machine and the stats. Android adapters in `app/…/exsurge/`. Deliberately not the shared seam in two places (ADRs 2, 3). |
| `:lib:reminders` | pure JVM | The reminder kit shared by the side quests (ADR 17); its Android half is `app/…/reminders/kit/`. |
| `:lib:dailyalarms` | pure JVM | Daily alarms (ADR 18): `DailyAlarm` and the per-day state machine. Android half in `app/…/dailyalarms/`. |
| `:lib:stats` | pure JVM | The stats log's events and every aggregation (ADR 44). |
| `server/` | the Pi | What the app talks to on the home Pi, one compose project each (ADR 85). See `server/README.md`. |
| `:app` | Android/Compose | UI (`AppShell`, screens), ViewModels, manual DI (`AppContainer`), SharedPrefs impls, video resolve/launch, queue, notifications. |

## The one-seam-per-capability map

| Capability | Seam | Both pillars via |
|---|---|---|
| Media item | `MediaItem` | a podcast episode *is* a video — same type |
| Playback | `PlaybackController` + one `MediaSession` + one mini/full player | anything with `mediaUrl` plays |
| Play routing | `routeNow` in `:core:domain` → `VideoFile` \| `AudioFile` \| `VideoStream` \| `AudioStream` \| `Refused` | "disk or network, picture or not" is answered once for both pillars |
| Search | `SearchSource` → sealed `SearchHit` (Podcast \| Video) | iTunes + `ytsearch` adapters |
| Subscriptions freshness | `ContentRefresher` + `SeenItemsTracker` | podcast RSS + YouTube subs adapters |
| Downloads | `DownloadManager` + `RoutedDownloadStrategy`, routed on `PlayHandle.pillar` | `EngineDownloadStrategy` (video) / `HttpDownloadStrategy` (enclosure) |
| Offline library | `DownloadStore.observeDownloaded()` → `DownloadedMedia` | the record carries its item, so no per-pillar catalogue is joined |
| Pillar of a raw feed item | `MediaItem.pillar` (one URL rule) | `PlayHandle.pillar` once a handle exists |
| Where a download's bytes come from | `PlayableItem.fetchUrl` | watch URL for video, enclosure for podcast |
| Skip segments | `SkipSegment` + `skipTargetFor`, enforced in the position ticker | any pillar's playback skips |
| HTTP text | `HttpTextFetcher` port | OkHttp impl |
| URL | `HttpUrl` value type | everywhere |

## DI

Manual: `AppContainer` interface + `DefaultAppContainer` (real) + `FakeAppContainer`
(previews/tests). Construction is code; errors are compile-time. Pillar routing
lives in exactly one place (`AppContainer`, e.g. `RoutedDownloadStrategy`).

## Playback: state and events are different things (2026-08-01)

`PlaybackController` exposes both, and the split is not decoration:

- **`state: StateFlow<PlaybackState?>`** — how things ARE. What the UI binds to. Re-emits on
  every position tick and drops values equal to the last, which is right for rendering.
- **`events: Flow<PlaybackEvent>`** — what HAPPENED, delivered once each, no replay. What
  anything reacting to a change binds to.

The rule: **if you are asking "has X changed?", you are on the wrong flow.** Reconstructing an
edge from a level signal means keeping private memory of what you saw and what you acted on, and
that memory is what goes stale. Two watchers wrote the same guard and both got it wrong within
one week — `AutoAdvancer` refused an item's second end citing one three hours old, and
`StallWatchdog` could not rescue an item it had rescued before.

Events are derived in exactly ONE place, `Media3PlaybackController`, from the player's own
callbacks — which are already edges. No replay is deliberate: an end that happened before a
consumer subscribed is not news, which is what makes the "already ended when we connected after a
process restart" case disappear instead of needing a branch.

### Why not move everything to events

Three other things watch playback, and they stay on `state` for reasons worth stating, because
"why isn't this an event too?" is the obvious next question:

- **`StallWatchdog`** — a stall is the ABSENCE of change. No player callback can deliver it,
  which is exactly why the watchdog samples on a clock rather than collecting: an earlier version
  DID collect the state flow and never fired, because a stall is a run of identical states and a
  `StateFlow` conflates them. Deriving a `Stalled` event in the controller would relocate that
  sampling, not remove it, and would move a threshold that is app policy into core. Its remaining
  memory is minimal and reset on progress, with a test for the reset.
- **`NextUpPrefetcher`** — triggers on *time remaining*, which is a question about how things
  ARE, not about something that happened.
- **The UI** — renders the current state, which is what a level signal is for.

The rule is therefore not "events everywhere" but: **if you are asking "has X changed?", you are
on the wrong flow.** Only `AutoAdvancer` was asking that, and only its guard was deleted.

## Module and seam notes

The fuller account of each module and seam, moved from `CLAUDE.md` at commit `698933d9` on 2026-11-28. The tables above
are the index; `CLAUDE.md` keeps only the architecture rules (which module may depend on what, the one-answer seams, the
Chaquopy and Exsurge exceptions) and wins if the two ever disagree.

- `:app` — Compose UI: `AppShell` bottom navigation (Videos / Podcasts / Music / Queue / Library),
  search as a header action over the tab (`TabNavigation`), theme, screens.
- `:core:domain` — pure-Kotlin (JVM) unified media model: `MediaSource`
  (VideoChannel | PodcastFeed), `MediaItem`, `Subscription`, `SourceId`. No
  Android dependency — leakage is a compile error. `explicitApi()` is on.
- `:core:data` — pure-Kotlin (JVM): `RssParser` (hardened DOM), the
  `PodcastRepository` with its `HttpTextFetcher`/`PodcastStore` ports, OkHttp
  fetcher, and the unified search seam: `SearchSource` port returning sealed
  `SearchHit`s (Podcast | Video), implemented by `ItunesPodcastSearchSource`
  (iTunes directory API) and `YtDlpVideoSearchSource` (engine `ytsearch`).
  Also `SkipSegmentSource` (SponsorBlock-backed, fail-open: lookup failure =
  no segments). Business logic lives here, testably, off Android.
- Skip segments (`SkipSegment` + `skipTargetFor` in `:core:domain`) are
  enforced in exactly one place — `Media3PlaybackController`'s position
  ticker — so any pillar's playback skips them.
- **Downloads** (`DownloadManager` port in `:core:data`, `RoomDownloadStore`
  in `:core:database`): one seam, one port, taking a `PlayableItem` — the
  handle is both the pillar and, for a video, the watch URL to fetch from.
  The manager takes a single `DownloadStrategy`; `RoutedDownloadStrategy`
  (wired in `AppContainer`, the only place pillar routing lives) picks by
  `PlayHandle.pillar` in an exhaustive `when` — `EngineDownloadStrategy`
  for video (yt-dlp fetches best video+audio and merges via the bundled
  ffmpeg; since ADR 16 it no longer cuts SponsorBlock segments, and files on disk skip them live
  from the stored list that `SkipSegmentsOnDisk` refreshes), `HttpDownloadStrategy` for podcast
  enclosures (a plain HTTP GET). A third pillar cannot be added without that
  `when` failing to compile. `DownloadState` in `:core:domain`; playback
  prefers the local file wherever one exists — decided by `routeNow` below, for
  both pillars, so a downloaded video needs no re-resolution. Interrupted downloads (a `Downloading` row at
  startup) start again by themselves, with their variant and height, at most twice, then are left Failed for Retry
  (ADR 80; until 2026-08-31 they were dropped, then left Failed).
  Strategy IO runs off the main thread (`flowOn(Dispatchers.IO)`). Verified
  offline in airplane mode; video merge verified on-device (AV1 4K + Opus →
  one Matroska, played locally).
- **A download record carries its item**, on the same `PlaylistItemColumns`
  the queue, playlists and history use (four tables, one mapper). So
  `observeDownloaded()` returns `DownloadedMedia` (item + path + variant) and
  the Library tab lists **both pillars** from the download store alone, with
  no catalogue to join against — it previously joined podcast episodes, which
  is why a downloaded video was on disk and invisible. `DownloadedMedia.offline`
  supplies the local handle, so an audio-only video plays as audio and a full
  one as `LocalVideo`.
- **"How do I play this, right now?" is answered once**, by `routeNow` in
  `:core:domain` (`PlayRoute.kt`), for both pillars: it takes any copy on disk,
  whether there is a network and whether Listen mode is on, and returns one of
  four routes (`VideoFile` | `AudioFile` | `VideoStream` | `AudioStream`) or a
  `Refused` with its reason. `PlaybackQueue.route` is the only caller and does
  nothing but carry it out. It exists because the question had **one branch per
  pillar and the branches disagreed**: the podcast branch asked the download store
  for a local copy and the video branch never did, so a downloaded YouTube item
  was refused in airplane mode with its file on the disk (reported 2026-08-06).
  The handle-to-disk swap is shared with `DownloadedMedia.offline`
  (`playedFromDisk`), so the Library and the queue cannot drift apart again.
  One deliberate asymmetry, Dewi's call: an **audio-only** copy of a video does
  not stand in while you are *watching* (it would silently drop the picture) —
  it does the moment you are listening or offline.
- **"Is this a video?" is answered once**, by `MediaItem.pillar` in
  `:core:domain`, and "where do the bytes come from" once by
  `PlayableItem.fetchUrl`. Both used to exist twice with rules that disagreed
  (`youtube.com/watch` vs any YouTube host), so a Shorts URL downloaded as a
  video but queued as a podcast enclosure. Anything holding a `PlayHandle`
  reads `PlayHandle.pillar` instead — it knows exactly rather than guessing.
- **ffmpeg IS bundled** as a minimal static binary (`libffmpeg.so` in
  `app/src/main/jniLibs/<abi>`, ~7MB; built from FFmpeg 7.1.1 by
  `tools/ffmpeg/build-ffmpeg-android.sh`, remux-only — no decoders/encoders).
  **ffprobe is bundled too** (`libffprobe.so`, another ~7MB per ABI): yt-dlp's
  ModifyChapters postprocessor — the one that cut SponsorBlock segments out of
  a download until ADR 16 — asks ffprobe for the media duration and fails with "ffprobe not
  found" without it. Together they are ~30MB of the repo's 36MB working tree,
  which is why the tracked binaries look disproportionate and are not a mistake.
  PyPI has no `aarch64-linux-android` ffmpeg wheel, so it can't be
  pip'd; a shipped binary is the only way. Under Android 14 W^X the only
  app-private executable location is `nativeLibraryDir`, so the `.so` is
  extracted there (`packaging { jniLibs { useLegacyPackaging = true } }`) and
  `FfmpegBinary` symlinks it to the name yt-dlp expects; the engine passes
  `ffmpeg_location`. This unblocks merged best-quality video and download-side
  SponsorBlock removal. The interpreter and ffmpeg can't self-update (W^X);
  yt-dlp itself (pure Python) still can.
- `:core:database` — Android library (Room via KSP): entities, DAO, and
  `RoomPodcastStore` implementing `:core:data`'s `PodcastStore` port. The only
  place entities meet domain types. Verified by instrumented tests; exempt from
  the Kover JVM gate.
- `:core:playback` — the unified playback seam: `PlaybackController` port +
  `PlaybackState`, implemented by `Media3PlaybackController` connected to a
  `MediaSessionService` (`PlaybackService`, foreground, Media3-managed
  notification with title/artist/artwork, seek back 10s / forward 30s, audio
  focus + becoming-noisy handling). Both pillars play through it — anything
  with a `MediaItem.mediaUrl` is playable, and every system surface
  (notification, lock screen, Bluetooth/headset media keys, Assistant)
  controls that one session. `POST_NOTIFICATIONS` is requested once as the shell
  composes (`RequestNotificationPermissionOnce`) — required on API 33+ or the
  notification never shows. It used to be asked for at FIRST PLAY, which was a
  defect: the dialog is another activity, so it paused ours and released the video
  surface, and pressing play on a fresh install stopped the picture. CI was red on
  it for five days reading as "the stream stopped". `fake.FakePlaybackController` for tests/previews.
  Kover-exempt adapter (Media3 glue; instrumented-verified).
  **Video renders on the same seam**: `PlaybackState.hasVideo` (from the track
  list) drives a `PlayerSurface` (media3-ui-compose) in the one `FullPlayer` —
  podcasts show artwork/controls, videos show the picture; no separate video
  player. The port exposes the `Player` so the UI binds a surface; the surface
  must appear before the decoder reports a size, so `hasVideo` (not video size)
  gates it, defaulting to 16:9 until `videoAspectRatio` is known. Streaming is
  pre-muxed quality; merged best quality comes via downloads (ffmpeg).
- Manual DI: `AppContainer` (in `:app`) wires the graph; construction is code,
  errors are compile-time. No Hilt/Koin.
- **Cleartext HTTP is deliberately permitted** (network_security_config):
  podcast enclosures in the wild are frequently plain http (BBC media hosts
  included); refusing them breaks playback of legitimate feeds. Same policy
  as AntennaPod.
- `:core:video` — YouTube resolution (`VideoResolver`, `SignedInTvPlayer`, `PlayerStreams`, `VideoQuality`,
  `RangedFetch`, `LookupStore`, the `VideoCodecSupport` port), pure JVM since 2026-10-31 so the Pi's download
  service (the CLI) resolves with the same code as the app. Only the decoder list (`PlatformVideoCodecSupport`)
  and the SABR download route stay in `:app`.
- `:core:cast` — the plain-Kotlin half of casting (manifest, rung choice, container layouts and remuxers, the cast HTTP
  server, reach, `ScreenLink`, captions, probes, the `CastTvPort`), pure JVM with `explicitApi()`, package
  `…playback.cast`, so the Pi's `:cli` runs the same code as the phone (ADR 119). `:core:playback` keeps what touches
  Media3, MediaCodec or Android (`CastStreams` as an adapter, `MediaCodecAudioEngine`, `CastHost`, `CastStatus`, `ScreenPlayer`); the stream server itself (`CastStreamServer`, `ProcessedAudio`) is here. Held by `koverVerify` (87.5%
  on 2026-11-07).
- `:core:sound` — the sound processing (`SilenceCutter`, `SpeechModel`, `SpeechTrack`, `LoudnessBoost`, `VolumeBoost`,
  `LookaheadLimiter`), pure JVM with `explicitApi()`, shared with the Pi (ADR 119). The Silero weights
  (`silero_vad.bin`) are its Java resource, read by `SpeechWeights.bundled()`; the Media3 wrappers
  (`SilenceCuttingAudioProcessor`, `BoostingAudioProcessor`, `AudioChain`) stay in `:core:playback`. Its audio-quality
  suite is `:core:sound:test -Ptotum.audioQualityTests`.
- `:lib:ytdlp` — from-scratch yt-dlp library (replaces the youtubedl-android
  fork). **Pure JVM on purpose** — it is the platform-neutral API (types,
  port, fake); only the real engine module needs Android. Public API:
  `YtDlpEngine` (suspend `extract`, `searchVideos`, cold-`Flow` `download`,
  sealed results), `bestPlayableFormat()` selection. Deliberately independent
  of `:core:domain` (standalone, reusable).
- `:lib:ytdlp-chaquopy` — the real engine: yt-dlp on embedded CPython 3.12
  via Chaquopy 17 (MIT). `totum_ytdlp.py` is a thin JSON-in/JSON-out bridge;
  `BridgeJson.kt` parses it (JVM unit-tested); `ChaquopyYtDlpEngine`
  implements the API. Chaquopy constraints: exactly ONE module per app may
  apply the plugin; build-host Python minor version must match the target
  (3.12 here); NOT configuration-cache compatible (config cache disabled in
  gradle.properties because of this). ABIs: arm64-v8a + x86_64. Adds ~80MB
  to the APK (Python runtime per ABI). **yt-dlp self-updates at runtime**:
  `YtDlpUpdater` fetches the latest wheel from PyPI on every launch (background,
  SHA-256-verified, never starts Python) into a cache dir; `totum_bootstrap.py`
  prepends it to `sys.path` before `import yt_dlp` so a YouTube-breaking fix
  applies on the next start without an app update — a bad wheel is dropped and
  the bundled copy used. The interpreter and ffmpeg can't self-update (W^X).
  ffmpeg is bundled separately in `:app` jniLibs (see Downloads above).
- `:lib:innertube` — pure-JVM **YouTube account seam**, the second engine
  beside `:lib:ytdlp` (which stays read-only): signed-in capabilities — subs
  feed, history, comments, likes/actions — talk YouTube's private InnerTube
  API here, authenticated SmartTube-style via Google's **TV device-code OAuth
  flow** (user visits google.com/device and types a short code; no WebView —
  Google blocks embedded logins; endpoints + public TV client identity
  live-verified 2026-07-13). Auth layer: `YouTubeAuth` port +
  `HttpYouTubeAuth` (OkHttp), `DeviceLogin` cold-flow driver owning all
  protocol pacing (poll interval, slow_down backoff, expiry; transient poll
  failures retried until the code expires), `TokenStore` port (app supplies
  storage; access token ~1h, refresh token long-lived, `invalid_grant` on
  refresh = signed out, re-login). Token value classes redact themselves in
  `toString()` so credentials can't leak into logs. Fakes for tests/previews.
- `:lib:exsurge` — **Exsurge et Disce**, the side quest (not a pillar): after 30 minutes of
  sitting, Surgius (a Roman-orator mascot) takes over the screen and sends Dewi into Loquax
  (`dev.hanzi.hanzi_practice`) for five minutes. Pure JVM: one sealed state machine and the stats.
  `app/…/exsurge/` holds the Android adapters. Two things here are deliberately not the shared
  seam: its **voice clips play on their own `MediaPlayer`** (alarm sounds, never media; ADR 3), and
  its **state is not in Room** (ADR 2). It pauses and resumes playback only through
  `PlaybackController.setPlaying` and `PlaybackInterruption` (ADR 4). Its tests are an opt-in phase
  (`-Ptotum.exsurgeTests`) with their own workflow (ADR 7). Feature doc:
  `docs/features/exsurge-et-disce.md`.
- `:lib:reminders` + `app/…/reminders/kit/` — the **reminder kit** shared by the side quests (ADR 17):
  waveforms, choice lists, next-weekday-time maths and time serializers; exact and alarm-clock alarms, an
  alarm-channel voice (clips and text-to-speech), the alarm tone, the buzzer, chips, a clock picker, a
  lock-screen helper and a JSON prefs store.
- `:lib:stats` — the stats log's events and every aggregation, pure; `app/…/stats/` records them and
  `app/…/ui/stats/` shows them (ADR 44).
- `:lib:dailyalarms` — **Daily alarms** (ADR 18): `DailyAlarm` and the per-day state machine.
  `app/…/dailyalarms/` holds the controller, `RingService` (systemExempted foreground service that owns
  the ringing), the question and ring screens, notifications, receiver, tile and settings.
- `server/` — what the app talks to on the home Pi (Prowlarr, FlareSolverr, TorrServer and its watchdog,
  totum-audio), one compose project each, deployed from the Pi's `~/code/totum`; nginx and gluetun stay in the
  Pi's `dot-files`. See `server/README.md` and ADR 85. The crash sink is `tools/crashlog-server/`.
- `:lib:common` — pure-Kotlin utility module with no app dependencies, shared
  by app modules and standalone libraries alike (it would be published
  alongside `:lib:ytdlp`, like the old youtubedl-android's `common` module).
  Home of `HttpUrl`, the single validated URL type used everywhere.
- detekt, the Android lint policy, and Android build defaults
  (compileSdk/minSdk/Java level) apply to every module automatically from the
  root build — never configure them per module.
- Package root: `com.dewijones92.totum`.
