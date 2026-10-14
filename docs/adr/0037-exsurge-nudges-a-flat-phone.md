---
title: Exsurge nudges when the phone has lain flat while it is off
kind: adr
status: accepted
updated: 2026-10-14
---

# 37. Exsurge nudges when the phone has lain flat while it is off

- Status: Accepted
- Date: 2026-10-14

## Context

Dewi, 2026-10-14: forgetting to turn Exsurge back on leaves it off for good, with only the silent banner.
He chose a nudge from the phone lying flat (one minute, his change from five), not a sitting clock driven
by it.

## Decision

- While Exsurge is **off**, the nudge is on (a new setting, default on) and it is **inside active hours**,
  `FlatPhoneNudge` listens to the accelerometer, batched (10 s report latency). Re-checked once a minute.
- `FlatStillness` (pure, `:lib:exsurge`): face up or down within 15° of flat, gravity within 0.6 m/s², and no
  axis moving more than that between samples; any movement starts the count again.
- `flatNudgeFor` decides: flat and still for `flatNudgeMinutes` (default 1, 1–15) and no nudge in the last
  hour → one heads-up notification, "Sat down? … Turn Exsurge on?", with **Turn on** and **Not today** (no
  more nudges until tomorrow); no sound, one 200 ms buzz (Dewi's choice, so a phone face down is noticed).
- The listener lives in the app process, which the always-pinned alarm board keeps alive; nothing new
  runs in the background when Exsurge is on or outside its hours.

## Consequences

- A phone in a pocket never nudges; a phone left on a desk while you walk around can.
- Every decision is logged once per change of reason (`flat-phone nudge not yet: …`, `posted`,
  `listening=`), so a report says why a nudge did or did not come.
