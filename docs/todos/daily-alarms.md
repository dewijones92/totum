---
title: Daily alarms — Totum asks each morning, then rings (starting with toddler pickup)
kind: todo
status: built 2026-10-07 — stage A (kit) and B (daily alarms, off by default) shipped; see features/daily-alarms.md
area: side-quest
priority: high
requested: 2026-10-07
updated: 2026-10-07
---

# Daily alarms

Dewi, 2026-10-07: *"an alarm clock that asks me every day: do I want a half-five alarm to pick up my
toddler?"* A side quest beside Exsurge, separate from the media pillars.

## Agreed (Dewi's answers, 2026-10-07)

- **General feature, shipping with one alarm**: "Pick up time", 17:30. A list of daily alarms, each with
  its own label, days, times and question schedule. **Several alarms a day** allowed (e.g. drop-off too).
- **Totum rings it itself** (not the Clock app): full screen over the lock screen, ringing and vibrating
  **until dismissed**, with Snooze 5 min; pauses Totum's playback while ringing.
- **Sound:** the phone's alarm tone **plus a spoken line**, on the alarm channel (rings on silent).
- **Asking:** weekday mornings by default (08:00, Mon–Fri, configurable), a notification with
  **Yes 17:30 · Other time · No**. "Other time" offers chips (e.g. 17:00 / 17:15 / 17:45 / 18:00) and
  **a clock picker for any time**.
- **No answer:** ask again **every hour until 16:30**, one last louder ask at 16:30, then stop for the day
  with **no alarm**.
- **Custom times, all four:** edit the chip choices; a **different default time per weekday**; pick any
  time on the day; several alarms a day.
- **Label:** editable per alarm, default "Pick up time"; names stay out of the code, stored on the phone.
- **Configuration:** its own Settings section, "Daily alarms", next to Exsurge et Disce, plus a Quick
  Settings tile.
- **Code shared with Exsurge** through an extracted **reminder kit** (exact alarms, full-screen ring
  screen, alarm sound and voice, vibration waveforms, minute/time chips, settings widgets). Exsurge's
  behaviour must not change.

## Plan sign-off (2026-10-07)

Plan page: `C:\Users\DewiJones\claude-html\20261007-1635-totum-daily-alarms-plan.html`. Approved with:
- The spoken line is the phone's text-to-speech reading the label.
- An alarm and an Exsurge summons at once: **the alarm wins**; the summons waits until it is dismissed.
- Once set, a quiet "Alarm set for 17:30 · Change · Cancel" notification stays until it rings.
- Stages: A reminder kit (refactor, ships alone, Exsurge identical) → B daily alarms, off by default,
  "Pick up time" 17:30 created → C Dewi switches it on.

## Defaults agreed before building (Dewi, 2026-10-07)

- Other-time chips: 17:00 · 17:15 · 17:30 · 17:45 · 18:00 (editable), plus the clock picker.
- Spoken while ringing: "<label>. It's half five." (the time in words), repeated between rings.
- Snooze on the ring screen: 5 min, unlimited, until dismissed.
- Stages A and B may be pushed once gate-green and device-tested; B ships switched off.

## Open (for the plan)

- Module layout of the kit (pure part vs Android adapters), and how Exsurge migrates onto it without
  behaviour change (its tests as the guard).
- Where alarm state lives (Exsurge keeps its own store, ADR 2) and how it survives reboot/update.
- Voice line generation (Exsurge uses pre-generated clips; a label typed by Dewi needs TTS on the phone).
