---
title: Phone use is read from Android's own history, and the unlock nudge only asks
kind: adr
status: accepted
updated: 2026-11-14
---

# 160. Phone use is read from Android's own history, and the unlock nudge only asks

- Status: Accepted
- Date: 2026-11-13

## Context

Dewi, 2026-11-13: *"some sort of tracker. How many times do I unlock my phone, go on my phone … I feel like I checked
my phone too many times … maybe lock the phone like not be able to open the phone more than once every 10 minutes"*.
He delegated the design. Two things pull against each other: a count only means something if it is complete, and a
tracker that has to run all day is one more thing for Android to kill.

## Decision

- **The source is Android's usage history**, read with `UsageStatsManager.queryEvents` (`KEYGUARD_HIDDEN`,
  `SCREEN_INTERACTIVE`, `SCREEN_NON_INTERACTIVE`, `ACTIVITY_RESUMED`, `ACTIVITY_PAUSED`). That needs **Usage access**
  (`PACKAGE_USAGE_STATS`, a special app op the person grants once in Settings). Nothing runs all day, and the history
  that was already there when access is granted is read too (the first sync reaches back seven days; how far Android
  itself keeps events was not measured).
- **It is recorded into the stats log** ([ADR 44](0044-stats-are-an-append-only-event-log.md)), not a new store:
  `PhoneUnlock(at, firstApp)`, `PhoneScreen(at, on)` and `UnlockNudge(at, what, …)`. `PhoneUse.sync` runs when the app
  starts, when Stats or the Phone use page opens, and after every unlock, so the log keeps what Android later forgets.
  Raw events are *settled* by pure code (`settle`, `:lib:stats`) over the half-open window
  `[cursor, now − 5 s)`, so each event is recorded once and an unlock waits until its first app could have been seen.
  An older build reading the log counts the new lines as unreadable and skips them.
- **What the numbers mean** (`:lib:stats`, all pure and unit-tested):
  - *Unlocks* are `KEYGUARD_HIDDEN` events, counted in the phone's own day.
  - *Screen on* is time between `SCREEN_INTERACTIVE` and `SCREEN_NON_INTERACTIVE`, split at local hour boundaries so a
    session over midnight or a daylight-saving change counts the time that really passed. An on with no off, or an off
    with no on, adds nothing and is counted.
  - *Longest gap* is between two unlocks **on the same day**; the night before the first unlock does not count (it would
    always be the longest), and a gap still running counts.
  - *First app after an unlock* is the app Android reports resuming up to 2 s before the keyguard went away (it resumes
    just before `KEYGUARD_HIDDEN`, seen in the usage log on `totum-api35`) or else the first within 5 s after, ignoring
    system UI.
- **The Stats page** gets a "📱 Phone use" section that always shows today, unlocks per hour today, a 7- or 30-day trend of
  unlocks or screen time, the apps opened first, and the nudge's outcomes. A bar or an app opens its unlocks
  (drill-down, as the rest of Stats). Without Usage access it shows "⚠️ Usage access is off" with the explanation and a
  button to the system screen, and whatever the log already holds. Reached from Library → Stats; Settings → 📱 Phone use
  holds the access card and the nudge.
- **The nudge is off by default** and **only asks**. While it is on, Totum registers `ACTION_USER_PRESENT` at run time with `RECEIVER_EXPORTED` (the
  manifest cannot receive it; the process has to be alive: the always-pinned alarm board, playback or a download hold it; a `RECEIVER_NOT_EXPORTED` receiver was never
  delivered the broadcast on `totum-api35`, and only the system can send this protected action). Two broadcasts within 3 s
  are one unlock. After a 1.5 s settle, a pure decision
  (`UnlockNudgeSettings.decide`) compares the unlock with the previous one from the usage history: inside the window
  (2 to 30 minutes, default 10) it shows a full-screen "⏳ You unlocked 3 min ago (14 today). Still want in?" in the
  side-quest look ([ADR 34](0034-the-side-quests-look-roman-and-bold.md)). **Continue** is held for five seconds;
  **Not now** goes to the home screen; Back works; the screen closes itself when it stops being the top screen and after
  90 s alone, so it can never be what the phone opens to.
- **It stays out of the way** of a call (audio mode: call, communication, ringing, screening), the camera or navigation
  in the foreground (the apps that handle the camera and navigation intents), Exsurge on screen (summons, rising, break,
  or its takeover resumed), a ringing daily alarm, the kid lock, and a video playing with Totum in front. The reason for
  every held-back or shown nudge is logged with its inputs.
- **It reuses what exists**: the over-other-apps launch moved from `AndroidExsurgePorts` into the reminder kit
  (`OverApps`) and both use it; the full-screen-intent check, `KeepScreenAwake`, `QuestCard`, `SnappingSlider`,
  `LocalLaurel`, `Bar` and `BarChart`, `appLabel`, and the Drive backup of settings (`withPhoneUse`, as Bedtime).
- **The honest limit:** no app can lock a phone or stop it opening (that needs device-admin or accessibility powers Totum
  does not ask for). The nudge makes the person decide twice; it does not enforce anything. This is also why it is
  not built as a once-every-ten-minutes lock.

## Consequences

- The person grants Usage access once. The section and the nudge say so while it is off; nothing is guessed without it.
- Direct launch over other apps needs "Display over other apps" (already granted for Exsurge); without it the nudge is a
  heads-up notification that opens the same screen.
- Foreground detection is best effort: a navigation app running in the background does not hold the nudge back, and an
  app with an unusual camera or navigation intent is not recognised.
- The Ask pack and the state dump carry unlocks (their time) and nudge outcomes but leave out the raw screen on/off
  events, which are the bulk of the log, and **never carry the app opened first**: `AskStateSource` takes `firstApp`
  out of every `PhoneUnlock` before it is encoded (2026-11-14; until then the package name went to the Pi in every
  pack and dump, while the Usage access card said app names stay on the phone). The Stats page on the phone still has
  it, and the card now says the times can go to the home server and the app names never do. Screen-time questions to
  Ask cannot be answered from the pack.
- **Unlocks are counted per kind in the pack.** A day of unlocks is hundreds of rows, and the pack's "other stats" were
  one newest-500 list, so a busy fortnight pushed every Exsurge and alarm row out of the Ask pack and the state dump.
  `AppStateBuilder` now keeps the newest 200 of each kind (`MAX_OTHER_STATS_PER_TYPE`) and says so in
  `snapshot.notes`; the `phone-unlock` kind can fill its 200 and no more.
- With nothing holding the process, Android can stop it and no nudge comes until Totum is next opened (seen on `totum-api35`,
  which has no alarms; Dewi's phone has the pinned board, so it should be held there, not confirmed). Usage tracking is
  unaffected, since it reads Android's history.
- Not measured: battery cost of the run-time receiver, how long Android keeps events on Dewi's phone, and the nudge on a
  phone with a PIN (the emulator has a swipe lock).
