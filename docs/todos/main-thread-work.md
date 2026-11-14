---
title: Work still done on the main thread, found by four scouts after report 0.1.762
kind: todo
area: performance
priority: medium
status: found 2026-10-30, partly fixed; 2026-11-14 pass blocked (emulator died), nothing further changed
updated: 2026-11-14
---

# Work still done on the main thread

Dewi, 2026-10-30: *"weird random lags … in the UI"*. The two-second freeze after every queue change
(the auto-downloader asking the network gate per item) is fixed and the main thread is now watched
(ADR 100), so the next report names any stall. Four read-only sweeps of the code found more; none
was measured on his phone, and each should be judged by the `[main]` lines a report now carries
before it is worked on.

## Fixed on 2026-10-30

- A podcast feed is parsed and mapped on `Dispatchers.Default` (`DefaultPodcastRepository.load`,
  subscribe / preview / refresh); `PodcastFeedParsedOffTheCallerTest`.
- A channel's RSS is parsed on `Dispatchers.Default` (`ChannelLatestUploads.fetch`), which the
  All-subscriptions check runs for each of 1,619 channels.
- The download table's three mappings and the playback-progress map run on `Dispatchers.Default`
  (`flowOn`), not in the collector (often `applicationScope`, which is Main): the progress map is
  rebuilt on every save, about every 5 s while playing, and the download map on every progress write.
- Send diagnostics writes the report off the main thread ("⏳ Saving diagnostics…" while it does);
  it ran synchronously in the tap and could wait up to 3 s for logcat.
- The breadcrumb buffer counts itself in O(1); `ConcurrentLinkedDeque.size` walked up to 5,000
  entries twice per log line.

## Still open (suspects, not measurements)

- **Every queue emission still does about ten linear passes on Main** at 5,739 items:
  `QueueAutoDownloader.pass` (sort, channel fill, skip per item), `GroupDownloads.track`,
  `SharedMetadataRepair`, `RandomStarts.follow`, the Queue screen's `QueueOfflineSummary`,
  uncached `filterNot`, `upNext` read three times, `rememberQueueActions` without `remember`, and
  `container.isOffline()` (two binder calls) per composition of `QueueScreen`. Play-instead mutates
  twice, so it pays all of it twice. Each queue change also rewrites all 5,739 rows in Room.
- **Whole-screen state rebuilt per download write**: `VideosViewModel` and `PodcastsViewModel`
  re-sort their whole lists, `LibraryScreen` collects four lists for their sizes, and
  `observeGroups()` is called inline (a new Flow per recomposition).
- `rowPlayStates` (`AppContainer`) still combines the whole history map on Main per save.
- Stats drill-down filters the whole event log in the tap; Explore reads every episode with `first()`.
- `RowFollowing` is a `staticCompositionLocalOf` given a new value when the follow lists change.

## 2026-11-14: not advanced, and what is now certain

The pass meant to judge the open suspects by the `[main]` lines of Dewi's real-scale state (ADR 100) could not run:
the project emulator died with a segfault at 06:55 and the builder may not boot one, and Dewi's state dump carries
no `[main]` lines or vitals (1,400 events, none tagged `main`), so the evidence the todo asks for does not exist yet.
`SharedMetadataRepair` was left alone as briefed (another builder is moving it off Main).

Read from the code, not measured: the queue auto-downloader's pass runs on Main. `startQueueAutoDownloader` hands it
`applicationScope`, which is `SupervisorJob() + Dispatchers.Main` (`AppContainer.kt`), and `pass` sorts, counts and
channels every one of 5,857 entries per emission before any lane starts. That makes it the first suspect to
measure, with `RandomStarts.follow` and `GroupDownloads.track`, which have the same scope. Moving the collector to
`Dispatchers.Default` is the obvious fix, but `isEnabled`, `isAllowedOnThisNetwork`, `playingNow`, `atHome` and
`groupAllows` are lambdas from `AppContainer` and each would need checking for main-only state first.
