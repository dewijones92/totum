---
title: Live streams
kind: feature
area: playback
status: built 2026-11-06 (starts-soon wait unpushed, tests owed)
updated: 2026-11-06
---

# Live streams

Plays live YouTube from a manifest Totum builds from the live head ([ADR 69](../adr/0069-live-streams-play-a-manifest-totum-builds.md)),
at 1x with nothing cut ([live-plays-at-1x.md](../todos/live-plays-at-1x.md)). Listed with the videos and tagged
"🔴 LIVE" ([shorts-in-the-feed.md](shorts-in-the-feed.md)).

## A live stream that has not started (2026-11-06)

Report 20261104T171409: tapping a live event before it began logged `UNPLAYABLE: This live event will begin in a few
moments` and nothing showed. Now:

- **The answer is typed.** `PlayerResponseParser` reads an upcoming live out of a refused `/player` response (status
  `LIVE_STREAM_OFFLINE`, an `offlineSlate`, or the words "live event will begin" / "premieres in") into
  `PlayerResult.Unplayable.upcoming` with the scheduled start when the response gives one (`scheduledStartTime`, else the
  microformat's `liveBroadcastDetails.startTimestamp`). yt-dlp's refusal is recognised by its words, with no time.
- **One registry.** `UpcomingLives` (`:core:video`) holds each upcoming video by id, written wherever a refusal is seen
  (signed-in TV client, anonymous player, yt-dlp), cleared when the video resolves or a check finds it live, and pruned
  after 12 h.
- **The row says so.** `MediaItemRow` shows "🔴 STARTS SOON" (or "🔴 STARTS 20:30" / with a date when it is not today)
  instead of "🔴 LIVE", in every list, from `LocalStartsSoon`.
- **The tap says so.** A refused tap (queue, or a peek, which now says why too) toasts "🔴 “…” has not started yet.
  Totum will tell you when it is live." (with the time when known; "too far ahead to wait for" beyond 6 h).
- **Totum waits politely.** `LiveStartWatcher` shows a "⏳ Waiting for “…” to go live" line with Cancel in the progress
  strip and checks through `VideoResolver.liveCheck` (the signed-in TV client, then the anonymous player: two gated
  requests, no yt-dlp). `LiveWaitSchedule`: with a scheduled time, one check a minute before it (never longer than 15 min
  between checks); otherwise every 2 min for 5 checks, every 5 min to the 15th, then every 10 min; gives up after 6 h.
- **It calls you.** When a check finds it live, a notification "🔴 … is live" with **▶️ Play** opens Totum on the watch
  link, which queues and plays it as a shared link does. Giving up posts "⌛ Stopped waiting for …".
- **Logs** (`[live]`): every note, every check and its outcome, every decision not to wait (`dewidebug upcoming live`
  logs the raw `playabilityStatus`, so the next report shows the real shape).

Not covered: the waiting lives in the app process (no WorkManager), so a killed process stops it; a signed-out phone
with the anonymous player bot-blocked can only say "could not tell" until it gives up; the player has no page for an
item that never started, so "starts soon" shows on rows, the toast and the strip.
