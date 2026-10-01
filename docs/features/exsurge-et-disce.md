---
title: Exsurge et Disce — stand up and study
kind: feature
area: side-quest
status: built — awaiting a real day on Dewi's phone (the emulator has no step counter)
updated: 2026-10-01
---

# Exsurge et Disce ("rise up and learn")

**Ask (Dewi, 2026-10-01):** after 30 minutes of sitting, make him get up for five minutes and use
his language app, Loquax. Make the prompt really obvious, give it a voice and a face, and make
everything configurable. The backlog item, with the agreed design and the open ideas, is
[todos/exsurge-et-disce](../todos/exsurge-et-disce.md); the decisions are ADRs
[2](../adr/0002-exsurge-side-quest-module.md) to [8](../adr/0008-an-unproven-break-earns-no-laurel.md).

## What it does

| Moment | What happens | Surgius says |
|---|---|---|
| Sitting (weekdays 09:00–18:00) | Sticky banner: "Next summons 14:32 · Sat 17 of 30 min". Walking 100+ steps in five minutes resets the clock. | — |
| 30 minutes sat | Full-screen takeover (over the lock screen, or over any app with the overlay grant), vibration | *Exsurge, Dewi.* |
| Ignored 60s, then 120s | Called again, louder | *Exsurge! Exsurge!* · *Quo usque tandem, Dewi, sella abutere?* |
| Ignored 3 minutes | Missed: recorded, the clock restarts | — |
| Snooze (5 min, twice at most) | Takeover hides, comes back | — |
| Skip | Recorded; Surgius looks wounded for the half hour | *Et tu, Dewi?* |
| GO | Unlock if needed; Loquax opens at `/practice`; Totum's playback pauses | *Alea iacta est!* |
| 20 steps | The 5-minute break starts | *Bene! Ambula, disce!* |
| 2 minutes left | — | *Duo minuta restant.* |
| Break over | Playback resumes (only if the break paused it); +1 laurel | *Satis! Liber es!* / *Veni, vidi, didici!* |
| Promotion | Every rank of the cursus honorum: Tiro, Legionarius (10), Centurio (50), Tribunus (150), Legatus (300), Consul (600), Imperator (1000) | *Salve! Gradum ascendisti!* |

- **Streak:** a day counts when every summons ended in a *credited* break. A snooze never breaks it;
  a skip, a miss or an unproven break does (GO pressed, but no steps within the rise timeout). A day
  with no summons neither extends nor breaks it. See [ADR 8](../adr/0008-an-unproven-break-earns-no-laurel.md).
- **Every timing is a setting:** sitting limit, break length, steps to rise, walking threshold and
  window, snooze length and count, call-again interval (missed after three calls), rise timeout,
  the break cue's minutes before the end, and the pause length.
- **Pause 1 hour:** from the banner, once a day.
- **Quick Settings tile:** turns it on and off, and shows "Next 14:32".
- **Settings → Exsurge et Disce:** every setting, a permissions checklist with Grant buttons,
  Summon now, and a stats card with Surgius in his current mood.

## Where it lives

- `lib/exsurge/` — the pure brain: `ExsurgeMachine` (one sealed state machine), `ExsurgeSettings`
  (active hours, validation), `StepWindow`, `ExsurgeStats` (ranks, streak), `ExsurgeCodec`.
- `app/…/exsurge/` — `ExsurgeController` (the hub, with `ExsurgePorts`), `AndroidExsurgePorts`
  (alarms, voice, vibration, Loquax), `ExsurgeBannerService` (health foreground service, sticky
  banner, step counter), `TakeoverActivity`, `ExsurgeNotifications`, `ExsurgeActionReceiver`,
  `ExsurgeTileService`, `Surgius` (one painter for every face and icon), `ExsurgeStore`.
- `app/…/ui/settings/ExsurgeSettingsScreen.kt` — the settings.
- `core/playback/…/PlaybackInterruption.kt` and `PlaybackController.setPlaying` — the shared seam.
- `tools/exsurge/gen-voice.sh` — regenerates the Latin clips in `res/raw/exsurge_*.ogg`.

## Diagnostics

Every decision is logged under `dewidebug exsurge`, with its inputs: the event, where it came from,
the state before and after, the machine's notes ("sat 30m of 30m: summoning", "snooze refused: 2 of
2 used"), and the effects. A diagnostics report carries an `exsurge.*` block: state, next wake,
whether steps are available (and how many readings arrived), last event, today's tally, streak,
rank and the settings.

## Verified

- ✅ **JVM:** 64 tests on the state machine and its support code, 14 on the controller, and 8 on
  `PlaybackInterruption`.
- ✅ **Instrumented, on `totum-api35`:** 8 of 8, at 1080×2400 and at 320×640. The small screen is
  CI's default, and it found Snooze and Skip cut off a non-scrolling takeover. Voice clips decode, the summons notification
  carries the full-screen intent and its GO/Snooze/Skip actions, the banner text, the destination
  launch, every face renders, and GO and Skip work from the real takeover.
- ✅ **On the emulator by hand (2026-10-01):**
  - The settings screen, a summons from the home screen taking over via the overlay grant, and
    the takeover all screenshotted and looked at.
  - The voice cues fired on exact alarms within 0.6 s of their deadlines ("Duo minuta restant",
    "Liber es!").
  - The banner showed "On break until 13:29" with Surgius counting. A swipe re-posted it
    (`REPOST`).
  - A reinstall mid-break kept the state, and `MY_PACKAGE_REPLACED` re-armed the alarm
    (`dumpsys alarm`).
  - Simulated steps took GO → rising → 21 steps → break.
  - The break paused a video meant to be playing.
- ⏳ **On Dewi's phone:** not yet. The step counter, Loquax opening at `/practice`, and audio focus
  with the banner service running can only be proven there.

## Independent review (Opus 5.5, 2026-10-01)

No CRITICALs. Every IMPORTANT finding was turned into a test first and seen red, then fixed:
- an all-day window wedged the clock at midnight;
- enabled and state could disagree after a store failed to decode;
- an unproven break earned a laurel;
- laurels could fall once the history passed 5,000;
- the banner promised a summons that active hours would end first;
- several timings were hard-coded;
- the CI paths missed `Media3PlaybackController`, `libs.versions.toml` and `lib/common`, and the
  instrumented job had no zero-test guard.

Two SUSPECTED findings were fixed by design rather than by test:
- the banner lost when the service stops (the `REST` action);
- screen-off steps arriving after the tick (flush, then wait).

## Known limits, seen on the emulator

- **Without the foreground service, the voice gets no audio focus** (`voice focus=0`): without the
  Physical activity grant the banner service does not run, and Android refuses focus to a
  background app. The clip still plays; it just does not duck other audio.
- **A stall rescue in flight overrides the break's pause.** That is a pre-existing playback race,
  not Exsurge's: see [stall-rescue-overrides-a-pause](../todos/stall-rescue-overrides-a-pause.md).
- **One unexplained ANR:** the first visit to the settings screen after a fresh install hung the
  main thread in continuous recomposition for about 50 s. It could not be reproduced after a
  force-stop, on the same build (main-thread CPU on that screen then measured 2 ticks in 4 s). If
  it recurs, take `debuggerd -j` of the main thread straight away.
- **The step baseline:** steps taken between GO and the next sensor report used to be missed. The
  last reading before GO is now the baseline (test written red first).
