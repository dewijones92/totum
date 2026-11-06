---
title: The mini player shows every mode that is on
kind: todo
status: built 2026-11-06 (code first; tests to follow)
batch: A (2026-11)
priority: high
updated: 2026-11-06
---

# The mini player shows every mode that is on

Dewi 2026-11-06 (after the repeat-one album bug): one small row on the mini player lists what is quietly on —
🔂 repeat one, 🔀 shuffle, 🧭 Explore, ⏩ speed, 👶 kid lock, 🔕 quiet hours — and the notification shows 🔂 when
repeat one is on. `MiniPlayerBar.kt:50` already shows Explore and Listen; show the effective repeat
(`AppContainer.repeatNow`), not only the stored one.

## Built (2026-11-06)

See [active-modes](../features/active-modes.md) and [ADR 125](../adr/0125-active-modes-are-one-flow.md). Skip silences were left out (a deliberate choice, visible in the quick strip, off by default), and the sleep timer was added.
