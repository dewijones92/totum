---
title: A restarted clock can run for its own length, for that sitting only
kind: adr
status: accepted
updated: 2026-11-13
---

# 117. A restarted clock can run for its own length, for that sitting only

- Status: Accepted
- Date: 2026-11-04

## Context

Dewi, 2026-11-04: *"when I click Restart Clock … it defaults to whatever it is, 40 minutes, but I want a slider … to
quickly change how much time in that particular instance (for example, change down to 20) … the default will be
whatever the default is and then I can quickly press OK"*. Restart clock (banner and Exsurge screen) started the
usual sitting length from now, with no way to shorten one sitting short of changing the setting.

## Decision

- **Restart clock opens a picker**: one translucent, lock-screen activity (`RestartClockActivity`) from both the
  banner button and the Exsurge screen, with a slider of the sitting range (5-minute steps) set to the usual length.
  OK restarts the clock; Cancel leaves it untouched.
- **The length belongs to that sitting**, like a break's length belongs to its summons (ADR 10): `Sitting.minutes`,
  null for the usual length; `ExsurgeEvent.RestartClock(minutes)`; the summons carries `satMinutes` so the takeover
  says how long you really sat. Any later sitting is the usual length again. Choosing the usual value stores null.
- Everything that reads a sitting's length (the wake alarm, the summons tick, the banner line and countdown) asks
  `Sitting.length(settings)`, never the setting directly.
- The old broadcast action stays in the receiver so a banner posted by the previous build still works until reposted.

## Update, 2026-11-13: the summons page has it too

Dewi: *"when the Loquax triggers after 40 minutes … give me an option on that page to restart clock … the same as restart
clock in the app … a slider … defaulting to 40 minutes. Use the same code."* The takeover now has a **Restart clock**
button that opens the same `RestartClockDialog`, set to the usual sitting length (40 minutes on his phone), and calls the
same `restartClock(...)` as `RestartClockActivity`, with the source `takeover`. No second picker or controller call.

## Consequences

- A notification button now opens an activity rather than sending a broadcast, so it collapses the shade and
  shows the picker over the lock screen.
- Covered by `ExsurgeMachineTest` (summons after the chosen length, the banner's limit, back to the usual length,
  clamped to the range).
