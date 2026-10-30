---
title: A stalled main thread logs where it was
kind: adr
status: accepted
updated: 2026-10-30
---

# 100. A stalled main thread logs where it was

- Status: Accepted
- Date: 2026-10-30

## Context

Dewi, 2026-10-30: *"There are still some lags in the app … the scrolling was frozen for like a few
seconds. Like there are weird random lags like that in the UI."* His report (0.1.762) showed a
two-second gap in the log after every queue change, and nothing else: no report carried frame
timing or the main thread's state, so a freeze could only be inferred from silence and then hunted
for on the emulator, which did not reproduce it until his Wi-Fi-only setting was copied over. The
cause (the queue auto-downloader asking the network gate about each of 5,739 items on the main
thread) is fixed alongside this; the next freeze should name itself.

## Decision

- `MainThreadWatch` (a daemon thread, started first thing in `TotumApplication.onCreate`) posts a
  ping to the main looper every 100 ms while at least one activity is started, and parks while none
  is, so it costs nothing in the background.
- A late ping makes it sample `Looper.getMainLooper().thread.stackTrace` every 100 ms until the ping
  runs. `MainThreadStalls` (pure, unit-tested) tallies where each sample was: the top frame plus up
  to six frames below it that are not the platform's (`java.`, `android.`, `com.android.` …), so a
  binder call reads with the app code that made it.
- A stall of 400 ms or more is one `[main]` warning with its length, sample count and the three
  places seen most; stalls within 10 s of a logged one are counted into the next line. Vitals:
  `main.stalls`, `main.worstStallMs`, `main.lastStall`.
- Release frames are R8 names, retraced with the `totum-mapping.txt` each release already ships.

## Consequences

- A freeze in a hand-sent report now says where it happened, without a trace or a reproduction.
- A shorter hitch (under 400 ms) is not reported; jank, as opposed to freezing, still needs a trace.
- An ANR-length stall is logged when it ends; one that ends in the process being killed is not.
- On the swiftshader emulator, render-thread waits appear as stalls; read the top frame before
  blaming app code.
- Tests: `MainThreadStallsTest` (5).
