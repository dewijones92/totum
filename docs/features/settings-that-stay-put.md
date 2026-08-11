---
title: Settings that stay put
kind: feature
status: shipped
area: playback
updated: 2026-08-09
---

# Nothing changes unless you change it

Dewi, 2026-08-09: *"if I select 1.5 speed for example, I don't want that ever to change unless I
manually change it … I want everything to be maintained going to the next video in auto play"*.

Four separate things were breaking that promise, in three different ways. They are grouped here
because to a person they are one complaint.

## What carries over now

| What | Carries | How |
|---|---|---|
| Speed | across items, and across a silent stretch | stored; re-applied and re-declared on every item |
| Volume boost | across items | stored; re-applied on every item |
| Skip silence | across items | lives on the service, not the item |
| Captions | across items | preference re-asserted on every item |
| Quality (height) | across items, for the sitting | `StreamChoices` |
| Audio track (language) | across items, for the sitting | `StreamChoices` |
| Brightness | within fullscreen, for the sitting | `ChosenBrightness` — see below |

## The speed had two separate bugs

**The button showed a rate nobody chose.** `PlaybackState.speed` reported the *player's* rate, and
skip-silence races through dead air by raising it to 4x (capped at 8x). Speech goes quiet between
sentences every few seconds, so on any video with skip-silence on, the speed button flicked to
"4x" and back for the whole item. It now reports the rate the **user** chose, which is the only
number that means anything to them; the racing is meant to be invisible and now is.

**A rate chosen mid-silence was thrown away.** The service inferred the user's rate from the
player's own callback and *skipped the inference while racing*:

```kotlin
if (!inSilence) userSpeed = playbackParameters.speed   // the bug
```

So changing speed during a silent stretch left the store holding 1.5 and the racer still believing
1.0 — and the moment speech returned, the player was put back to 1.0. Given how often speech is
silent, this was reachable by simply changing speed at the wrong moment.

Told, not inferred, now: `ACTION_USER_SPEED` carries it over the session, sent on every change
**and on every item**, and [`SilenceRacer`](../../core/playback/src/main/kotlin/com/dewijones92/totum/playback/SilenceRacer.kt)
owns the arithmetic so it can be tested without a device.

## Quality and audio track were per-video

Defensible in isolation: a new video has different streams, so "the chosen stream" cannot
literally survive. But in a queue that meant a deliberate 720p or a deliberate German track lasted
exactly one item, which is an implementation detail leaking into the product.

`StreamChoices` holds the *intent* — a height and a language — rather than a stream, and both are
re-applied to whatever the next video happens to offer:

- **Height**: the tallest rung at or below yours; the **smallest** on offer when the video is
  published only above it. Falling back to the tallest would turn "I asked for 480p" into 4K on
  any video without a low rung.
- **Language**: applied at *resolve* time, because the resolver picks streams before the launcher
  ever sees them. One instance is shared by both, which is why it is constructed in `AppContainer`
  and passed to each.

The network's data-saver cap still wins over both. It is a limit, not a preference.

Held for the sitting, not persisted — the same call as the brightness. A quality picked while
tethered in a car should not still be capping things a week later on Wi-Fi.

## Brightness is the exception, deliberately

It does **not** carry outside fullscreen. Dewi, same day: *"the brightness needs to be only applied
if the video has been played in full screen. Otherwise it needs to use my phone brightness …
similar to PipePipe"*. The choice is remembered for the sitting, so going back into fullscreen
returns to it; the override is dropped the moment you leave. See
[`ChosenBrightness`](../../app/src/main/java/com/dewijones92/totum/ui/player/ChosenBrightness.kt).

## What a report will say

```
[playback] user speed -> 1.5 (playing at 6.0, racing=true)
[playback] skip-silence change #50 -> speed=6.0 (user=1.5)
[choices]  quality -> 720p; it will hold for the next video too
[choices]  audio language -> de-DE; it will hold for the next video too
```

The first line is the one that was missing: a rate change and what the player was actually asked
to do about it, which are different numbers whenever a silent stretch is in progress.

## Tests

| Level | Where | What |
|---|---|---|
| unit | `core/playback/…/SilenceRacerTest` | 13 cases, including a rate chosen *during* a silence and 200 stretches in a row |
| unit | `core/playback/…/ReportedSpeedTest` | that the two numbers differ while racing, and that the state mapping and the service still read the right one |
| unit | `app/…/StreamChoicesTest` | the height rules, the cap beating the choice, the fallback both ways |
| unit | `app/…/ChoicesSurviveTheNextVideoTest` | end to end through the launcher's real auto-advance path |

The four `ChoicesSurviveTheNextVideoTest` cases were run against the per-video behaviour first and
all four failed.

## Not covered

- **No test drives the session round trip on a device.** `ACTION_USER_SPEED` leaving the controller
  and arriving at the service needs a real `MediaController`, a real service and a real silent
  stretch; the racer's arithmetic is covered, and the two ends are guarded *at the source* by
  `ReportedSpeedTest` — which fails if either reverts — but nothing proves the message actually
  crosses. Asked directly whether this had pyramid tests (Dewi, 2026-08-11), the honest answer was
  no, and three of those four guards were written afterwards. They fail against the old code; the
  gap that remains is this one.
- Captions surviving an item change is asserted by construction (re-applied every time) rather
  than by a test — it needs a real player with a caption track.
