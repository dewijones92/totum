---
title: GO opens Loquax scrolled to the current lesson
kind: todo
status: PARKED 2026-10-07 — another agent is working in Loquax; agreed design below, needs a change in Loquax too
area: side-quest
priority: medium
requested: 2026-10-07
updated: 2026-10-07
---

# GO lands on the current lesson in Loquax

Dewi, 2026-10-07: *"actually open my current lesson … or actually pressing the target button so it
scrolls to the current one and then I click on it myself"*.

Today GO opens Loquax (`dev.hanzi.hanzi_practice`) at the route in the Exsurge settings, `/practice`.

## Agreed

- GO opens Loquax's lesson list **scrolled to the current lesson**, as its "target" button does. Dewi
  taps the lesson himself; Totum does not open the lesson.
- **Loquax is changed too** (Dewi's own app): it gets a route that means "scroll to current", e.g.
  `/learn?current=1`, and Exsurge's default route becomes that. No driving Loquax's UI from outside.

## What Loquax already has (read 2026-10-07, `~/code/dualingo-update` at f4f6ea9, 0.14.0+75)

- It **remembers the last-picked course** (`selectedLanguage` pref in `language_provider.dart`) and opens
  on it, so any route lands in that language.
- The "target" button is the Learn tab hero card's `onLocate`, which calls `_jumpToSkill(current)`
  (`features/home/home_screen.dart`). "Current" is `_currentSkill`: the skill of the most recent lesson
  activity, else the first playable one.
- A launch route already crosses the app boundary: `MainActivity` reads the `hanzi_route` extra,
  `app.dart` pushes it. Exsurge sends `LOQUAX_PRACTICE_ROUTE` = `/practice` (the Practice tab).

## Agreed details (Dewi, 2026-10-07)

- **Language:** whatever Loquax was last set to. Nothing chosen on the Totum side.
- **Current lesson:** the same rule as the target button, shared by both, not a second copy.
- **On arrival:** scroll to it **and highlight it briefly**; Dewi taps it himself.
- Sketch: a Learn-tab route such as `/learn?locate=current` that runs the target button's scroll and
  highlight once the path has laid out; Exsurge's default route becomes it (rename
  `LOQUAX_PRACTICE_ROUTE`).

## Open

- Asked and left unanswered on 2026-10-07: should a lesson finished in Loquax be reported back so
  "practised" means a lesson done; what to do when an older Loquax ignores the new route; confirm the
  move from the Practice tab to the Learn tab.
- Does Just walk change? (It opens nothing today.)
- Loquax had uncommitted work in `content_provider.dart`/`main.dart` on 2026-10-07, not from this
  session: check it has landed before starting.

## Done when

A Loquax build with the route ships, Exsurge's default destination uses it, the Exsurge destination
test covers the new route, and a report line records the route that was opened.
