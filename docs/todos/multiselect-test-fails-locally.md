---
title: MultiSelectTest fails on totum-api35 because the long-press never reaches the row; CI passes
kind: todo
status: open — refined 2026-10-14; in the 2026-10-14 batch to make deterministic
area: testing
priority: medium
requested: 2026-10-14
updated: 2026-10-14
---

# MultiSelectTest fails locally and passes on CI

On 2026-10-09 `com.dewijones92.totum.ui.MultiSelectTest` failed **7 of 12** on `totum-api35` (run with
`am instrument`): "could not find '1 selected'", "Select all", "Add to queue". The same 7 failed on the
previous `main` commit built from `git archive`, and CI's instrumented job passes the class. So it is
the environment, not a change, but a test that only passes on one screen is a test that can't be trusted
locally, and every playback change is verified locally first.

## What is known

- **The failing cases never log the row's `dewidebug long-press` line**, so the gesture does not reach
  the row's long-press handler. The test does `onNodeWithText(title).performTouchInput { longClick() }`.
- **The emulator's long-press timeout is the default** (`settings get secure long_press_timeout` = 400,
  checked 2026-10-14), so a slow-press setting is not it.
- **Geometry differs:** CI runs at 320×640 dp, 160 dpi; `totum-api35` is 1080×2400 px, 420 dpi.
- Exsurge on or off made no difference.

## Leads, cheapest first

1. **Something clickable sits under the title's centre at this geometry and takes the press.** The follow
   mark (ADR 22) shipped on 2026-10-08, the day before the first recorded local failure, and is a
   clickable child of the row. `longClick()` presses the centre of the *text* node; if the title wraps
   beside the mark, or the mark overlaps the title's bounds, the press lands on the mark. Check: dump the
   bounds of the title and the mark during the test (`printToLog` on the row), and screenshot the row.
2. **Geometry:** run the class with `wm size 320x640`, `wm density 160` (see the memory
   `reproduce-ci-android-ui-failure`). If it passes there, it is layout, not timing.
3. **A merged-semantics target:** if the title's node merges into the row (`clickable` merges children),
   the centre of the merged node may not be the centre of the text.

## Done when

The class passes 12 of 12 on `totum-api35` at its own geometry, three runs in a row, and still passes on
CI; the fix targets the row (a test tag on the row, pressed at a point that is the row's own) rather than
loosening what the test checks.
