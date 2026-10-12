---
title: Playback findings from the 0.1.601 phone reports
kind: todo
status: partly fixed locally — four verified repairs; buffering and boost remain open
area: playback/downloads
priority: high
requested: 2026-10-12
updated: 2026-10-12
---

# Playback findings from the 0.1.601 phone reports

Dewi requested that older reports be marked considered and the current-build reports investigated.
All five current reports identify `0.1.601`, commit `42273dc`, which matches both `main` and the
`v0.1.601` release tag at triage. The original triage below describes that source. Four fixes are now implemented locally, with
failing-before/passing-after JVM and device regressions and the required paired soak comparison.
The first release attempt, `v0.1.602`, exposed a local-podcast video regression in CI and is
withdrawn from stable updates while its correction is verified. Buffering and boost remain open.

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

### Controlled subtitle investigation — 2026-10-12

A scratch API-35 probe used healthy WAV media and sideloaded TTML captions from local HTTP
servers. Healthy captions reached READY in 174ms; delayed HTTP 429 captions remained buffering
at position zero for the full 30-second observation, after seven requests. A repeat reached READY
before the caption response arrived and observed only one further second, so it does not rule out
a later stall. Neither probe recorded selected tracks throughout the failure. This establishes a
useful reproduction candidate, not a completed diagnosis of both phone intervals.

The exact Media3 1.10.1 source shows that sideloaded TTML uses a progressive source with the load
error policy. Playback waits for its enabled renderers; a terminal subtitle stream error disables
that track, matching the phone's recovery immediately after the text-track error at 22:12:13.
Source archive: [official Media3 1.10.1 sources](https://dl.google.com/dl/android/maven2/androidx/media3/media3-exoplayer/1.10.1/media3-exoplayer-1.10.1-sources.jar).

Next: verify caption selection and observe the complete failure/recovery window against healthy
media. If reproduced, compare ordinary retries with immediate abandonment of HTTP 429 for the
item's explicitly known optional caption URI. Keep media-load retries unchanged and verify that
caption abandonment neither restarts the item nor loses its position. The scratch source/APK/logs
are in `~/claude-tasks/totum-playback-fixes-20261012/`; no probe or retry-policy change is shipped.

## Older reports considered by request

`20261008T220628-07f2b904` and `20261008T180001-1cad320a` (`0.1.593`), plus
`20261008T095903-bfb3844c` (`0.1.587`), were marked `triaged` with a note that Dewi requested
consideration by build age, without individual investigation or an assertion that they were fixed.

## Implementation and validation — 2026-10-12

- Player Watch/Listen and the playing row's mode action route the actual now-playing item through
  `QueueModeSwitch` and the shared queue. They retain the current position and invalidate the armed
  successor before rerouting. A different row still starts normally.
- `routeNow` selects sound-only playback for a full local copy in Listen mode. Both file variants
  use the same launcher path, clear stale stream options, and carry the exact position and pillar.
  The service applies the item's audio-only flag on each playlist transition; `hasVideo` reflects
  the selected video track, including armed local files. The controller applies video and subtitle
  choices together so preparing captions cannot undo Listen's video flag.
- Segment refresh returns the current list even when storage already matches it, or a lookup is
  unavailable. Cut files retain their existing no-live-skip behaviour. The controller also retains
  a refreshed list arriving while its asynchronous resume lookup is still pending.

Observed failures against the unchanged production source: two full-copy routing tests, the
concurrent segment refresh regression, four player/row mode-switch integration cases, and the
real queue's already-refreshed-store handover case. All of these pass against the local changes.
All three real-player `DownloadedVideoModeSwitchTest` cases failed on the baseline APK and pass
on the changed build: initial full-file Listen, Listen/Watch retaining the current position, and
segments arriving before the resume lookup finishes. The first changed-build attempt revealed that
applying subtitle choices undid the video's disabled flag; the corrected build applies them together.
All five `ShortsReelAdvanceTest` cases pass, with the shared reel inspected during the repeat-advance
case. The picture-loss case caught and corrected an extra replay introduced by the first Shorts
routing change; the reel now requests the picture once per playing item and leaves recovered audio alone. The full local gate and preflight pass. Both baseline and changed-build playback/queue soaks are complete; results are recorded below.

No boost or HTTP retry policy has been changed. Buffering causality remains open.

### Release-attempt correction

CI run 602 passed the build gates and published its APK before cancellation completed, but the
separate emulator suite reported three failures among 327 cases: both `MeteredAudioSwitchDeviceTest`
cases and the picture case in `SilenceIsReallyCutTest`. All eight original repair/Shorts device
cases passed there. The release was marked prerelease and `v0.1.601` restored as Latest.

The file route had incorrectly treated every podcast enclosure as a request to disable video.
Podcast enclosures can contain a picture. `PlayRoute.File.audioOnly` now carries the explicit
choice: an audio-only copy or Listen requests sound alone; a full podcast enclosure retains its
picture otherwise. Immediate and armed file playback use that same decision. A new player/queue
JVM regression failed before this correction; the gapless case checks that the prepared successor
receives the same picture choice. The full local gate and preflight pass. The three failed CI
picture cases and the eight original repair/Shorts cases pass on the corrected APK. The broader
18-case device run had one AAC timing miss (10380ms against a 10000ms limit); all eight silence
cases passed in the retained repeat. The corrected APK's 30-play soak recorded 23/30 passes versus
26/30 in the earlier baseline. The comparator flagged one regression: `p4NlCQi9x9Y` stalled in
2/3 plays versus 0/3 earlier. No startup-time or quality regressions were flagged. This blocked the
replacement push until the unchanged-source baseline was rerun; the failed comparison is retained.

The unchanged `v0.1.601` APK's repeat recorded 24/30 passes and reproduced the tennis underrun in
1/3 plays. Comparing this fresh baseline with the corrected APK reports **0 regressions under the
repository's configured thresholds**, which allow one additional failure per three plays. Startup
time and quality also satisfy the gate. This is not evidence that all media stalls are fixed. The
baseline repeat ran after the corrected soak, with the same AVD, account, VIDEO mode, alarms off,
automatic downloads off and cold-start/cache-clearing procedure; both headers list no other services.

The corrected queue soak records off gaps **2117/1333/2354ms**, three normal rebuild stalls and
no fatal 403s. On records **3/3 adopted, 0ms gaps, no stalls or fatal 403s**, matching the unchanged
baseline's queue result. Its header includes the disabled-rows alarm board and JS sandbox, as the
earlier queue runs did. The normal and queue JSONs, both comparator outputs, CI failure proofs and
device repeat are preserved in `~/claude-tasks/totum-playback-fixes-20261012/deployment/`.

### Paired soak results

Same API-35 AVD, official emulator 36.6.11 with SwiftShader, muted; VIDEO mode and automatic queue
downloads off; Exsurge and all daily alarm rows disabled. The alarm-board service remains running
even with disabled rows, including both queue runs. Baseline production source is the archived
`v0.1.601` / `42273dc`; both locally built APKs identify themselves as `0.1.0-dev`.

- Three passes over ten videos: baseline **26/30 PASS**, changed build **25/30 PASS**. The comparison
  reports **0 regressions under its configured thresholds**: no quality loss or startup-time
  regression. Occasional underruns and the recurring `edon5wb5Qsc` stream failure remain;
  this is not a claim that every play passed or that the phone's buffering issue is solved.
- Queue, gapless off: baseline gaps **1660/1677/1986ms**; first changed run
  **1834/1566/1674ms**, repeat **797/686/1888ms**. Each run recorded the normal rebuild buffer
  interval at each handover; none recorded fatal media 403s.
- Queue, gapless on: baseline **3/3 adopted, 0ms gaps, no stalls**. The first changed run
  recorded **2/3**, with no gap timing for the last handover. The required repeat recorded
  **3/3 adopted, 0ms gaps, no stalls**, matching the baseline. Both changed runs are retained.

Raw JSON reports, detailed queue-repeat events, comparison output, failing-before proofs and the
final tested APK checksum are in `~/claude-tasks/totum-playback-fixes-20261012/`. The initial changed
soak was superseded after the Shorts recovery correction; only the final APK's completed run is
used above. The emulator's saved preferences were backed up and restored after validation.


## Required validation

- Reproduce each confirmed defect with a failing regression test before changing its code.
- Exercise mode switches for streams, full local videos and audio-only copies, after item changes
  and gapless handovers, while asserting both the item identity and exact resume position.
- Exercise download handover with the two segment refreshes completing in either order; assert
  that the active player receives the stored list even when the lookup reports no database change.
- Apply the repository's playback and queue soak requirements before shipping playback changes.
- Judge a boost redesign against real music and speech, including quiet-to-loud transitions;
  zero clipped samples alone does not establish acceptable sound quality.
