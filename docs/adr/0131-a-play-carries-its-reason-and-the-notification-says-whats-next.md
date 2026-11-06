---
title: A play carries its reason, and the notification says what is next
kind: adr
status: accepted
updated: 2026-11-06
---

# 131. A play carries its reason, and the notification says what is next

- Status: Accepted
- Date: 2026-11-06

## Context

Dewi, 2026-11-06: two questions the app could not answer. "Why is this playing?" had no stored answer at all: only
`byYou` and log strings existed, so a screen could not say "next in the Peep Show group" or "Explore picked it". And
the playback notification should say what comes next, which `core/playback` cannot read: it has no queue.

[ADR 125](0125-active-modes-are-one-flow.md) already built the seam for the second: `PlaybackContext`, pushed by
`PlaybackController.setPlaybackContext` as a custom session command and read by `ContextualNotificationProvider`.

## Decision

### The reason

- **One pure type, `PlayReason`, in `:core:domain`** (with `ChosenWay`): `Chosen(way)`, `Next(group)`,
  `Previous(group)`, `RepeatOne`, `RepeatGroup(group)`, `RepeatQueue`, `Explore(relatedTo)`,
  `RelatedAfterQueue(relatedTo)`, `Radio(station)`, `NewUpload`. A way is how you chose it: tap, peek, link, pin,
  Shorts reel, Ask card, queue row, back or forward, the music/talk switch.
- **The queue sets it, once, where a play starts.** `PlaybackQueue.becameCurrent` is the single place playback
  starts, so it is the single place that tells the controller (`PlaybackController.setPlayReason(itemId, reason)`).
  Every entry point names its reason: `playNow`, `playAll`, `peek` and `jumpTo` take one (a tap by default; the other
  ways override it); `playAt` **requires** one, so a new internal caller cannot forget; `step` derives it from the
  entry it lands on (`arrivedNext`: a New-group entry is a new upload, a radio group is the radio, anything else is
  next in its group or in the queue); the repeat paths and Explore name theirs.
- **A recovery replay keeps the reason.** Only a fresh play passes one; the retry rungs pass none, so a stream
  rescue does not rewrite why the item started.
- **The controller keys it by item id** and puts it on `PlaybackState.playReason` only while that item is current,
  so a reason can never be shown against the wrong item, and a state that began before this was recorded reads
  "unrecorded" rather than inheriting another item's.
- **Shown** by tapping the title in the full player and a long press on the mini player (menu entry), as a dialog
  (`WhyIsThisPlayingDialog`), and written to diagnostics (`playing.why`) and the log
  (`dewidebug why <id> is playing: <reason>`).

### The Next line

- **It rides `PlaybackContext`** (new fields `nextTitle`, `explorePicksNext`, `nextAfter`); no second channel. The
  app is the only writer: `startPlaybackContext` combines `activeModes` with `nextLineFor`, so a mode change and a
  queue edit both produce one context.
- **The rules mirror the advancer's order.** `nextLineOf` (a pure function beside `AutoAdvancer`) answers, in
  `advancePast`'s order: sleep timer stops here, repeat one, auto-play off, repeat group at its end, Explore, then
  `nextAfterThePlace` (the function `peekNext` and the advance use, same stay-in-kind rule and finished-skipping)
  and finally repeat queue's first item. What it cannot know it says nothing about: a reshuffled round, a related
  video that Explore may not find, a queue with nothing after.
- **Only for the playing item.** `nextAfter` names the item the line follows, and the provider shows the line only
  when the session's current item is that one, so the moment between a track change and the new context never shows
  the old track's Next.
- **Refreshes on every input** that can change the answer: queue contents and cursor, the playing item, effective
  repeat, Explore, auto-play, the kid lock, the sleep timer. Replacing 🔂 with Next follows the advancer: repeat
  one shows "🔂 Repeating this one" and no Next.

## Consequences

- A new way to start playing adds a `ChosenWay` (or a `PlayReason`), a string and a `when` branch; the compiler
  lists every `when` that must learn it.
- `nextLineOf` restates the advancer's order of decisions. A change to the advancer's order must change it too;
  its tests (owed) pin the pair.
- The finished-skipping rule reads live play states, which are not an input to the flow: an item marked played
  elsewhere while another plays corrects the line at the next queue or item change, not at once.
- Radio and a New-group entry are recognised by group id (`RADIO_GROUP_PREFIX`, `NewUploads.INBOX`); an item added
  to a shuffled group by the new-uploads check reads as "next in the group".

## Tests still to write

`nextLineOf` per input; the reason chosen by every `PlaybackQueue` entry point and by `step` for a grouped, radio
and New entry; `PlaybackState.playReason` only for the current item (and kept across a recovery replay); the
`PlaybackContext` bundle round trip; the provider's text with and without an artist and held back for another item;
the dialog text for every reason.
