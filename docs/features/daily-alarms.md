---
title: Daily alarms and reminders — asked each morning or nagging until done, rung by Totum
kind: feature
area: side-quest
status: built 2026-10-07 — ships switched off; reminders and the one-tap page built 2026-10-29, the fridge reminder on by default
updated: 2026-10-31
---

# Daily alarms

**Ask (Dewi, 2026-10-07):** *"an alarm clock that asks me every day: do I want a half-five alarm to pick up
my toddler?"* Decisions: [ADR 18](../adr/0018-daily-alarms.md), built on the reminder kit
([ADR 17](../adr/0017-reminder-kit-shared-by-side-quests.md)); backlog and answers:
[todos/daily-alarms](../todos/daily-alarms.md).

## What it does

| When | What happens |
|---|---|
| 08:00 on its days (Mon–Fri) | A notification: *Pick up time: alarm at 17:30 today?* — **Yes, 17:30 · Other time · No** |
| No answer | Asked again every hour; a last ask at 16:30 that stays until 17:30; then no alarm today |
| Other time | A screen with the time chips (17:00 · 17:15 · 17:30 · 17:45 · 18:00, editable) and a clock for any time |
| Set | One pinned board for all alarms, soonest first (*Next: Pick up time 17:30 · in 6h*), each alarm's next occurrence as a row (set, asking, skipped, or its next day), **Change · Cancel** for the soonest set alarm, **Set it after all** for a skipped one, **Open**; swiping it puts it back; **always pinned**, saying *No alarm today*, *Alarms off* (with **Turn on**) or *No alarms* when nothing is set, redrawn at midnight, and shown on the lock screen ([ADR 21](../adr/0021-alarm-board.md), [ADR 23](../adr/0023-pinned-notifications-alert-silently.md)) |
| 17:30 | Full screen over the lock screen: the phone's alarm tone, *"Pick up time. It's half five."* every 9 s, vibration — until **Dismiss**; **Snooze 5 min** as often as needed. Totum's playback pauses and resumes after |
| From the alarms page, any time | **Set for today**: tap a time chip (the day's time and the choices; times already gone are greyed out) or **Other time…**, and today's alarm is set; **Cancel today** undoes it. Works on any day, before the 08:00 question (which then never comes), and again after it has rung. Tapping a time on an alarm that is off switches it on, without the in-between tick that would sound the morning question; a time already gone says so and leaves the alarm as it was. Event `SetToday`, logged `from page` (2026-10-12, [ADR 18](../adr/0018-daily-alarms.md)) |
| Exsurge at the same time | The alarm wins; Exsurge holds its takeover and voice until it is dismissed |

- **Several alarms**, each with its own label, days, usual time, **a time per weekday**, time choices,
  first and last ask. Settings → **Daily alarms**, and a Quick Settings tile showing today's state (the first alarm
  that is not a reminder).
- Ships with "Pick up time" 17:30, **switched off**.

**The screen stays awake** (Dewi, 2026-10-08) on the ringing screen, the morning question and the alarm
settings, at the phone's normal brightness, through the kit's `KeepScreenAwake`.

## Where it lives

- `lib/dailyalarms/` — `DailyAlarm`, `DailyAlarmMachine` (per-day states), `spokenLine`, `AlarmCodec`.
- `app/…/dailyalarms/` — `DailyAlarmController`, `AndroidDailyAlarmPorts`, `RingService`, `QuestionActivity`,
  `RingActivity`, `DailyAlarmNotifications`, `DailyAlarmReceiver`, `DailyAlarmTileService`,
  `DailyAlarmsScreen` / `DailyAlarmsActivity`, `AlarmCard`, `AlarmEditor`, `OneOffSection`, `NewAlarmSheet`,
  `NewAlarmChoice`.
- The reminder kit: `lib/reminders/`, `app/…/reminders/kit/`.

## Diagnostics

Every event is logged under `dewidebug dailyalarm <id> <event> from <source>: <before> -> <after>; notes=…;
effects=…; nextWake=…`; ringing logs the tone, voice and foreground start. Reports carry
`dailyAlarms.<id>.state / .nextWake / .settings` and `dailyAlarms.lastEvent`.

## Verified (2026-10-07, totum-api35)

- ✅ JVM: 18 `:lib:dailyalarms` (machine + codec; mutation-checked: a shorter late-ring grace and a
  miscounted last ask were both caught) and 4 controller tests; kit 7.
- ✅ Device: `DailyAlarmDeviceTest` 3/3 — the question carries Yes 17:30 · Other time · No, the set
  notification Change · Cancel, and `RingService` goes foreground (`systemExempted`) with a full-screen
  alarm notification and stops cleanly.
- ✅ By hand, with a test alarm minutes ahead: asked at 18:01:00 exactly, last ask at 18:03, withdrawn
  unanswered at 18:04 (`Done(UNANSWERED)`); re-armed, **Yes, 18:07** from the notification set it (the
  status-bar alarm-clock icon appeared); with the screen asleep it woke at 18:07, showed the ring screen
  over the lock screen, played the alarm tone, vibrated, and said *"Pick up time. It's seven past six."*;
  Snooze silenced it and it rang again at 18:12:27; Dismiss ended it (`Done(RANG)`). Screenshots of the
  question, the set notification, the ring screen and the settings screen were looked at.
- ⏳ The phone: Doze, the real alarm volume and a real weekday morning.

## Look (2026-10-14, ADR 34)

Each alarm is a compact card: name and switch, the next ring in huge type (`DailyAlarm.nextRing`), today's
state, the Set for today chips and laurel day initials; Edit opens the full editor. The ring screen shows the
time inside a laurel circle. See [ADR 34](../adr/0034-the-side-quests-look-roman-and-bold.md).

## At a glance (2026-10-14)

The card's today line starts with an emoji per state: ⏳ waiting, ❓ asking, ✅ set for 17:30, 🔔 ringing,
😴 snoozed until …, and per outcome 🏁 rang, 🚫 you said no, ✖️ cancelled, 🤷 no answer, ⚠️ missed.

## One-off alarms (2026-10-28, ADR 91)

**➕ New alarm** on the alarms page (now titled **Alarms**) opens a sheet: a title, your recent titles as chips,
+15 min · +30 min · +1 h · +2 h, a clock, Today · Tomorrow · a date. *"Deployed to production" at 19:00 tonight* is a
title and a time. It rings like a daily alarm (full screen, tone, vibration, the voice saying its title and time,
Snooze, Dismiss), shows on the pinned board (with Cancel), and leaves the list once it has rung, keeping its title as
a recent. Also reachable from the app icon's long-press menu (**New alarm**), a **New alarm** Quick Settings tile, and
an **Alarms** tile on the Library tab showing what rings next. Logged `dewidebug dailyalarm once-… one-off …`; reports
carry `dailyAlarms.oneOffs` and `dailyAlarms.recentTitles` (counts only).

## Reminders and the one-tap page (2026-10-29, ADR 92)

**Ask (Dewi):** a nightly *close the fridge* at 21:00, on the same board as the alarms, configurable; and changing or
adding a time without the five-tap trip through Edit.

- **🔔 Reminders** are daily alarms of a second kind: no morning question; at the time a heads-up with a sound
  (channel *Reminders*) saying *🔔 Close the fridge · ⏰ 21:00 · reminder 1 of 6*, with **✅ Done** and **Snooze 10 min**;
  again every 10 minutes (a setting under ⚙️ More: 5/10/15/30) up to six times; the pop-up can be swiped away (it comes
  back with the next nudge), and the board row stays until Done or the next day. Started late (a reboot, an update)
  it still nags within that hour. On the board: *21:00 Close the fridge · today · 📌 reminder*, then *🔔 now, until done* with
  **✅ Close the fridge: done**; while set, **Skip 21:00**. No status-bar alarm-clock icon and no ten-minute herald.
- **Close the fridge** at 21:00 every day is added once, switched on. Delete it and it stays deleted.
- **An alarm earlier than its first ask** (say 07:00 with the 08:00 ask) rings without asking, set from midnight;
  the board says *⏰ rings, no question*.
- **The board heading** stays on an asking or set alarm; a reminder set for tonight only heads it when nothing else
  is on, and has no countdown.
- **The card:** tap the big time and the clock opens (⌨️ Type it switches the dial to typing); **−15 / +15** beside
  it; tap a day initial to switch that day; **Skip today**, **✅ Done** or **Remind me after all** for a reminder,
  **Set for today** for an alarm; ⚙️ More for the title, ⏰ Alarm / 🔔 Reminder, each day's time, the question's
  choices and ask times (alarms) or the nag interval (reminders), and 🗑️ Delete with **Undo**. −15/+15 and the clock (which opens on
  the shown time) move the usual time, every day's own time, the choices and today's set time by the same amount (or
  cancel today, when the moved time has already gone). Undo brings back the alarm and its day.
- **➕ New alarm** is the only add: a title, ⏰ Alarm / 🔔 Reminder, **Once / Every day / Weekdays / Choose days**; Once
  keeps the +15 min … chips and the day, the others take a time. New ones start switched on. The old
  "Add a daily alarm" button (an alarm switched off at 17:30) is gone.
- **One-offs:** tap the time or the title to change it; a time already gone is refused.
- **Logs:** `dewidebug dailyalarm seeds offered=… alreadySeeded=… adding=…`, `… <id> retimed A -> B …`, `… retime moves
  today's state: …` (with why), `… reminder <id> "<title>" for 21:00: nudge n of 6, posted=…`, `… <id> new REMINDER …`,
  `… deleted from page (undo offered)` / `restored from undo`, `… new sheet Set: NewAlarmChoice(…)`. Reports carry each
  alarm's `kind` in `dailyAlarms.<id>.settings` and the `reminding` state in `.state`.

## Answer today early, on the page (2026-10-31)

Dewi: *"I want to be able to answer it myself … without waiting for the question"*, then *"it just looks like a bit
of labeled text, not a button I can press"*. Tapping the sticky board notification already opened this page, but
"Set for today" was a small title over filter chips that read as labels. Each daily alarm's card now has a tinted
**Today** panel (`TodayPanel`): today's status as its title, a line saying you can answer now (or that a time turns a
switched-off alarm on), every offered time as a filled ⏰ button (✅ on the one set), 🕐 Other time…, and 🚫 Not today
(❌ Cancel today once set). Not today works before the morning question: `AlarmEvent.Decline` from `Idle` settles
today as declined, so the question is not asked (`DailyAlarmMachineTest`). Tests: `SetTodayFromThePageTest` (the
times have the Button role; Not today before the question).

## ⚡ Quick alarm (2026-10-30, ADR 99)

The alarm notification has ⚡ Quick alarm: a screen over the lock screen with saved presets (🏃 Run up and down the
stairs ×5 to start; the last one used is chosen; long-press to rename or delete, ➕ to add) and the clock dial, 15
minutes ahead. ✅ Set makes a one-off alarm at that time (tomorrow if it has gone) that rings full screen.
[ADR 99](../adr/0099-a-quick-alarm-from-the-alarm-notification.md).

