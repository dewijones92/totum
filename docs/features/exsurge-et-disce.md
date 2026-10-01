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
[2](../adr/0002-exsurge-side-quest-module.md) to [7](../adr/0007-exsurge-tests-run-only-when-touched.md).

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

- **Streak:** a day counts when every summons ended in a completed break. A snooze never breaks it;
  a skip or a miss does. A day with no summons neither extends nor breaks it.
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

- ✅ 56 JVM tests on the state machine and its support code (96% line coverage), 12 on the
  controller, and 8 on `PlaybackInterruption`.
- ⏳ Instrumented: voice clips decode, notifications carry the takeover and its actions, the banner
  text, the destination launch, the face rendering, and GO and Skip from the real takeover.
- ⏳ On the emulator: see the todo for the run. On Dewi's phone: not yet, and the step counter can
  only be proven there.
