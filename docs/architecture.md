---
title: Architecture — unified seams & modules
kind: reference
updated: 2026-10-31
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
| `:core:video` | JVM | YouTube resolution: `VideoResolver`, `SignedInTvPlayer`, `PlayerStreams`, `VideoQuality`, `RangedFetch`, `LookupStore`, the `VideoCodecSupport` port. Pure JVM so the CLI's home-download service runs the same code as the app; the Android decoder list (`PlatformVideoCodecSupport`) stays in `:app`. |
| `:lib:ytdlp` | pure JVM | yt-dlp API: `YtDlpEngine` (extract/search/download), result types. Platform-neutral. |
| `:lib:ytdlp-chaquopy` | Android | Real engine on embedded CPython; `BridgeJson` (JSON in/out), wheel self-update. |
| `:lib:innertube` | pure JVM | YouTube InnerTube seams: auth (TV device-code OAuth), feeds, comments, related, actions, playlists, subscriptions, history. `InnerTubeClient`. |
| `:lib:common` | pure JVM | `HttpUrl` — the one validated URL type. |
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
