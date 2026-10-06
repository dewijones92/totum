---
title: Queue items seem to finish a few seconds early
kind: todo
status: instrumented 2026-10-06; leading cause found on the emulator (skip-silence cuts a trailing silence, and the clock jumps ~5 s at the end); awaiting Dewi's call and a phone report
area: playback
updated: 2026-10-06
---

# "Sometimes items in the queue finish a few seconds early"

Dewi, 2026-10-06: *"sometimes items in the queue finish a few seconds early for some reason???"*
Asked what it looks like, he could not say (it was "noticed in passing").

## What the reports already said

Every end logged in the September and October phone reports (19 of them) reached the player's own
duration: `ended at 520549ms of 520541ms`, and so on. At the player's level nothing ended short, so
the early end is something the listener perceives rather than an early `ENDED`. Skip-silence was on
in 6 of the 18 reports with something playing, and speeds of 1.5x and 2x were in use.

## The instrument (shipped 2026-10-06)

`ItemEndWatch` writes one `item-end` line per item and keeps the last five in the vital
`playback.lastEnds`, so they survive a full event buffer:

```
item-end <id> reason=ended at=25006ms player=25000ms listed=-1ms
  | last 9780ms of media took 5048ms playing (5048ms at 1.0x): jumped 4732ms
  | skipSilence=true mode=SMART speed=1.0 sponsorSkips=0 (in last 10s: 0)
```

- **jumped** = media time in the last 10 s minus the time spent playing (paused time is excluded)
  × speed. Any cause of an early end shows up there: a silence cut, a SponsorBlock seek, or a stream
  shorter than its clock.
- **listed** is the item's catalogue duration, so "it ends before the length in the list" is
  answerable too. **reason=replaced** marks an item left before its end (a skip, or the queue
  moving on).

## Evidence (emulator, 2026-10-06, `totum-api35`)

Fixtures: 20 s of the bundled LibriVox reading, followed by a tail, played through the real
controller.

| Tail | Skip-silence | Jumped in the last 10 s |
|---|---|---|
| 8 s quiet music bed (−48 dB) | off (control) | −4 ms: the instrument reads zero |
| 8 s quiet music bed (−48 dB) | Smart | 446 ms |
| 8 s louder music bed (−31 dB) | Smart | 264 ms |
| **5 s of silence** | Smart | **4,732 ms** |

So a music outro is played, but a **trailing silence** is cut to 40 ms like any pause. The
clock then leaps ~5 s as the item ends. That matches "finishes a few seconds early" exactly, with
no content lost. It is the leading explanation, not a proven one: it applies only while skip-silence
is on, and no phone report yet carries an `item-end` line.

## Open (Dewi's call)

- Leave it: cutting trailing silence is skip-silence working, and the line now says so.
- Or never cut in the last ~10 s, so the clock runs out naturally (and outros keep their pauses).

Next phone report after an early end: read `playback.lastEnds`.
