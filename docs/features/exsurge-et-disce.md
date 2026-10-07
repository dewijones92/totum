---
title: Exsurge et Disce — stand up and study
kind: feature
area: side-quest
status: built — awaiting a real day on Dewi's phone (the emulator has no step counter)
updated: 2026-10-07
---

# Exsurge et Disce ("rise up and learn")

**Ask (Dewi, 2026-10-01):** after 30 minutes of sitting, make him get up for five minutes and use
his language app, Loquax. Make the prompt really obvious, give it a voice and a face, and make
everything configurable. The backlog item, with the agreed design and the open ideas, is
[todos/exsurge-et-disce](../todos/exsurge-et-disce.md); the decisions are ADRs
[2](../adr/0002-exsurge-side-quest-module.md) to [8](../adr/0008-an-unproven-break-earns-no-laurel.md) and
[10](../adr/0010-break-length-is-chosen-at-the-summons.md), and [15](../adr/0015-exsurge-banner-is-a-live-update.md).

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
| Just walk | The same break, with no language app (Dewi, 2026-10-01: *"I don't necessarily wanna do language learning when I get up"*); no unlock needed; recorded as not practised | *Alea iacta est!* |
| Continue Totum | Resume the current Totum item while taking the same walking break; unlock if needed and return to Totum, without opening the language app or restarting the item; recorded as not practised | *Alea iacta est!* |
| 20 steps | The break starts, for the length chosen on the takeover (5 minutes by default) | *Bene! Ambula, disce!* |
| 2 minutes left | — | *Duo minuta restant.* |
| Break over | Playback resumes (only if the break paused it); +1 laurel | *Satis! Liber es!* / *Veni, vidi, didici!* |
| Promotion | Every rank of the cursus honorum: Tiro, Legionarius (10), Centurio (50), Tribunus (150), Legatus (300), Consul (600), Imperator (1000) | *Salve! Gradum ascendisti!* |

- **Streak:** a day counts when every summons ended in a *credited* break. A snooze never breaks it;
  a skip, a miss or an unproven break does (GO pressed, but no steps within the rise timeout). A day
  with no summons neither extends nor breaks it. See [ADR 8](../adr/0008-an-unproven-break-earns-no-laurel.md).
- **Break length chips** (Dewi, 2026-10-06): the takeover offers 2 / 5 / 10 / 15 min above GO,
  with the last choice selected. A chip writes the one Break length setting, so the stepper on the
  Exsurge screen shows the same number. GO, Just walk and Continue Totum fix that length on the
  break as it begins. Changing the setting later applies from the next break and never moves the
  one under way. A value set by the stepper outside the four shows as an extra selected chip. See
  [ADR 10](../adr/0010-break-length-is-chosen-at-the-summons.md).
- **The Exsurge screen shows everything the banner shows** (Dewi, 2026-10-06): a status card at
  the top with the banner's own title, detail line, progress bar and buttons. `BannerText` writes
  both texts, and `bannerActionsOf` (in `:lib:exsurge`) lists the buttons for both, so the page and
  the notification cannot disagree. The card updates every second while the screen is open. Its
  GO (while snoozed) opens the takeover's GO.
- **Every timing is a setting:** sitting limit, break length, steps to rise, walking threshold and
  window, snooze length and count, call-again interval (missed after three calls), rise timeout,
  the break cue's minutes before the end, and the pause length.
- **Always-present banner** (Dewi, 2026-10-05): stays visible when switched off, paused, or
  outside active hours. Off reads "Exsurge et Disce is off" and offers **Turn on**, **Summon now**
  and **Restart clock**. It stays quiet, re-posts after dismissal, and is restored at startup,
  reboot and APK update. Turning off posts the plain ongoing banner and
  removes the service notification; the step listener stops. Both use one builder, with separate
  notification IDs so delayed service callbacks cannot overwrite the idle banner (ADR 5).
- **On the lock screen and in the status bar** (Dewi, 2026-10-07): on Android 16 QPR1+ the banner is a
  Live Update in every state, so it shows on the lock screen and the always-on display, with a status-bar
  chip: a countdown to the summons, the end of the break, a snooze or a pause, else GO!, the steps
  (7/20), Off or Zzz. Its text is public on the lock screen. Older Android is unchanged. The permissions
  checklist has a Live Update row on Android 16+. See [ADR 15](../adr/0015-exsurge-banner-is-a-live-update.md).
- **Off quick actions:** Turn on enables the regular schedule. Restart clock and Summon now
  start one manual reminder/break while leaving that schedule off, returning to Off after
  completion, Skip or Missed. The manual run survives process restart and snooze; changing its
  settings does not cancel it. Explicitly switching an enabled schedule off still cancels a
  live run and releases playback. Pause remains an action for the enabled schedule.
- **Pause 1 hour:** from the banner, once a day.
- **Restart clock** (banner, Exsurge screen) means "I've just sat down": the sitting limit starts
  again from now. Dewi's case, 2026-10-01: *"I go to sit and play piano for half an hour then I
  wanna get up"*. Outside active hours it arms a **one-off** summons after the sitting limit,
  which can be snoozed as usual, and then goes back to sleep. That holds from any state, including
  a second Restart in the evening and a pause that ran past 18:00. A one-off armed before 09:00
  becomes the ordinary clock once the day starts, so it goes quiet at 18:00 as usual. It ends a pause. **Mid-summons**
  (summoned, snoozed, or walking to the 20 steps) it ends that summons as a quiet skip (recorded,
  no "Et tu"). **Mid-break** it ends the break early as completed. Dewi chose both on 2026-10-01,
  after his first report on 0.1.554 showed Restart clock being ignored at `rising#5/7` while the
  banner kept counting steps.
- **Summon now** outside active hours is a one-off too, so its snooze comes back rather than being
  dropped as "outside hours".
- **Steps arrive promptly while it matters.** The step counter is batched at 60 s while sitting, to
  spare the battery, and unbatched while rising or on a break. A batched report could hold back the
  20th step for up to a minute.
- **Continue Totum** (Dewi, 2026-10-06): the popup offers this alongside GO and Just walk.
  It explicitly resumes the current video or podcast at its existing position, even when paused,
  while keeping the walking proof, break timer and rewards. It uses the shared playback controller
  and never pauses playback for this break; a later manual pause remains paused when the break ends.
  Just walk still follows the playback-pause setting. Continue Totum also works for a disabled
  manual one-off, which returns to Off afterward.
- **The summons notification** carries GO, Just walk and Snooze, or GO, Just walk and Skip when no
  snoozes are left. Android shows three actions at most; the popup has all five choices, including
  Continue Totum. Its actions scroll on a small screen.
- **Stats** count the breaks that included practice separately ("done: 3 (practised: 2)"). Walking
  without practice still earns the laurel; the laurel is for standing up.
- **Quick Settings tile:** turns it on and off, and shows "Next 14:32". A long-press opens the
  Exsurge screen.
- **Tapping the banner** opens the Exsurge screen (`ExsurgeActivity`, which hosts the same
  `ExsurgeSettingsScreen`), or the takeover while a summons is live. It opens in its own task, out of
  Recents, so Back returns to whatever was in front rather than into Totum's player. Dewi asked for this on
  2026-10-01; before that, it opened Totum's main screen.
- **Settings → Exsurge et Disce:** the live status card (above), a stats card with Surgius in his
  current mood, every setting, and a permissions checklist with Grant buttons.

## Where it lives

- `lib/exsurge/` — the pure brain: `ExsurgeMachine` (one sealed state machine), `ExsurgeSettings`
  (active hours, validation), `StepWindow`, `ExsurgeStats` (ranks, streak), `ExsurgeCodec`.
- `app/…/exsurge/` — `ExsurgeController` (the hub, with `ExsurgePorts`), `AndroidExsurgePorts`
  (alarms, voice, vibration, Loquax), `ExsurgeBannerService` (health foreground service, sticky
  banner, step counter), `TakeoverActivity`, `ExsurgeNotifications`, `ExsurgeActionReceiver`,
  `ExsurgeTileService`, `Surgius` (one painter for every face and icon), `ExsurgeStore`.
- `app/…/ui/settings/ExsurgeSettingsScreen.kt` — the settings; `app/…/exsurge/ExsurgeStatusCard.kt` —
  the banner's content on that screen.
- `core/playback/…/PlaybackInterruption.kt` and `PlaybackController.setPlaying` — the shared seam.
- `tools/exsurge/gen-voice.sh` — regenerates the Latin clips in `res/raw/exsurge_*.ogg`.

## Diagnostics

Every decision is logged under `dewidebug exsurge`, with its inputs: the event, where it came from,
the state before and after, the machine's notes ("sat 30m of 30m: summoning", "snooze refused: 2 of
2 used"), and the effects. A diagnostics report carries an `exsurge.*` block: state, next wake,
whether steps are available (and how many readings arrived), last event, today's tally, streak,
rank and the settings, plus `exsurge.liveUpdate.*`: whether Live Updates are allowed, what the banner
last asked for, and whether the posted banner is actually promoted.

## Verified

- ✅ **JVM:** 86 tests on the state machine and its support code, 23 on the controller, and 8 on
  `PlaybackInterruption`.
- ✅ **Original instrumented coverage, on `totum-api35`:** 8 of 8, at 1080×2400 and at 320×640. The small screen is
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

- **Banner regression coverage:** `ExsurgeAndroidPortsTest` verifies the posted Off banner and
  its three actions. `ExsurgeBannerFlowTest` drives the actual PendingIntents, checks the service
  stops without losing the notification, and re-posts after dismissal and simulated boot/update events.
  Controller tests cover the complete Off manual run, snooze/persistence, steps, settings changes
  and missed/skip completion. On 2026-10-05 all 14 device tests passed at 1080×2400, including
  five rapid Restart/Summon/Skip cycles. The expanded Off notification was visually checked:
  Surgius, the Off title and all three action labels fit without clipping. The normal quality
  gate and the separate 104-test Exsurge JVM phase passed.

- **Continue Totum verification (2026-10-06):** the normal quality gate and the separate
  109-test Exsurge JVM phase passed. All 17 Exsurge device tests passed at 1080×2400 and 320×640.
  Real playback tests resume a paused podcast or continue an already-playing video without
  changing its item or restarting its position; a later manual pause is respected when cancelling
  the break. Unit tests cover step baseline, walking proof, completion, snooze, duplicate presses
  and a disabled one-off. The new popup button and caption were visually checked at both sizes;
  on the smaller screen the choices remain reachable by scrolling.

- **Status card and break-length verification (2026-10-06):** written red first, two machine tests
  failed against the old code: lengthening the setting mid-break moved the end from 09:05 to 09:10,
  and shortening it ended the break. 92 `:lib:exsurge` and 25 app JVM tests pass. The 21 Exsurge
  device tests pass at 1080×2400, and the takeover's 7 at 320×640. That includes
  `ExsurgeStatusCardTest`, which asserts that the card's text equals `BannerText` in the sitting,
  break and snoozed states and that it offers the banner's buttons, and a chip test (tap 10, Just
  walk, the break carries 10 and the setting reads 10). On the emulator, Summon now from the card
  summoned, 10 min was chosen, and Just walk gave "On break until 11:09" from 10:59. The page,
  the takeover and the small-screen takeover were screenshotted and looked at.

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

- **Live Update (2026-10-07):** two `:lib:exsurge` tests (written first, seen red) pin the chip for every
  banner state. On an Android 16 QPR1 emulator (`totum-api36`) the system set `PROMOTED_ONGOING` on the
  banner and the log showed `requested=true promotable=true appAllowed=true chip="Off" sdk=36.1`. ⏳ The
  lock screen and chip were not seen: that emulator image crashes System UI on screen read-back under
  WSL (`hasReadColorBufferDma`), with every GPU mode tried. First sight is on the phone.

## Known limits, seen on the emulator

- **Without the foreground service, the voice gets no audio focus** (`voice focus=0`): without the
  Physical activity grant the banner service does not run, and Android refuses focus to a
  background app. The clip still plays; it just does not duck other audio.
- **A stall rescue in flight used to override the break's pause.** That was a playback race, not
  Exsurge's, and is fixed (the first fix was not): see
  [stall-rescue-overrides-a-pause](../todos/stall-rescue-overrides-a-pause.md).
- **One unexplained ANR:** the first visit to the settings screen after a fresh install hung the
  main thread in continuous recomposition for about 50 s. It could not be reproduced after a
  force-stop, on the same build (main-thread CPU on that screen then measured 2 ticks in 4 s). If
  it recurs, take `debuggerd -j` of the main thread straight away.
- **The step baseline:** steps taken between GO and the next sensor report used to be missed. The
  last reading before GO is now the baseline (test written red first).
