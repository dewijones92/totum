---
title: A daily mobile-data budget
kind: feature
status: built 2026-11-06 (tests to follow; counter not yet seen on a phone)
area: playback
updated: 2026-11-06
---

# A daily mobile-data budget

Dewi, 2026-11-06. Totum counts the bytes it uses on a metered connection each day; past the limit, video plays as
sound only on mobile data. Decisions in [ADR 129](../adr/0129-periodic-jobs-remember-their-last-run-and-data-is-counted-from-the-uid.md).

- **Counting** (`MobileDataMeter`, `app/.../mobiledata/`): every 5 s, the change in the app UID's
  `TrafficStats` rx + tx bytes is added to today's total when the active connection is metered. Resets at local
  midnight; persisted with the counter, so a restart loses nothing. Covers playback, downloads, the Pi, yt-dlp and
  ffmpeg alike.
- **The limit** (Settings → Default video quality, "Daily mobile-data limit"): Off, 500 MB, 1 GB (suggested), 2 GB,
  5 GB. **Off by default.** The row also says today's use ("📶 Today: 312 MB of 1 GB mobile data used", or
  "📶 Data limit reached: …").
- **Over the limit** (`MeteredAudioSwitch`, no second switch): on a metered connection a playing video goes to sound
  after the usual 15 s hold, even one the person put the picture back on, and a notification says the limit was
  reached. Turning the limit off or raising it, or Wi-Fi, ends it; the picture is not put back by itself (as ever).
- **The chip** "📶 Data limit reached" in the active-modes row ([active-modes](active-modes.md)) while the limit is
  reached and the connection is metered.
- **Not done:** downloads are counted but not blocked; Stats has no line for it (it reads an event log, not this
  counter); the first 15 s of a video started over the limit plays as video.

## Diagnostics

`mobiledata` lines: the start (today's count and the limit), each change between metered and unmetered with the
day's total, every 25 MB, the moment the limit is reached, a new day, and a warning if the phone reports no byte
counters; `data` lines from `MeteredAudioSwitch` carry `[dailyLimitReached=…]`; report vital `data.mobileTodayKb`.

## Tests still to write

`MobileDataMeter.sample` with fake counter/metered/day (metered vs unmetered deltas, reboot where the counter drops,
midnight reset, unsupported counter, persistence round trip); `reachedLimit`/`holdingBack`; `MeteredAudioSwitch` over
the limit (switches again after a hand-restored picture, announces the limit); `activeModesOf` with `dataLimit`;
Settings row choices. A phone check that the counter tracks a known download on mobile data.
