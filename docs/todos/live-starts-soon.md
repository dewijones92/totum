---
title: A live stream that hasn't started says so, and calls you when it does
kind: todo
status: built; tests written
batch: A (2026-11)
priority: medium
updated: 2026-11-14
---

# A live stream that hasn't started says so, and calls you when it does

Report 20261104T171409: tapping "Elina Svitolina | Live Practice" before it began logged `UNPLAYABLE: This live event
will begin in a few moments` (signed-in TV and every yt-dlp client) and the peek "did not start", with nothing on
screen. Decided 2026-11-06: the row and the player say "🔴 Starts soon" (or the scheduled time when YouTube gives
one), and Totum checks politely through the YouTube gate until it goes live, then notifies you; Cancel in the
progress strip. Reuse the resolve refusal reasons and the live route (ADR 69).

## Built 2026-11-06

See [live-streams.md](../features/live-streams.md) and ADR 69 (amended). Open: the TV client's refusal shape is
unverified (the raw `playabilityStatus` is logged as `dewidebug upcoming live`); the player has no page for an item that
never started, so the label is on rows, the toast and the strip; the wait lives in the process, no WorkManager.

Tests still to add *(written in part 2026-11-14: see "Tests written 2026-11-14" below for what is done and what is still owed)*: `PlayerResponseParserTest` (LIVE_STREAM_OFFLINE with `scheduledStartTime`, the "few moments" wording
as UNPLAYABLE, a normal UNPLAYABLE staying `upcoming = null`), `LiveWaitScheduleTest` (before the start, 2/5/10 min steps,
give-up, too far ahead), `UpcomingLivesTest` (note, refresh, clear on resolve, 12 h prune), `VideoResolverTest` (a
yt-dlp refusal notes it; a resolve clears it), `InnerTubePlayerStreamsTest` (`liveCheck` Live / StillUpcoming / Unknown),
`LiveStartWatcherTest` (waits only for an upcoming tap, one wait per video, notifies on live, gives up after 6 h),
`ARefusedTapSaysSoTest` (a peek that fails now toasts; the tap carries its video id).

## Tests written 2026-11-14

`LiveWaitScheduleTest` (`:core:video`: the 2, 5 and 10 minute steps and where they change, the 30 second floor and 15 minute ceiling before a start, give-up at exactly six hours, the six hour limit on what is worth waiting for), `UpcomingLiveParserTest` (`:lib:innertube`: `LIVE_STREAM_OFFLINE` with and without a scheduled time, the microformat fallback, the "few moments" and premiere wording, ordinary refusals staying not upcoming), `LiveStartWatcherTest` (`:app`, virtual time: waits only for an upcoming tap, once per video, notifies once on live or on a recording, gives up at six hours and clears the registry, cancel frees the video). `LiveStatesTest`, `TheResolverTellsTheLiveRegistryTest` and `TheLiveCheckSaysWhatIsHappeningTest` already covered the registry, the resolver and `liveCheck`. `LiveStartWatcher` takes a `subjectFor` function instead of a `Context` so it can run on the JVM (no behaviour change). `ARefusedTapSaysSoTest` already covers a refused tap naming its item. Owed: a device check with a real upcoming stream.
