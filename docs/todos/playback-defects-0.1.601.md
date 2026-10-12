---
title: Playback findings from the 0.1.601 phone reports
kind: todo
status: triaged — fixes outstanding; volume boost needs discussion
area: playback/downloads
priority: high
requested: 2026-10-12
updated: 2026-10-12
---

# Playback findings from the 0.1.601 phone reports

Dewi requested that older reports be marked considered and the current-build reports investigated.
All five current reports identify `0.1.601`, commit `42273dc`, which matches both `main` and the
`v0.1.601` release tag at triage. These findings still apply to that source. No playback code was
changed, no fix was released, and no emulator reproduction was attempted during triage.

The server stores the per-report findings and their state; the identifiers below refer to those
records. `triaged` makes a report considered without asserting that its problem is fixed.

| Report | Findings | Result |
|---|---|---|
| `20261011T212827-38120d23` | #37, #44 | SponsorBlock list lost during download handover; subtitle rate limiting and buffering also observed |
| `20261010T211240-5a6fd7eb` | #38, #43 | Watch plays the wrong item; subtitle rate limiting and buffering also observed |
| `20261010T115639-add5c898` | #39 | Listen only keeps a downloaded video's picture |
| `20261010T115927-ab9006fc` | #40, #41 | Same Listen defect; switching can also jump backwards |
| `20261010T070752-5ac72012` | #42 | Volume boost sound quality needs discussion |

## SponsorBlock list lost during handover — #37

For `ND_kRgU_WlY`, playback starts with three segments at `22:23:27`. At `22:23:30` the new audio
download takes over and the player starts with zero segments. The download callback stores
`0 -> 3` concurrently; the playback refresh then reports three segments **unchanged**, without
updating the player. No sponsor skip is logged before Watch restores the three segments at
`22:27:42`. The same report shows a one-to-zero loss for `luoPwt2I0d0`.

The source explains that sequence:

- `PlaybackQueue.route` starts file playback with the segments read from the download store.
- `AppContainer.onDownloaded` refreshes the stored segments without updating the player.
- `SkipSegmentsOnDisk.refresh` returns `null` when the store already matches the lookup.
- `PlaybackQueue.refreshSkips` updates the player only for a non-null result.

Thus a concurrent store refresh can leave the active player with an empty list even though the
database already contains the correct list. Playback needs the current segment list regardless
of whether refreshing it changed the database.

Source: `app/…/queue/PlaybackQueue.kt`, `app/…/di/AppContainer.kt`,
`core/data/…/download/SkipSegmentsOnDisk.kt`.

## Watch uses stale launcher state — #38

At `22:11:54`, Watch asks for `y50uWvxGb-A` (Sarah Paine), but immediately starts `WVOxI6Vc2Xo`
(“Do We Live in a Multiverse?”) at Sarah's position, approximately `289498ms`.

Sarah was playing through the queue's `AudioFile` route. That route calls the playback controller
directly and does not replace or clear `VideoPlaybackLauncher.current`. Its picture preparation
only warms the resolver cache. `WatchViewModel.watch` then calls `launcher.watch`, which plays
`current` without checking it against the item actually playing.

The launcher can therefore retain a previous streamed video while a different downloaded item
plays. `listenIfPossible` already checks an expected item id for recovery, but the player-facing
Watch and Listen calls do not have that guard. Mode changes must operate on the current playback
item through the shared routing seam.

Source: `app/…/ui/player/WatchViewModel.kt`, `app/…/video/VideoPlaybackLauncher.kt`,
`app/…/queue/PlaybackQueue.kt`.

## Listen only ignores a full download — #39 and #40

Both reports concern `eUccYcR6hhs` (“Zheng Qinwen lights up Beijing”). At `12:56:11` the row action
sets `AUDIO`; the route logs `copy=full listen=true`, chooses the downloaded video, and a
`1920x1080` picture appears at `12:56:12`. The second report repeats the same failure through
several mode changes, ending with `hasVideo=true` while `settings.playbackMode=AUDIO`.

`routeNow` selects `VideoFile` for a full local copy regardless of `audioPreferred`. The queue's
`VideoFile` branch calls `launcher.playLocal`, which plays the full file without disabling its
video track. These two reports are occurrences of one routing defect. The local copy should
remain usable in Listen mode, with the picture disabled through the unified playback path.

Source: `core/domain/…/PlayRoute.kt`, `app/…/queue/PlaybackQueue.kt`,
`app/…/video/VideoPlaybackLauncher.kt`.

## A full-file switch loses the exact position — #41

At `12:58:21`, the second Listen report replaces the item around `113130ms` but resumes at
`103755ms`, approximately 9.4 seconds earlier.

The row's mode switch calls `queue.playNow` without the current position, so the controller
falls back to periodically saved progress. In addition, the queue's `VideoFile` branch drops
any supplied `startPositionMs` when calling `launcher.playLocal`, which also calls
`controller.play` with its default position of zero. Both the mode-switch caller and full-file
playback need to carry the exact position; forwarding it only inside the file route would not
fix the row action.

Source: `app/…/ui/common/MediaItemActions.kt`, `app/…/queue/PlaybackQueue.kt`, `app/…/video/VideoPlaybackLauncher.kt`,
`core/playback/…/Media3PlaybackController.kt`.

## Volume boost needs a sound-quality discussion — #42

The report asks to discuss other options, including compression. It has `AUTO` enabled while
classical guitar music plays, including “Suite Castellana: II. Arada”. Gain moves from `25.1dB`
at `08:00:30` to `15.6dB` at `08:00:45`, then `23.0dB` at `08:01:08`. Limiter reduction reaches
`-17.5dB` elsewhere in the report. Every recorded boost measurement has `clipped=0`.

The current implementation is adaptive gain with a two-second level estimate and 400ms gain
smoothing, followed by a five-millisecond look-ahead limiter. Music sets speed and skip-silence
aside but retains the boost. Large changes in gain could alter musical dynamics or sound like
pumping; that is a hypothesis, not an audible diagnosis from these logs.

Before choosing a replacement, establish which audible symptom Dewi wants addressed and whether
music should preserve its dynamics or receive the same leveling as speech. The existing mechanism
and validation are documented in [loudness boost](../features/loudness-boost.md).

## Subtitle rate limiting and buffering — #43 and #44

Both evening reports show repeated HTTP `429` responses from YouTube's `/api/timedtext` endpoint.
The Watch report has a `10771ms` buffering interval with two media loads outstanding; the sponsor
report has a `15913ms` buffering interval earlier in its session.

The subtitle failures and media stalls overlap, but these reports do not establish that the
subtitle retries caused the stalls. Their cause remains open. These are subtitle `429`s, not
media URL `403`s, and no recurrence of the position reset fixed in `v0.1.599` was established.

## Older reports considered by request

`20261008T220628-07f2b904` and `20261008T180001-1cad320a` (`0.1.593`), plus
`20261008T095903-bfb3844c` (`0.1.587`), were marked `triaged` with a note that Dewi requested
consideration by build age, without individual investigation or an assertion that they were fixed.

## Verification needed when fixes are implemented

- Reproduce each confirmed defect with a failing regression test before changing its code.
- Exercise mode switches for streams, full local videos and audio-only copies, after item changes
  and gapless handovers, while asserting both the item identity and exact resume position.
- Exercise download handover with the two segment refreshes completing in either order; assert
  that the active player receives the stored list even when the lookup reports no database change.
- Apply the repository's playback and queue soak requirements before shipping playback changes.
- Judge a boost redesign against real music and speech, including quiet-to-loud transitions;
  zero clipped samples alone does not establish acceptable sound quality.
