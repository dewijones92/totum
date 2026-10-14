---
title: The side quests look Roman and bold, with laurel gold that only they use
kind: adr
status: accepted
updated: 2026-10-14
---

# 34. The side quests look Roman and bold, with laurel gold that only they use

- Status: Accepted
- Date: 2026-10-14

## Context

Dewi, 2026-10-14: *"please improve the UI of the exsurge page and alarm page??? sexy???? while keeping
functional"*. Asked, he chose a Roman, bold look; a laurel gold added to the palette for the side quests
only; every Exsurge setting still visible but grouped into cards with icons; number settings as sliders
with value and unit, on the same steps and ranges as the old steppers; each alarm as a compact card with a
huge next time and an Edit for the rest; and the takeover, ring and question screens in scope.

## Decision

- **Laurel gold** lives in `theme/Color.kt` (`Laurel20…90`) and reaches screens through `LocalLaurel`,
  provided by `TotumTheme` in light and dark variants. Only the side-quest screens read it.
- **Exsurge hero** (`ExsurgeStatusCard`): Surgius inside a countdown ring, the countdown in display type
  ("12:34 until you rise"), the banner title and detail, a laurel rank badge, and the status actions as
  buttons. The ring's numbers come from one pure rule, `ringOf` / `countdownText` in `:lib:exsurge`.
- **Exsurge settings** in `QuestCard`s (on/off, hours, timing, behaviour, permissions, destination);
  every number is a `SnappingSlider` that commits once on release and can only land where the stepper
  could (`snapToStep`), logged as `[sidequest] slider "…" a -> b`.
- **Alarms**: name and switch, the next ring huge (`DailyAlarm.nextRing` in `:lib:dailyalarms`: the time
  set for today, else today's own time while it is still ahead and not done, else the next active day),
  today's state, the Set for today chips, laurel day initials, and Edit opening the old editor in full.
- **Takeover**: Surgius on a laurel medallion, a black-weight title and the rank. **Ring screen**: the
  time inside a laurel circle. **Question screen**: a heavier heading with a laurel rule.
- Every existing test tag stays (`exsurge-status*`, `exsurge-action-*`, `dailyalarm-*`); new ones:
  `exsurge-countdown`, `exsurge-rank`, `exsurge-slider-*`, `exsurge-time-*`, `dailyalarm-edit-*`,
  `dailyalarm-next-*`.

## Consequences

- The brand palette gains one colour, kept away from the media pillars.
- An alarm's editing controls are one tap further away; Set for today stays on the card.
