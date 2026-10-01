---
title: Exsurge et Disce — stand up and study every 30 minutes
kind: todo
status: planned — design agreed in part, nothing built
area: side-quest
priority: medium
requested: 2026-10-01
updated: 2026-10-01
---

# Exsurge et Disce ("rise up and learn")

Dewi, 2026-10-01: *"every 30 minutes … it requires me to get up for 5 minutes and open my
language app … I want it to prompt me with notification and a really obvious way cuz I don't
want to sit down for 30 minutes"*. A **side quest** inside Totum, not a third pillar: it has
nothing to unify across video and podcasts, and it touches the pillars in one place only
(pausing playback).

The face of the module is **Marcus Surgius Disco**, a small round tangerine Roman orator in a
toga with a golden laurel. He carries a scroll marked 起 ("rise"). The logo is 起 in a tangerine
roundel with a cyan half-clock sweep (30 minutes). The ideas waiting on a keep/cut decision are
in [exsurge-ideas](exsurge-ideas.md).

## Agreed with Dewi (2026-10-01)

- **Target app:** his own Hanzi Practice (`dev.hanzi.hanzi_practice`). GO deep-links straight
  into practice via its existing `hanzi_route` intent extra (to be verified that a non-banner
  route is honoured).
- **Summons:** an alarm-style full-screen takeover (full-screen intent, over the lock screen)
  with voice and vibration, and one huge GO button.
- **Smart:** a sitting-time clock (walking resets the 30 minutes), proof of standing (the
  5-minute timer starts after ~20 steps), active hours (weekdays 09:00–18:00), and Totum's
  playback paused for the break and resumed after.
- **Voice:** recorded clips in a Roman orator persona (generated once with edge-tts and
  committed), e.g. "Exsurge!" … "Duo minuta restant." … "Satis! Liber es!" ("you're free").
- **Escape hatch:** snooze 5 minutes, at most twice; after that, GO or a logged Skip.
- **Extras:** streak and stats, a Quick Settings tile, escalation if ignored (60s, twice), and
  a mid-break voice cue.
- **Everything configurable** in the app's Settings screen.
- **Tests:** the full pyramid, but run only when this area's code changes (a path-filtered
  workflow like `audio-quality.yml`, plus a preflight paths check).
- **Decisions get ADRs:** a new `docs/adr/` folder, and a CLAUDE.md rule to create, update or
  supersede an ADR whenever a decision changes.
- **Review:** an independent Opus 5.5 review after the build.

## Design notes from the review pass (Astra, 2026-10-01; claims spot-checked)

- When the phone is **unlocked and in use**, Android 14 shows a full-screen intent only as a
  heads-up banner. A true takeover then needs the "display over other apps" grant.
- GO over the lock screen must call `requestDismissKeyguard` before launching Hanzi.
- `TYPE_STEP_COUNTER` counts only "while activated", so the sitting clock needs a listener
  through the whole 30 minutes, not only during the break.
- `PlaybackController` has no explicit `pause()`, only `togglePlayPause()` (a delayed toggle
  could *start* playback). It needs a real pause, plus an interruption token so a break resumes
  only what it paused.
- SoundPool caps a decoded clip at about 1 MB, so clips stay short and mono.
- The Kover 75% gate applies to `:lib:*`, so the filtered workflow owns `:lib:exsurge`'s
  koverVerify.

## Open questions (waiting on Dewi)

1. Mascot name: Marcus Surgius Disco, or Astra's "Quintus"?
2. Add the optional "display over other apps" grant for a takeover even while the phone is in use?
3. Voice: Latin only or with an English gloss, and Italian *Diego* or British *Ryan*?
4. Streak: a weekday counts when no summons was skipped or missed?
5. Who watches steps between breaks: an ongoing health foreground service with a countdown
   notification (recommended), Google Activity Recognition, or no sitting clock at all?

**Done when:** a summons on Dewi's phone takes over, GO opens Hanzi practice, 20 steps start
the timer, playback pauses and resumes, "Liber es!" plays, and the next diagnostics report
shows every one of those decisions with its inputs.
