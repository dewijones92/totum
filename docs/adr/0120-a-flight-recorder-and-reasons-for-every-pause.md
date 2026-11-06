---
title: A flight recorder, and a reason for every pause and cast drop
kind: adr
status: accepted
updated: 2026-11-06
---

# 120. A flight recorder, and a reason for every pause and cast drop

- Status: Accepted
- Date: 2026-11-06

## Context

Reports 20261101T170416 and 20261105T064409 could not say who paused playback: the `playback` line
read only "paused", which Media3 raises for a person's tap, another app taking audio focus, a
headset unplugged and the app's own `setPlaying(false)` alike. A cast session that dropped logged
nothing at all (`onSessionSuspended` was an empty override) and the end and start-failed lines carried
a bare number. And the morning's Exsurge wake-up outside active hours was lost: the in-memory trail
(`Breadcrumbs`) holds 30 minutes, floor 400 and ceiling 5,000 lines, dies with the process, and playback
chatter pushed the side-quest lines out within about 25 minutes of a normal session.

## Decision

- **Reasons are named words.** `PlaybackDiagnostics` logs `onPlayWhenReadyChanged` as
  `PLAY wanted` or `PAUSE wanted` with the Media3 reason spelled out (a play/pause request, audio focus
  lost to another app, audio becoming noisy, a remote controller, the end of the item, suppressed too
  long) and logs every `onPlaybackSuppressionReasonChanged`. The old `paused` line now carries the last
  request it follows. Two lines name who asked: the session's `onPlayerCommandRequest` logs a play/pause
  or stop command with its controller (the app, the notification or lock screen, or a package), and a
  pause the app makes through `togglePlayPause` or `setPlaying(false)` logs the three calling frames as
  `class.method:line` (retraceable, like the main-thread stalls of ADR 100). Vitals:
  `playback.pauseRequests`, `playback.playRequests`, `playback.lastPlayWhenReadyChange`,
  `playback.lastSuppression`.
- **Cast sessions say what happened and why.** `CastTarget` logs starting, resuming, suspended
  (with the cause: 1 the Cast service disconnected, 2 the network was lost), ending, ended and start or
  resume failed, each with the device. Error codes go through `CastCodes`: a code the SDK names
  (`CastStatusCodes`: 2005 APPLICATION_NOT_RUNNING, 2103 REPLACED, 2111 ERROR_URL_INSEURE ...) prints its
  name; any other prints `N unknown code` and the SDK's own category string for it. 2055, 2154 and 2155
  are **not** among the SDK's named constants, so they read as unknown codes until a report with the
  device's own text names them.
- **A longer lane for side quests and casting.** `Breadcrumbs` keeps lines tagged `Exsurge`,
  `DailyAlarm`, `reminders`, `cast`, `home`, `ask` and `speakers` in a second deque, up to 12 hours and
  1,000 lines, that playback chatter cannot evict. `snapshot()` merges both lanes in time order, so
  reports read as before.
- **A flight recorder on disk.** `Diag.saveTrail(kind, detail)` writes the merged trail as a small JSON
  snapshot (`filesDir/flight-recorder/<epoch>-<kind>.json`: kind, detail, saved-at, app version, and the
  newest lines that fit 200,000 characters, each message cut at 600). Triggers: a stall give-up
  (`StallWatchdog`), a cast session suspended, ended with a non-success code or failed to start or resume
  (`CastTarget`), and an Exsurge summons whose first call lands outside active hours or while Exsurge is
  off, not one the person asked for (`ExsurgeController`). The snapshot is written through a temporary
  file and renamed, the same kind is not saved again within two minutes (the cool-down says so in the
  trail), and the newest five are kept for seven days. The recorder is installed beside the log sink; the
  trigger lives in `Diag` so any module can raise one.
- **Reports carry them.** `CrashReporter` adds a `flightRecorder` array (newest first, each snapshot with
  its file name) to every report, up to 1,000,000 characters of it, so the worst report adds about 1 MB to
  the 8 MiB the ingest server accepts (trail at most about 1.5 MB, logcat 400 KB). Every report repeats the
  snapshots still on disk, so a report that failed to upload cannot take the only copy with it. Nothing
  new is read from token stores: the snapshot holds only lines already written through `Diag`.
- **Repeat mode in the state block.** `playback.repeatNow` is the effective mode (the one the kid lock
  forces) and `settings.repeatMode` the stored choice.

## Consequences

- A report after a pause can say whether it was a tap, the notification, a media key, a headset, focus
  loss or the app, and for the app, which frame.
- A cast drop names its cause where the SDK knows it, and the trail around it survives the process.
- Seven kept tags cannot evict each other fairly: a chatty one (`cast`) can push `Exsurge` out of the
  1,000-line lane; watch the lane in real reports before adding more tags.
- The snapshot copies the log text as it is, so a line that should not carry a secret is on the
  producer; the existing rule that tokens never reach `Diag` is what this relies on.
- No tests yet: this batch is code first, and the testing stretch adds `FlightRecorder` (cool-down,
  rotation, size bounds), `CastCodes`, the kept lane in `DiagTest`, and the `PlaybackDiagnostics` reason
  lines.
- Not verified on a device: the reason lines and cast callbacks need a real pause, a real unplug and a
  real Cast drop.
