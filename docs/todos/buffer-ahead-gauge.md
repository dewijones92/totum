---
title: Show how far ahead the file is buffered
kind: todo
area: playback
priority: medium
status: shipped — seconds-ahead gauge on the scrub bar
updated: 2026-09-26
---

# Show how far ahead the file is buffered

Dewi, 2026-08-02: *"lets make it clear in the gui how much of the 'future' of the file is
downloaded???? some sort of gauge in seconds or??"*.

## Why it matters more for torrents than anything else

A YouTube stream either keeps up or it does not, and the answer arrives within a second. A
torrent is different: it depends on seeders, on which pieces the swarm happens to hold, and on
whether you have just seeked into a region nobody has sent yet. Report 0.1.317 shows exactly
that shape — a stall of 20 seconds with **360ms buffered**, recovering, stalling again. Right
now the app shows a spinner for all of it, so "will this settle down or should I pick something
else?" is unanswerable from the screen.

Seconds, not a percentage. Ahead-of-playhead is what decides whether you can keep watching; a
percentage of a 1.7GB file says nothing about the next ten seconds.

## Where the number comes from

Two sources, and they answer different questions:

- **The player.** `Player.getBufferedPosition()` minus the current position is what ExoPlayer
  actually holds and can play without asking for more. This is the honest "will it keep going".
  Already available — `PlaybackAnalytics` sees the loads that fill it, and the stall watchdog
  already reads the buffered figure (`STUCK (360ms buffered)` in the report).
- **The server.** TorrServer reports `preloaded_bytes` and per-file piece state, so it can say
  what it holds beyond what the player has taken. Useful, but a second network call per tick and
  it describes the Pi rather than the phone.

Start with the player's own number. It needs no new I/O, it is the one that governs playback,
and if it turns out to be insufficient the server's view can be added behind the same UI.

## Shape

- A thin secondary track on the scrub bar showing buffered-ahead — the convention every player
  uses, so it needs no explanation.
- Plus **seconds in words** when it is low, because a bar an eighth full does not read as "three
  seconds left". Something like `12s buffered` under the controls, and a distinct state when it
  is falling rather than merely small.
- Say when the buffer is going BACKWARDS. That is the difference between "it will catch up" and
  "pick something else", and it is the whole question being asked.

## Careful of

The position ticker already drives skip-segment enforcement; adding a second timer for this
would be a second clock disagreeing with the first. Reuse the existing one.

And it must not become chatty in the diagnostics buffer — the log-volume rule applies, so any
trail from this is counted and periodic, never per tick.

## Status corrected, 2026-08-04

Said "requested" while `BufferAhead` (9 unit tests) had been shipped and wired into
`ui/player/SeekBar.kt`. Corrected against the code.

## The gauge made the scrub bar flicker, 2026-09-26

Dewi, report 0.1.540 (Pixel 7, a downloaded item in Listen mode at 2x): *"sumin flickering on the
player?"*, then *"its like sumin is flickers on the progress bar or near it"*, and that it had been
there long before the UI overhaul. It was this gauge, since it shipped on 2 August.

Two faults, both in how "show it" was decided:

- **"Falling" compared against one tick ago.** ExoPlayer loads in bursts and drains in between,
  so during ordinary playback the whole seconds went 49, 49, 48, 48… The label appeared on each
  tick where the count dropped and vanished on the next, where it was equal. It blinked once a
  second (twice at 2x), and because it was a line of its own between the bar and the
  timestamps, every blink pushed the timestamps down and back up.
- **A large buffer draining was treated as news.** Draining from the player's upper target
  towards its lower one is the healthy cycle between loads, not a warning. On the device the
  last seconds of an item were also shown as "falling behind", when the file simply ends there.

Now (`BufferGauge` in `BufferAhead.kt`, one instance per scrub bar):

- shown only after the buffer has been at or under 10s for 2s, so the empty moment after a seek
  or an item start is never shown;
- once shown, it stays until the buffer is clear of 15s (hysteresis), so a buffer hovering at
  the mark cannot blink it;
- never when the buffer reaches the end of the item;
- "falling" holds across ticks whose whole seconds are equal, so it stops flipping between
  "falling behind" and "buffered ahead";
- it sits in the middle of the timestamp row, so appearing moves nothing;
- every show and hide is logged under `[buffer]` with its reason.

Tests: `BufferGaugeTest` replays tick-by-tick playback (a healthy drain at 2x, a buffer running
out, the moment after a seek, a buffer hovering at the mark, the end of an item, the next item).
Three failed against the old rule (shown for 149 ticks during healthy playback, 8 blinks while
running out, 23 ticks after a seek), and the end-of-item case failed against the first fix. On
the emulator, the old build moved the timestamp row from y=2142 to 2179 when the label appeared
in an item's last seconds; the fixed build kept it at 2142 through the same stretch.
