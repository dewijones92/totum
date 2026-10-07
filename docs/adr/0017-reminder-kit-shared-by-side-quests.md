---
title: A reminder kit shared by the side quests
kind: adr
status: accepted
updated: 2026-10-07
---

# 17. A reminder kit shared by the side quests

- Status: Accepted
- Date: 2026-10-07

## Context

Exsurge et Disce owned every piece of alarm plumbing: exact alarms, alarm-channel voice, vibration
waveforms, a full-screen screen over the lock screen, choice chips, a JSON settings store and date/time
serializers. Daily alarms ([ADR 18](0018-daily-alarms.md)) needs all of them. Copying them would break the
repo's DRY law, and making the pickup alarm a mode of Exsurge would mix two unrelated jobs (Dewi chose the
kit, 2026-10-07).

## Decision

- **`:lib:reminders`** (pure JVM): `Waveforms`, `minuteChoices` / `timeChoices`, `nextOccurrence` (the
  next active weekday time, by wall clock) and the `Instant`/`LocalDate`/`LocalTime`/`DayOfWeek`
  serializers. Tests run in the normal gate.
- **`app/…/reminders/kit/`** (Android): `ExactAlarm` (exact with an inexact fallback, plus
  `setAlarmClock` for real alarms), `AlarmVoice` (a queue of recorded clips and text-to-speech, on the
  alarm channel), `AlarmTone` (the phone's alarm sound, looping), `Buzzer`, `showOverLockScreen`,
  `ChoiceChips`, `TimePickDialog` and `JsonPrefs`.
- Exsurge moved onto the kit with no behaviour change: its storage keys, clip choice and notification text
  are unchanged; only some log wording changed (`voice queued exsurge SUMMON`).

## Consequences

- A third reminder-like feature starts from the kit.
- Exsurge's permission checklist rows were not moved yet (notifications, full screen, exact alarms are
  the shared three); the Daily alarms screen does not show permissions. A follow-up.
