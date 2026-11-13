---
title: Phone use — unlocks, screen time and an optional "still want in?" nudge
kind: feature
area: library
status: built 2026-11-13 — checked on totum-api35 (see Verified); not yet on Dewi's phone, which needs Usage access
updated: 2026-11-13
---

# 📱 Phone use

**Ask (Dewi, 2026-11-13):** *"some sort of tracker. How many times do I unlock my phone … I feel like I checked my phone too
many times … maybe lock the phone … not be able to open the phone more than once every 10 minutes"*. Decision:
[ADR 160](../adr/0160-phone-use-is-read-from-androids-own-history-and-the-nudge-only-asks.md). Builds on
[stats](stats.md) ([ADR 44](../adr/0044-stats-are-an-append-only-event-log.md)).

## What it does

- **Counts** each unlock and the screen-on time per day and per hour, from Android's own usage history, so nothing runs all
  day. Needs **Usage access** (Settings → 📱 Phone use → Open usage access, or the button on the Stats section).
- **Library → 📊 Stats → 📱 Phone use** shows:

  | Part | What it shows | Drill-down |
  |---|---|---|
  | 📅 Today | unlocks, screen on, longest gap between unlocks (same day), time since the last unlock | — |
  | 🕰️ Unlocks per hour today | 24 bars | tap an hour: its unlocks |
  | 📈 Trend | 7 or 30 days, unlocks or screen time | tap a day: its unlocks |
  | 📊 Summary | unlocks in the range, average a day | — |
  | 📲 First app after an unlock | ranked, with the screen time that followed | tap an app: its unlocks |
  | ⏳ Unlock nudges | shown, continued, went home, left without answering | — |

  Each unlock row says the time, the app it opened to and how long the screen then stayed on.
- **⏳ Unlock nudge (Settings → 📱 Phone use), off by default.** "Nudge me if I unlock within [2–30, default 10] min of the
  last unlock". When it fires: a full-screen "⏳ You unlocked 3 min ago (14 today). Still want in?" with **Not now** (home
  screen) and **Continue**, enabled after 5 s with a countdown. **It never blocks the phone**: Back and Home work, and it
  closes itself if it is left or alone for 90 s. Android does not let an app lock a phone, and the page says so.
- **Stays out of the way** of a call, the camera, navigation, Exsurge, a ringing alarm, the kid lock and a video playing
  with Totum in front; the log says which.
- The nudge starts directly over other apps when "Display over other apps" is granted (as Exsurge's takeover); otherwise
  it is a heads-up notification with a full-screen intent that opens the same screen.

## Where it lives

- `lib/stats/` — `StatEvent.PhoneUnlock/PhoneScreen/UnlockNudge`, `UsageEvents.kt` (`settle`, first app, foreground app),
  `PhoneUseStats.kt` (sessions, today, trend, rows, first apps, nudge totals), `UnlockNudge.kt` (settings, `decide`,
  `NudgeBlock`, the five-second hold, codec). All pure.
- `app/…/phoneuse/` — `PhoneUse` (hub: settings, sync, the unlock listener, the decision), `PhoneUsePorts` +
  `AndroidPhoneUsePorts` (usage events, access check, USER_PRESENT receiver, call/camera/navigation checks),
  `UnlockNudgeActivity`/`UnlockNudgeScreen`/`UnlockNudgeNotifications`, `PhoneUseScreen` (the Settings page),
  `UsageAccessCard` (shared with Stats), `PhoneUseBackup`.
- `app/…/ui/stats/` — `PhoneUseSection`, `PhoneUseActions`; `StatsViewModel` gains `PhonePage` and the unlock drill-downs.
- `app/…/reminders/kit/OverApps.kt` — the over-other-apps launch, now shared with Exsurge.

## Logged

`[phoneuse]`: `loaded`, `sync … read N usage event(s) …, recorded M, now settled until …` (or `skipped: usage access is
off`), `USER_PRESENT received`, `nudging: last unlock N min ago, M today [foreground=…]`, `not nudging: <reason>
[foreground=… unlocks=…]` (reasons: switched off, no earlier unlock, outside the window, or the blocks by name),
`nudge shown: … started directly over other apps=…`, `nudge answered: CONTINUED|LEFT`, `nudge screen stopped without an
answer`, and the camera and navigation apps found. Reports carry `phoneUse.usageAccess`, `.nudgeOn`, `.withinMinutes`,
`.listening`, `.syncedUntil`, `.lastSync` and `.lastDecision`. Stats: `drill into phone use …`.

## Verified

On `totum-api35` (2026-11-13, lock screen switched on for the test and off again): unlocks, first apps (Settings, Totum) and
screen time appeared in Stats after real lock/unlock cycles; the Settings page, the Usage access card and the slider render;
with the nudge on, a second unlock inside ten minutes showed the full-screen nudge ("You unlocked 8 min ago (9 today)")
with Continue held by the countdown then enabled, **Continue** recorded `CONTINUED`, **Not now** went to the launcher and
recorded `LEFT`, power-off closed the screen so it did not come back, and without "Display over other apps" the same nudge
was a heads-up notification. A `RECEIVER_NOT_EXPORTED` receiver never got `USER_PRESENT`, so it is exported (the action is
protected). Two broadcasts a second apart showed two nudges until the 3 s de-duplication was added (hub test seen red
without it). Screens: `~/claude-tasks/2026-11-13-phone-use-screens/`.

Not seen: a PIN lock screen, a call/camera/navigation block on a real app (covered by `PhoneUseHubTest` with fakes), the
section on Dewi's phone, and the nudge surviving with nothing holding Totum's process (another session's reinstall killed it
on the emulator and no nudge came until it was reopened).

## Tests

`:lib:stats` `PhoneUseStatsTest` (settle in halves, first app before and after the unlock, held-back unlocks, screen
sessions and their orphans, today across UTC and local midnight, the gap, hours, the clocks going forward and back, a
session over midnight, trend, first apps, rows, bars, nudge totals, the codec) and `UnlockNudgeTest` (switched off,
the window edge, the setting's window, no earlier unlock, a duplicate event, an unlock not yet on record, every block,
unlocks today across local midnight, clamping, the five-second hold). `:app` `PhoneUseHubTest` (sync once and incrementally,
the seven-day first reach, no access, nudge shown and not shown, listening only while on, every block, answers recorded),
`UsageEventKindsTest`, and `StatsViewModelTest` (the section, trend ranges, drill-downs, no access).
No instrumented test: the screens were checked by hand on `totum-api35`.
