---
title: Queue items seem to finish a few seconds early
kind: todo
status: closed 2026-10-08 — phone reports confirm skip-silence cutting trailing quiet (1.1 s of media in recent builds); Dewi chose to leave it
area: playback
updated: 2026-10-08
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

## Phone evidence (reports 0.1.573 to 0.1.587, read 2026-10-08)

50 distinct `item-end` lines across 9 reports; 7 items played to their end, all with skip-silence Smart at 2x:

| Build | Item | Jumped in the last 10 s | Note |
|---|---|---|---|
| 0.1.573 | PQw0TRzpCkk | 4,219 ms | downloaded audio; player 1,367 s vs listed 1,508 s |
| 0.1.573 | pJljViiUEPw | 3,645 ms | downloaded audio; player 1,605 s vs listed 1,697 s |
| 0.1.581 | APHMTbD6ZPo | 1,121 ms | |
| 0.1.581 | 1yKxFGhyDFE | −180 ms | one SponsorBlock skip earlier |
| 0.1.581 | edon5wb5Qsc | 1,153 ms | |
| 0.1.587 | rGUzoHunuV8 | 1,120 ms | |
| 0.1.587 | WrCjAAl9okA | −402 ms | |

- The few-seconds effect is skip-silence cutting quiet at the end: about 1.1 s of media in recent builds,
  about half a second of real time at 2x.
- The two items that ended 1.5 and 2.5 minutes before their listed length were downloads made before
  ADR 16, with sponsor segments cut out of the file. New downloads keep them.

## Decision (Dewi, 2026-10-08)

- **Leave it.** Cutting trailing silence is skip-silence working; the `item-end` line says so.
- **Leave old sponsor-cut downloads** as they are; they leave as they are finished and deleted.
