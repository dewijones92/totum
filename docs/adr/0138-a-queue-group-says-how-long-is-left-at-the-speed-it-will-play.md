---
title: A queue group says how long is left, at the speed it will play
kind: adr
status: accepted
updated: 2026-11-06
---

# 138. A queue group says how long is left, at the speed it will play

- Status: Accepted
- Date: 2026-11-06

## Context

Dewi, 2026-11-06 (batch A): each queue group header says how long is left, for example "4 h 12 m left at 2x", and
"at least" when a length is unknown. A group's remaining time depends on what will actually play: finished talk is
passed over (ADR 74), music and live streams never speed up, and the chosen speed is one global value that lived only
inside the playback controller's speed store.

## Decision

- **One pure answer** (`timeLeft` in `:core:domain`, `groupTimesLeft` in `:core:data`): for each item still to play,
  its remaining length is its length minus its position (an `InProgress` state knows both, else the item's own
  duration), divided by the speed it will play at, and the total is rounded up to whole minutes so the header does not
  recompose on every progress save. An item with no known length (and every live stream) adds to `unknown`, which turns
  the wording into "at least"; a group with nothing known is not annotated.
- **"Still to play" is the queue's own rule.** An entry counts when it is at or after the cursor (all of them when
  nothing is playing) and `PlaybackQueue.passOver` does not pass it over, which is the rule stepping uses, so a finished
  episode, which the queue will skip, is not counted, and music and a repeat mode are respected.
- **The speed is the speed it will play at.** `MediaContentKind.playsAtSpeed` is the rule "music and live play at 1x,
  everything else at the chosen speed", moved out of `tuningFor` so playback and this estimate cannot disagree. The
  chosen speed is read from `PlaybackSpeedStore.chosen`, a new state flow the app's one store keeps current, exposed as
  `AppContainer.chosenSpeed`, because the controller's own state reports 1x whenever music is playing. The speed is
  named in the line (in the player's own `speedLabel` wording, "2x") only when it changes something: at 1x, or in a
  group of music, the line is just "⏳ 4 h 12 min left".
- **Endless radio groups say nothing**: their time left has no end.

## Consequences

- A group that has already been passed (wholly before the cursor) shows no line.
- A split group shows the same figure on both of its headers.
- Hours are written "4 h 12 min", to match the existing "N min" wording (`minutesLabel`).
