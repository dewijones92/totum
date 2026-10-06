---
title: Exsurge et Disce — stand up and study every 30 minutes
kind: todo
status: built — awaiting a real day on Dewi's phone; see features/exsurge-et-disce.md
area: side-quest
priority: medium
requested: 2026-10-01
updated: 2026-10-06
---

> **Built 2026-10-01.** The feature doc is [features/exsurge-et-disce](../features/exsurge-et-disce.md) and the decisions are [ADRs 2–7](../adr/_index.md). Two departures from the plan, both recorded in ADR 5: `setExactAndAllowWhileIdle` rather than `setAlarmClock` (so it does not take over the clock's "next alarm"), and "Pause 1 hour" capped at once a day.

**Follow-up (Dewi, 2026-10-06):** the popup also offers Continue Totum: resume the current
item while taking the walking break, without language practice. The behaviour belongs in
the feature doc and [ADR 4](../adr/0004-playback-set-playing-and-interruption.md).

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
- **An always-there sticky banner** (Dewi, 2026-10-01: *"make the banner notification for this
  always sticky and there"*). One ongoing notification is always shown, including when the module
  is off (clarified 2026-10-05),
  with Surgius in his current mood: during active hours "Next summons 14:32 · sat 17 min ·
  3 laurels today" with a sitting-clock progress bar; outside them "Surgius sleeps · back
  Mon 09:00". Android 14 lets the user swipe away even an ongoing notification, so "sticky"
  means ongoing plus an instant re-post from its delete intent. That is the same technique as
  Hanzi Practice's `BannerManager`, re-posted after reboot and APK update too.
  Off offers Turn on, Summon now and Restart clock; the latter two run once without enabling the
  regular schedule. It is also the foreground-service notification that keeps the step listener
  alive during active hours and manual one-offs, which answers how the sitting clock hears your steps (open question 5 → option a).
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

## Settled (Dewi, 2026-10-01)

1. **Mascot:** Surgius (Marcus Surgius Disco).
2. **"Display over other apps": yes.** It is an optional grant in the permissions checklist,
   so the takeover covers the screen even while the phone is in use; without it, the full-screen
   intent falls back to a heads-up banner when the phone is unlocked.
3. **Voice: Latin only**, no English gloss.
4. **Streak** (Dewi left it to Claude): a weekday counts when every summons ended in a completed
   break. A snooze never breaks it; a Skip or a Missed does. A day with no summons (module off,
   or outside active hours) neither extends nor breaks it, so a holiday is not a failure.
5. **Step watching:** the sticky banner's health foreground service, during active hours.
6. **Pushing:** hold the docs commits and push them with the first code changes.

## Still open

- The banner's "Pause 1 hour" action: keep it, cap it (e.g. once a day), or drop it?
- Keep / cut / later on each idea in [exsurge-ideas](exsurge-ideas.md).

**Done when:** a summons on Dewi's phone takes over, GO opens Hanzi practice, 20 steps start
the timer, playback pauses and resumes, "Liber es!" plays, and the next diagnostics report
shows every one of those decisions with its inputs.
