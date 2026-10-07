---
title: The takeover uses a full-screen intent plus the overlay grant
kind: adr
status: accepted
updated: 2026-10-07
---

# 6. The takeover uses a full-screen intent, plus "display over other apps" when granted

- Status: Accepted
- Date: 2026-10-01

## Context

Dewi chose an alarm-style takeover. A full-screen intent launches its activity only when the screen
is off or locked. When the phone is unlocked and in use, Android shows it as a heads-up banner
instead (the AOSP full-screen-intent behaviour, confirmed by a second review on 2026-10-01).

## Decision

- Every summons posts a high-priority alarm notification with a full-screen intent to
  `TakeoverActivity` (shown over the lock screen, turning the screen on), carrying GO, Snooze and
  Skip actions.
- When "Take over even while using the phone" is on **and** "Display over other apps" is granted,
  the activity is also started directly, which that grant permits from the background. Dewi agreed
  to this grant on 2026-10-01.
- GO over a locked screen asks to unlock first (`requestDismissKeyguard`) and only then opens Loquax
  (`dev.hanzi.hanzi_practice`, extra `hanzi_route`). The route was `/practice`, an existing Loquax
  deep link. **Changed 2026-10-07:** it is now `/learn?locate=current` (`LOQUAX_CURRENT_LESSON_ROUTE`),
  which opens Loquax's Learn tab scrolled to the current lesson in the last-picked course and highlights
  it (Loquax ADR 19, Dewi's request). Settings saved before the change with the old default are moved
  once, when they carry no `destinationRouteVersion`; a route chosen afterwards is kept.

## Consequences

- Without the overlay grant, a summons while the phone is in use is a banner. The permissions
  checklist in Settings says which grants are missing.
