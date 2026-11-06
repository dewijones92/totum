---
title: The mini player shows every mode that is on
kind: feature
status: built 2026-11-06
area: player
updated: 2026-11-06
---

# The mini player shows every mode that is on

Dewi, 2026-11-06, after the repeat-one album bug: a mode that is quietly on should be visible without opening
anything. One flow, `AppContainer.activeModes` (`ActiveModes.kt`, `ActiveModesWiring.kt` in the app's `playback`
package), lists what is on; the mini player, the playback notification and diagnostics all read it
([ADR 125](../adr/0125-active-modes-are-one-flow.md)).

## What counts

| Chip | On when | Tap |
|---|---|---|
| 🔂 Repeating this one | the **effective** repeat is one (`repeatNow`: the kid lock forces it, not only the setting) | opens the player |
| 🔁 Repeating the group / the queue | effective repeat is group or queue | opens the player |
| 🔀 Shuffled | the effective repeat reshuffles each round | opens the player |
| 🧭 Explore on | Explore is on and the kid lock is not | opens the player |
| ⏩ Playing at 1.5x | the playing speed is not 1x | opens the player |
| 👶 Kid lock | the kid lock is on | opens the player |
| 🔕 Work apps quiet | the work-app quiet hours are snoozing now (`WorkAppsStatus.Quiet`) | opens the work-apps page |
| ⏰ Sleep timer on | any sleep timer is armed | opens the player |

Left out on purpose: skip silences and volume boost are chosen deliberately in the quick strip and read there, and
repeat off is the default, so neither would be a surprise. A one-off "shuffle up next" in the Queue header is an
action, not a mode, so it has no chip; the shuffled repeat modes are the only persistent shuffle.

## Where it shows

- **Mini player**: one wrapping row under the controls (`ActiveModesRow`), absent when nothing is on.
- **Playback notification**: `🔂 Repeating this one` leads the content text when repeat one is on. The app tells the
  session through `PlaybackController.setPlaybackContext(PlaybackContext)` (a custom session command, held until the
  controller connects); `ContextualNotificationProvider` stores it and refreshes the notification. `PlaybackContext`
  is the one seam later notification facts (Next line, why is this playing) extend.
- **Diagnostics**: `playback.activeModes` (`repeat-one | explore | speed-1.5 …`, or `none`) and a `modes` log line
  `dewidebug active modes <before> -> <after>` on every change; `dewidebug mode chip … tapped` on a tap;
  `dewidebug telling the session` and `dewidebug notification context` on the push.

## Tests still to write

`activeModesOf` per input (effective repeat under the kid lock, Explore hidden under the kid lock, speed tolerance,
order); `activeModesFor` against fakes (a settings change, a speed change, quiet hours starting at the window edge,
the sleep timer's per-second ticks collapsing to one emission); `ActiveModesRow` shows the chips and routes the
quiet-hours tap; `PlaybackContext` bundle round trip; `ContextualNotificationProvider` text with and without an
artist; a device check that the notification flips within a second of changing repeat.
