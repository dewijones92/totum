---
title: The side-quest cards sit at the top and pop down on events
kind: adr
status: accepted
updated: 2026-10-15
---

# 54. The side-quest cards sit at the top and pop down on events

- Status: Accepted; supersedes the importance choice in [ADR 23](0023-pinned-notifications-alert-silently.md)
- Date: 2026-10-15

## Context

Dewi, 2026-10-15: *"I want to see all the Exsurge notifications stuff and alarms as priority, as like top notification
banner things, so on my lock screen"*. The Exsurge banner and the alarm board were on an `IMPORTANCE_DEFAULT`
channel (ADR 23): shown on the lock screen, but below anything of higher importance and never popping down.
The summons, the morning question, the ring and the "Sat down?" nudge were already high importance.

## Decision

- Both pinned cards move to **high importance**, still with no sound, no vibration and no badge, public on the lock
  screen (new channel ids `exsurge_pinned_v2` and `dailyalarm_board_v2`; Android does not let an app raise an
  existing channel, so the old ids are deleted).
- They pop down **only on events**, otherwise updating silently (`setOnlyAlertOnce`):
  - Exsurge (`bannerAlert`, `:lib:exsurge`): the summons is a minute away, a break starts, a break ends.
  - Alarms (`AlarmBoard.heralds`, `:lib:dailyalarms`): an alarm is within 10 minutes. The board now also refreshes
    at that moment (`refreshAt`), and the one it popped for is remembered across restarts.

## Consequences

- A first post of either card on the new channel pops once, so once each morning.
- Tests: `BannerAlertTest`, `AlarmBoardTest`; the device tests now pin `IMPORTANCE_HIGH`. The pop-ups were not seen on
  a device: that needs a live Exsurge session or a real alarm 10 minutes out.
