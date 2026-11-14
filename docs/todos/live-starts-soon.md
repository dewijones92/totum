---
title: A live stream that hasn't started says so, and calls you when it does
kind: todo
status: built 2026-11-06, Batch A (tests owed, not seen on a device)
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

Tests still to add: `PlayerResponseParserTest` (LIVE_STREAM_OFFLINE with `scheduledStartTime`, the "few moments" wording
as UNPLAYABLE, a normal UNPLAYABLE staying `upcoming = null`), `LiveWaitScheduleTest` (before the start, 2/5/10 min steps,
give-up, too far ahead), `UpcomingLivesTest` (note, refresh, clear on resolve, 12 h prune), `VideoResolverTest` (a
yt-dlp refusal notes it; a resolve clears it), `InnerTubePlayerStreamsTest` (`liveCheck` Live / StillUpcoming / Unknown),
`LiveStartWatcherTest` (waits only for an upcoming tap, one wait per video, notifies on live, gives up after 6 h),
`ARefusedTapSaysSoTest` (a peek that fails now toasts; the tap carries its video id).

Test status check (2026-11-14): of the tests listed as still to add, those that exist now are `ARefusedTapSaysSoTest` and, for the
registry, `LiveStatesTest`, `LiveStatesPersistenceTest`, `TheResolverTellsTheLiveRegistryTest` and
`TheLiveCheckSaysWhatIsHappeningTest` (the live check, upcoming and unknown). Not found in the tree: a `PlayerResponseParserTest` case for
`LIVE_STREAM_OFFLINE` / "will begin in a few moments", `LiveWaitScheduleTest`, `LiveStartWatcherTest`. So "tests owed" stays true for the
parser, the wait schedule and the watcher.
