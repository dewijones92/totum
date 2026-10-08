---
title: Daily alarms — asked each morning, rung by Totum
kind: feature
area: side-quest
status: built 2026-10-07 — ships switched off; awaiting Dewi's first weekday
updated: 2026-10-08
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
| Exsurge at the same time | The alarm wins; Exsurge holds its takeover and voice until it is dismissed |

- **Several alarms**, each with its own label, days, usual time, **a time per weekday**, time choices,
  first and last ask. Settings → **Daily alarms**, and a Quick Settings tile showing today's state.
- Ships with "Pick up time" 17:30, **switched off**.

**The screen stays awake** (Dewi, 2026-10-08) on the ringing screen, the morning question and the alarm
settings, at the phone's normal brightness, through the kit's `KeepScreenAwake`.

## Where it lives

- `lib/dailyalarms/` — `DailyAlarm`, `DailyAlarmMachine` (per-day states), `spokenLine`, `AlarmCodec`.
- `app/…/dailyalarms/` — `DailyAlarmController`, `AndroidDailyAlarmPorts`, `RingService`, `QuestionActivity`,
  `RingActivity`, `DailyAlarmNotifications`, `DailyAlarmReceiver`, `DailyAlarmTileService`,
  `DailyAlarmsScreen` / `DailyAlarmsActivity`.
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
