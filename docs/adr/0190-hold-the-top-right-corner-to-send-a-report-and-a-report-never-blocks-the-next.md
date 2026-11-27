---
title: Hold the top-right corner to send a report, and one report never blocks the next
kind: adr
status: accepted
updated: 2026-11-27
---

# 190. Hold the top-right corner to send a report, and one report never blocks the next

- Status: Accepted
- Date: 2026-11-27
- Builds on: [ADR 159](0159-a-crash-screen-and-a-state-dump-for-every-report.md) (the state dump linked to every report),
  [ADR 146](0146-gpl-3-and-reports-go-to-the-pi-only-when-signed-in.md) (reports go to the Pi only when signed in, GitHub otherwise),
  [ADR 76](0076-kid-lock-pins-the-app-to-a-bare-video.md) (kid lock), [ADR 43](0043-every-state-string-starts-with-an-emoji.md)
- Backlog item: [corner-hold-report](../todos/corner-hold-report.md)
- Reports: 20261121T063645 (the gesture), 20261121T061944 (a second report could not be sent)

## Context

Dewi, 2026-11-21: sending a screenshot and diagnostics is hard work (Settings, Send diagnostics, Add screenshots, a gallery
picker, back). He asked for an overlay on the whole app: hold the top-right of the screen for a few seconds, the app takes a
screenshot of what he is looking at, captures the diagnostics as Settings does, and opens a window for free text.

The same day, after one diagnostics report he could not send another until the app was killed. The cause is in
`DiagnosticsRow`: a `rememberSaveable` flag `sent` was set after the first report, never reset, and the row was
`clickable(enabled = !sent && !saving)`. A second flag, `diagnosticsSaving`, was a file-level `MutableStateFlow` reset only on
the success path, so a throw would have wedged it the same way. The Quick Settings tile has no such flag, so it was unaffected.

## Decision

**One report path.** `ReportSubmitter` (`app/.../diagnostics`) is the only way a hand-sent report leaves a screen. Settings,
the Quick Settings tile and app-icon shortcut, the speech-boost feedback box and the new corner hold all call
`reportSubmitter.submit(note, screenshots, from)`. It runs the existing `AppContainer.sendDiagnostics` (report written by
`CrashReporter`, uploaded by `DiagnosticsUploader`, state dump linked by id, redactor untouched), shows the progress strip, and
tells the person the outcome by toast: `📨 Report sent`, `❌ Couldn't send, will retry` or `❌ Couldn't save the report`.
`sendDiagnostics` gained an `onDone(ReportFate)` callback so the outcome is the upload's real result, not "saved".

**A report never blocks the next.**
- No flag disables a row after a send. The Settings row keeps showing the last outcome in its title and stays tappable;
  "saving" is a counter (`inFlight`) decremented in a `finally`, so a failure cannot leave it set. Submissions are not refused or
  queued behind each other.
- `DiagnosticsUploader.upload` now holds a mutex: two sends started together used to read the same pending file and post the
  report twice (a unit test saw 4 requests where 2 were right). The second now sees only what is left.

**The gesture.**
- A press that starts in the top-right corner (48 dp wide; 48 dp tall below the status bar, since the status bar's own height
  would otherwise leave a sliver) and stays there, within touch slop, for **1.5 s** fires. A haptic tick marks it.
- **Hold time decides, never the tap.** The recogniser watches in the `Initial` pointer pass and consumes nothing until it
  fires, so buttons in that corner (back, overflow, cast) work exactly as before; a press shorter than 1.5 s, a drift past slop, a
  second finger or leaving the corner abandons it silently. Once fired, the rest of that touch is consumed so the button
  underneath does not also click on release.
- **The screenshot is taken first**, by `PixelCopy` of the activity window (so video `SurfaceView`s are not black), which covers
  the full player and the Shorts reel as they live in the same window. Then the bottom sheet opens: note box (focused, so the
  keyboard's dictation mic is one tap), a preview of every screenshot, **📎 Add screenshots** (the existing picker, so a second
  picture can be added), and Send. An empty note still sends with a fallback line, as Settings does.
- Dialogs and bottom sheets are other windows, so a hold while one is open takes no picture of it; the sheet is also ignored
  while another report is open.
- A setting, **Hold the top-right corner to send a report** (Settings, Diagnostics), on by default.

**Recorded exceptions (ADR 108).**
- **Not in the kid lock.** A toddler holding corners would fill the report box with noise and could reach a dialog that opens
  outside the lock. The gesture is off while `KidLock.locked` and a sheet already open closes when the lock is switched on. Kid
  TV mode (the TV is locked, the phone is Dewi's) keeps it.
- **Only in `MainActivity`.** The alarm, ring, crash and quick-alarm screens are other activities and are not wrapped. The
  Quick Settings tile covers those.
- **Signed out (or home reports off): GitHub, no picture.** Those reports are text-only and public, so the gesture opens the
  same GitHub preview dialog Settings opens and takes no screenshot.

## Consequences

- One path for five entry points, so a change to the report (a new field, a new redactor rule) is made once.
- Logs, all prefixed `dewidebug`: the gesture's state when it changes (setting, kid lock), a fired hold, an abandoned hold that
  lasted 400 ms or more with why and how long, the window copy (size, or why not), the sheet opening (picture or not), a hold
  ignored because a report is open, each submission (note length, picture count, entry point, how many in flight) and its fate.
  Short taps in the corner are not logged, so ordinary use does not fill the report buffer.
- A hold is read from pointer events, so on a phone whose status bar swallows touches the usable zone is the part below it; the
  zone is extended by the status-bar height for that reason, not because the bar is touchable.
- Not covered by automation: the haptic tick, and PixelCopy of a live video `SurfaceView` (the emulator's SwiftShader surface).
