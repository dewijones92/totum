---
title: The morning job (🤖 For you and the 🌅 digest)
kind: feature
status: built 2026-11-07, Batch A (JVM tests written 2026-11-07; not yet seen on a device)
area: ai
updated: 2026-11-07
---

# 🌅 The morning job: 🤖 For you and the digest

One job, at about 07:30 every day, with two switches (Settings → Morning, both off by default): a daily **🤖 For you**
playlist of videos and podcasts from outside Dewi's subscriptions, and a **🌅 digest** notification that says in two lines
what is new in his groups. One notification tells him both. Decisions, dry runs and limits:
[ADR 143](../adr/0143-for-you-and-the-morning-digest-are-one-morning-job.md); the briefs, tests and what is still owed:
[for you](../todos/for-you-feed.md), [morning digest](../todos/morning-digest.md).

## What it does

1. **Settings → Morning**: 🌅 Morning digest and 🤖 For you each morning, each with its own switch, a "Last ran 3 hours
   ago: 7 picks · 4 new" line and **▶️ Run the morning job now** (shown in the progress strip).
2. **For you**: Haiku is given what Dewi subscribes to, plays most, starred and liked (and the picks of the last two
   weeks, so nothing repeats) and names up to 6 YouTube videos and 4 podcast shows he does not follow. The app's own
   searches find each one (a video not from a followed channel, not a Short, not members-only; a show's newest episode);
   what cannot be found is left out. The result is added to the **"🤖 For you"** playlist (Library → playlists) and the old picks are removed after, so the
   list is never empty in between.
3. **Digest**: while it is on, a new video in a group no longer sends its own notification. It goes into an inbox. At 07:30
   Haiku turns the group names and titles into two short lines (a title the spoiler shield hides is sent hidden); if
   the home server cannot be reached the lines are plain ("🆕 Tennis: Fritz v Fils Highlights and 3 more"). A group's 🔔
   switched off still keeps it out of the digest.
4. **One notification**, "🌅 Your morning": the digest lines and "🤖 7 picks for you are in Library", or "⚠️ 🤖 For you could
   not be filled this morning: …" when For you failed. Nothing new, no picks and no failure means no notification.

## Seam

- `core/data/morning/`: `MorningRun` (the whole run, with injected search and playlist steps so it is testable),
  `MorningStore` (the switches, the inbox, recent picks; `morning.json`), `MorningRules` (what is kept after the call),
  the request and answer types. `core/data/ask/TasteOf.kt`: `tasteOf`, moved from the Ask screen's package, with starred and
  liked titles added.
- Pi: `cli/.../serve/tasks/MorningPrompts.kt`; routes `POST /ai/for-you` and `/ai/morning-digest`, callers `for-you` and
  `digest`, no tools.
- App: `jobs/MorningWorker.kt` (a `PeriodicJob`, daily, first run at the next 07:30), `ai/MorningFeature.kt` (`MorningJob`
  = run + notification; the taste, the search for a pick, the playlist), `AppContainer.groupNewsNotify` (digest on: into
  the inbox), `ui/settings/MorningSettings.kt`.
- Reused: `PeriodicJob`/`LastRuns` ([ADR 129](../adr/0129-periodic-jobs-remember-their-last-run-and-data-is-counted-from-the-uid.md)),
  `AskCopies` and `Suggestion` (the app's searches and the pick's shape), `HousekeepingNotifier`, `HttpAiJobs`, the local
  playlist store, the spoiler shield's titles, per-group switches ([ADR 118](../adr/0118-every-new-content-notification-can-be-switched-off.md)).

## Limits, honestly

- The job runs at about 07:30 (WorkManager does not promise the minute, and not while the phone is off); the time is not
  a setting yet. After each run the work is re-armed for the next 07:30 so it does not drift by the length of a run.
- The digest tells up to 40 titles a group; entries past that wait for the next one.
- Haiku has no web in this job, so a pick is a name from its memory that the app's search must then find; a made-up
  one is simply not found.
- The liked page is one YouTube request per run, through the same polite gate as every other call.
- `morning.json` and the switches are not in the Drive backup yet.
- The subscription "new uploads/episodes" notifications (ADR 118's other half) are not replaced by the digest.

## Diagnostics

`morning` lines: the run's switches, the taste counts, what Haiku gave and what the checks kept, each pick found or left
out and why, each inbox entry added, the digest sent (and whether AI or plain lines), the final result. On the Pi: `[morning]`
lines with the model's picks and what was kept.
