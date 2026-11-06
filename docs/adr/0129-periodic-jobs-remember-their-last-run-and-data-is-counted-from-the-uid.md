---
title: Periodic jobs remember their last run, and mobile data is counted from the app's own byte counters
kind: adr
status: accepted
updated: 2026-11-06
---

# 129. Periodic jobs remember their last run, and mobile data is counted from the app's own byte counters

- Status: Accepted
- Date: 2026-11-06

## Context

Batch A adds three weekly or daily housekeeping jobs (queue tidy-up, a test-read of the Drive backup, a mobile-data
budget) on top of three WorkManager workers that each hand-copied the same boilerplate (`NewContentWorker`,
`NewUploadsWorker`, `GroupNewsWorker`: unique name, constraints, KEEP, a start log, a catch that logs and returns
success). None of them said when it last ran, so "is it working?" could only be answered by waiting.

The budget needs a count of the mobile-data bytes Totum used today. Bytes move through at least five unrelated paths:
Media3's `DefaultDataSource` (`HttpURLConnection`), the SABR data source (its own `HttpURLConnection`), the shared
OkHttp pool (downloads, feeds, the Pi), yt-dlp and ffmpeg inside the embedded Python, and the cast server.

## Decision

- **`PeriodicJob(name, interval, network)`** (`app/.../jobs/PeriodicJob.kt`) is the one place a periodic worker is
  scheduled and run. `schedule` enqueues unique work with KEEP; `run` logs the start, turns a `JobOutcome`
  (`Done`, `Failed(retry)`, `Skipped`) into the WorkManager result, logs and records a thrown exception as a failure,
  and rethrows cancellation. The three existing workers moved onto it with their behaviour unchanged (same unique
  names, intervals and constraints; the two that skip under instrumentation still do).
- **`LastRuns`** keeps, per job name, when it last ran, whether it succeeded, a detail line and when it last
  succeeded, in its own prefs file (`LastRunStore`, with an in-memory fake). Every surface shows it through one
  formatter (`agoOf` / `Context.agoText`: "just now", "5 minutes ago", "3 hours ago", "2 days ago"), so a row says
  "✅ Backup checked 2 days ago" the same way everywhere. A skipped run records nothing.
- **Mobile data is the app UID's byte counter, sampled.** `MobileDataMeter` reads `TrafficStats.getUidRxBytes +
  getUidTxBytes` for the app's own UID every 5 s and adds the difference to today's total when the active connection
  is metered at that moment. It resets at local midnight and persists the total and the counter (so bytes between the
  last sample and a process death are not lost). One counter covers every path above, including Python and ffmpeg,
  which no hook inside the app could see; the price is a 5 s attribution error at a Wi-Fi/mobile change, and
  counting traffic that is not Totum's playback (feeds, the Pi) as the person's mobile data, which it is.
- **The limit reuses `MeteredAudioSwitch`.** It gains `overLimit` and `announceOverLimit`: when the daily limit is
  reached and the connection is metered, a video that is playing goes to sound, even one the person put the picture
  back on by hand (the once-per-stretch stand-down does not apply over the limit), and the notification says the limit
  was reached rather than that mobile data was detected. The limit is a setting (`dataLimitMb`, **off by default**,
  1 GB suggested). "📶 Data limit reached" is one more `ActiveMode`, on when the limit is reached and the connection
  is metered.
- **The tidy-up and the backup check are weekly `PeriodicJob`s** that record their outcome in `LastRuns` and tell the
  person through one `HousekeepingNotifier` (its own low-importance channel): the tidy-up only when something moved,
  the backup check only when the copy cannot be read.

## Consequences

- A new periodic job is a `PeriodicJob` constant, a worker whose `doWork` is `JOB.run(lastRuns) { ... }`, and one line
  in `TotumApplication`; its row reads `LastRuns.observe(name)`.
- The count has a 5 s error at each Wi-Fi/mobile change. Bytes the playback cache replays from disk are not network
  traffic and are not in it, which `PlaybackAnalytics`' loaded-byte total could not say.
- Over the limit, a video started on mobile data plays for the metered hold (15 s) before it goes to sound.
  Downloads are counted but not blocked by the limit.
- `TrafficStats` is not available on every device (it reports `UNSUPPORTED`); the meter then logs once and counts
  nothing, so the limit never fires rather than firing wrongly.

## Tests still to write

See [mobile-data-budget](../features/mobile-data-budget.md), [queue-tidy-up](../features/queue-tidy-up.md) and
[backup-checked](../features/backup-checked.md).
