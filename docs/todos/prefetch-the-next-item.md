---
title: Preload the next item's first 30 seconds
kind: todo
area: playback
priority: medium
status: done — readiness and byte preload both shipped, Wi-Fi only; the video nomination was wrong until 2026-08-17
updated: 2026-08-17
---

# Preload the next item's first 30 seconds

Dewi, 2026-08-02: *"So our app has like a 30 sec buffer right???? If we are coming to the end of
a episode (t-29) seconds does it start buffering the next in the queue??"* — and, on scope,
*"just 30 seconds of future to be loaded right??"*.

His premise was right: `PlaybackService.MIN_BUFFER_MS` is 30s. That is the FLOOR for the item
playing (`MAX_BUFFER_MS` is four minutes); 30s is the budget for the *next* item.

"Getting the next item ready" is two layers, and only one of them is shared across the pillars.

## Layer 1 — readiness. Done, and it costs no mobile data

`NextUpPrefetcher` already did this for YouTube at a 45-second lead. It skipped everything else on
the reasoning that *"only a video costs anything to resolve"* — true when written, and false once a
torrent gained an audio-only URL with ~25s of ffmpeg behind it. That guard is now
`worthPreparing()`, and `AppContainer`'s `prefetchOne` is an exhaustive `when`, so a fourth kind of
playable cannot silently get no preparation.

| Handle | Getting it ready |
|---|---|
| `Video` | resolve, warming the resolver's short-lived cache |
| `Podcast` with an `audioUrl` (a torrent) | ask the home server to start remuxing — the ~25s case |
| `Podcast` without one | nothing; an enclosure URL is already playable |
| `LocalVideo` | nothing; it is already on the device |

The 45s lead stays. It was chosen against a ~7s extraction and is a better fit for a 25s remux
than 30 would be — and unlike Layer 2 it pulls no media, so a longer lead costs nothing.

**A separate class was written for this and deleted before it shipped.** It duplicated
`NextUpPrefetcher` almost exactly — same lead, same `peekNext`, same once-per-item guard. Worth
recording: the seam already existed and only its routing was out of date.

## Layer 2 — the bytes. Shipped 2026-08-04, Wi-Fi only

`ExoPlayer.PreloadConfiguration` looks like the answer and is not: it preloads the next item **in
the player's playlist**, and `Media3PlaybackController` calls `setMediaItem` — one item at a time,
because `PlaybackQueue` owns advancing (skip segments, history, just-in-time resolution).

The mechanism that fits is `DefaultPreloadManager` (present in Media3 1.10.1, checked), which
preloads media sources independently of the playlist. It has to live in `PlaybackService`: only
the service side owns `MediaSource`s, so a `MediaController` cannot be handed one. That means a
custom session command to nominate the next item, and it is a real piece of work rather than a
setting.

### What 30 seconds actually costs

| | 30s of "future" |
|---|---|
| Podcast enclosure | ~0.5 MB |
| Torrent, Listen mode | ~1 MB |
| Torrent, watching | ~7.6 MB |
| YouTube 1080p | ~5–10 MB |

Flat in time, eight times apart in bytes. In Listen mode it is cheap; watching video it is ~8 MB
per track change.

### Decided: Wi-Fi yes, mobile no

Dewi, 2026-08-02: *"defo yes on wifi, but maybe not on mobile please"*. So the byte preload is
gated on `NetworkStatus.isMetered()`, which already exists and already errs toward "metered" when
the state is unknown — the data-saving side, which is the right way to be wrong here.

This gate applies to Layer 2 ONLY. Layer 1 runs everywhere, on any connection, because it pulls no
media at all — gating readiness would give up a 25-second saving to protect data it never spends.

### There is no player cache yet, and that decides the route

Checked 2026-08-02: no `SimpleCache`, `CacheDataSource` or `CacheWriter` anywhere in the app. That
rules out the simplest possible implementation — writing 30s into a cache the player will later
read from — and leaves two routes:

1. **`DefaultPreloadManager` in `PlaybackService`.** Contained: the service already sets a custom
   `MediaSourceFactory` (`MergingAudioVideoFactory`), so a wrapping factory can hand back a
   preloaded source when one exists. Needs a custom session command to nominate the next item,
   because the app talks to the service through a `MediaController`.
2. **Add a `SimpleCache`.** Simpler preloading, but it changes how *all* playback reads bytes and
   needs a disk budget and an eviction policy alongside downloads. A bigger change to something
   already shipped, for the same result.

Route 1, on the strength of blast radius: it touches only what is being added.

## Related

- `docs/todos/listen-mode-saves-data.md` — the audio-only stream this readies.
- `AutoAdvancer` — the sibling that watches for the END rather than the position.

## How Layer 2 was actually built

`DefaultPreloadManager` lives in `PlaybackService` and holds the first **30 seconds** of whatever
the app nominates. `ExoPlayer.PreloadConfiguration` could not do it: that preloads the next item in
the PLAYER'S PLAYLIST, and the queue plays one item at a time because it owns advancing.

The app cannot preload anything itself — only the service owns media sources, and a
`MediaController` cannot be handed one — so nomination goes over a custom session command
(`ACTION_PRELOAD_NEXT`), alongside the skip-silence and volume-boost commands that were already
there. The player and the preloader share ONE `MediaSourceFactory`: a source preloaded by one
factory and played through another is a different object, and its bytes would simply be discarded.

Nominating happens inside `readyAgain`, so preloading rides the same seam as resolving a video and
warming a torrent. One place decides what "get the next item ready" means.

### The Wi-Fi gate, and a bug it caught

`preloadBytesOf` declines on a metered connection, per Dewi's *"defo yes on wifi, but maybe not on
mobile please"*. It also declines for a **downloaded** item — and the first version did not, because
it was spelled `localPath?.let { null } ?: (audioUrl ?: mediaUrl)`. An elvis cannot tell a
deliberate null from an absent one, so it fell straight through and would have fetched a file
already on the device over the network. `PreloadOnWifiOnlyTest` caught it before it shipped.

### Two things only a device could show

- **`setPreloadLooper` throws** when the builder was created with the `Context` constructor, which
  already supplies one. It compiles perfectly and dies at runtime.
- **A session command must be advertised in `onConnect`** or it is rejected in silence — which looks
  identical to a preload that simply did nothing.

`PreloadCommandReachesServiceTest` asserts the SERVICE's own breadcrumb, written only after the
preload manager has accepted the item, so a passing test means the command arrived, was permitted,
and was taken.

### Videos too, since 2026-08-04

A video's stream URL does not exist until it resolves, so its bytes are nominated on the FAR SIDE of
the resolution rather than alongside the other pillars. `VideoResolver.prefetch` now returns what it
resolved instead of only caching it, which is the one thing that could not be known beforehand.

It nominates the stream the CURRENT mode will actually play: the audio-only track when listening,
the video otherwise. Preloading the picture for a mode that will never show it would spend the data
twice over. A video with no separate audio track falls back to the muxed stream, which is still
better than nothing.

All three pillars now preload, and every nomination goes through one `nominatePreload`, so the Wi-Fi
gate cannot be bypassed by a future caller.

### It nominated the wrong stream for two weeks (0.1.390, 2026-08-17)

The paragraph above says it "nominates the stream the CURRENT mode will actually play". It did not,
and the app had been saying so all along:

```
20:25:34.723 preload held a different stream of ng2Tsa5KE_A than the one that played,
             so the preload was wasted
```

`preloadsWasted = 12` out of twelve nominations in one 44-minute session — every preload thrown
away, roughly 30 seconds of 1080p fetched and dropped per track change. 0.1.359 had already recorded
the same thing as "itag 18 held, itag 399 played".

`readyAgain` nominated `resolved.item.mediaUrl`; `playVideoQuality` plays
`choices.qualityFrom(resolved.qualities, cap)?.videoUrl`. Two rules for one question, and they only
agree on a video with no quality ladder — which is almost none of them. The Listen-mode half was
right, which is why reading the code satisfied everyone: the branch that mattered looked correct
and the branch it fell through to did not.

`VideoPlaybackLauncher.urlThatWouldPlay` is now the single answer and the preloader asks for it.
`ThePreloadIsTheStreamThatPlaysTest` asserts the nomination against what the controller is actually
handed, across no cap / a network cap / a hand-picked height / listening.

**And the test that should have caught it was part of the problem.** `PreloadOnWifiOnlyTest`
reimplemented the rule as a third copy in order to stay a pure unit test, and the copy pinned the
wrong answer — so four green tests certified the bug. Those cases are gone; the rule is tested where
it lives. Reimplementing a rule in a test cannot catch the rule being wrong, only make it feel
covered.

### Still worth doing

Measuring what it actually saves at a track change on a device — the mechanism is proven, the felt
improvement is not, and until 2026-08-17 it was saving nothing at all on video.
