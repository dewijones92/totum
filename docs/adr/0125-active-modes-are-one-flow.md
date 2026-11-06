---
title: Modes that are quietly on are one flow
kind: adr
status: accepted
updated: 2026-11-06
---

# 125. Modes that are quietly on are one flow

- Status: Accepted
- Date: 2026-11-06

## Context

Dewi, 2026-11-06, after the repeat-one album bug: a mode left on is invisible until it surprises. Repeat, Explore,
speed, the kid lock and the work-app quiet hours were each readable from their own place, so each surface that wanted
to show them would have re-derived them, and the effective repeat (the kid lock overrides the stored one) was already
spelt in two places.

## Decision

- `ActiveMode` (sealed) and `activeModesOf(ModeInputs)` in the app's `playback` package are the one pure rule for what
  is on and in what order. `AppContainer.activeModes` is the one flow, built by `activeModesFor` from the effective
  repeat (the same `KidRules.repeat` that `repeatNow` uses), Explore, the playing speed, the kid lock, the work-app
  status (`WorkAppsStatus.Quiet`, re-read at its own refresh time) and the sleep timer (on or off only, so its
  per-second ticks do not reach the flow).
- Only modes that can surprise are listed. Skip silences and volume boost are deliberate and visible in the quick
  strip; repeat off is the default; "shuffle up next" is an action. The shuffled repeat modes are the persistent
  shuffle.
- The mini player, the playback notification and diagnostics read the flow. The notification learns through a new
  `PlaybackContext` pushed with `PlaybackController.setPlaybackContext`, a custom session command rather than session
  extras: `CastStatus.publish` replaces the whole extras bundle, so a second writer would clobber the cast status.
  `PlaybackContext` is the seam the notification's other facts extend.
- A chip's tap opens the control it stands for: the player for most, the work-apps page for quiet hours.

## Consequences

- A new mode adds an `ActiveMode` case, an input to `ModeInputs` and a string; every surface shows it.
- `ContextualNotificationProvider` subclasses `DefaultMediaNotificationProvider` to change the content text, so it
  depends on that class's protected `getNotificationContentText`.
- The command is not re-sent if the service restarts without the app; the next change re-sends it.

## Tests still to write

See [active-modes](../features/active-modes.md).
