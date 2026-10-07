---
title: The break length is chosen at the summons and fixed for that break
kind: adr
status: accepted
updated: 2026-10-07
---

# 10. The break length is chosen at the summons and fixed for that break

- Status: Accepted
- Date: 2026-10-06

## Context

Dewi asked to choose the break length when Surgius summons him, with the last choice offered
next time. Until now the length was one setting (`breakMinutes`, default 5), and a running break
read it live. Changing the setting mid-break therefore moved the end of the break already under
way, and shortening it past the time already walked ended the break at once.

## Decision

- The takeover screen offers 2, 5, 10 and 15 minute chips. A value set elsewhere (e.g. 7 from the
  stepper) appears as an extra chip, so the selected length is always visible.
- A chip writes the existing `breakMinutes` setting. There is **one value**: the chip, the
  settings stepper and "last chosen" are the same number (Dewi's choice).
- GO, Just walk and Continue Totum each copy the setting into `Summons.breakMinutes` as the break
  begins. The running break (`OnBreak.length`/`endsAt`), the banner and the Exsurge screen all read
  that copy, so a later change applies from the next break and never moves the current one. A
  state saved before this field existed falls back to the setting.
- Dewi left the freeze decision to Claude ("up to you"). Claude chose to freeze, because a
  live read makes the chips able to end a break by surprise.

## Consequences

- Changing the length mid-break no longer affects that break. That is intended, and is a change in
  behaviour.
- The summons notification gets no length choice: Android shows three actions, and they are
  already used.
- The machine notes `<event>: break length fixed at Nm`, and a chip tap logs
  `takeover break length chosen=Nm`, so a report shows both the choice and the length applied.

## Addendum (2026-10-07): snooze length the same way

Dewi asked for snoozes to be unlimited and their length chosen on the popup. The snooze chips (5, 10, 15,
30 min) follow this ADR's pattern: a chip writes the one Snooze length setting, the last choice is
remembered, and an odd stepper value shows as an extra chip. Both rows of chips are one `MinuteChips`
composable with their own presets (`breakChoices`, `snoozeChoices`). The per-summons snooze limit and its
setting are removed; the summons notification is now GO, Snooze, Skip.
