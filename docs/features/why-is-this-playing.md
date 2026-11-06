---
title: Why is this playing?
kind: feature
status: built 2026-11-06 (tests to follow; emulator look owed)
area: player
updated: 2026-11-06
---

# Why is this playing?

Dewi, 2026-11-06. Tap the playing item's title in the full player, or long-press the mini player and choose
**❓ Why is this playing?**, and a dialog says how it was chosen ([ADR 131](../adr/0131-a-play-carries-its-reason-and-the-notification-says-whats-next.md)).

## The reasons

| Reason | Says | Set by |
|---|---|---|
| `Chosen(TAP)` | ▶️ You tapped it. | `playNow`, `playAll` (the default), the queue's play-from-here |
| `Chosen(PEEK)` | 👀 You peeked at it, so the queue kept its place. | `peek` |
| `Chosen(LINK)` | 🔗 You opened a link to it. | a shared link |
| `Chosen(NOTIFICATION)` | 🔔 You played it from a notification. | the 🔔 list's rows, and the "is live" notification's Play (`MainActivity.EXTRA_FROM_NOTIFICATION`) |
| `Chosen(SHUFFLE)` | 🔀 You started a shuffle and this was the first one picked. | shuffle all, an artist's shuffle, an album's shuffle, a queue group's "shuffle and play"; the rest of the run reads as next in its group |
| `Chosen(PIN)` | 📌 You started it from a pinned shortcut. | `PinPlayer` (pinned row, home-screen shortcut) and the tile menu's Shuffle and "go to the song it started from" (`PinTileActions`) |
| `Chosen(SHORTS_REEL)` | 📱 You swiped to it in the Shorts reel. | the reel's run and its swipes |
| `Chosen(ASK_CARD)` | ✨ You picked it from an Ask card. | the Latest card |
| `Chosen(QUEUE_ROW)` | 📋 You played it from the queue. | a queue row, the up-next list, play-instead |
| `Chosen(BACK_OR_FORWARD)` | ⬅️ You went back or forward to it. | the trail tiles |
| `Chosen(SWITCH)` | 🎵 You switched between music and talk, and it was the one you left off. | the music/talk button |
| `Next(group)` | ⏭️ Next in the _group_ group. / Next in your queue. | the advance, ⏭ and gapless |
| `Previous(group)` | ⏮️ You went back to the one before it … | ⏮ |
| `RepeatOne` | 🔂 Repeat one is on, so it played again. | the advancer's replay |
| `RepeatGroup(group)` | 🔁 Repeat group is on, so the _group_ group started again. | `repeatGroupAfter` |
| `RepeatQueue` | 🔁 Repeat queue is on, so the queue started again from the top. | `playFromTheTop` |
| `Explore(relatedTo)` | 🧭 Explore picked it, related to _title_. | Explore's peek |
| `RelatedAfterQueue(relatedTo)` | 🧭 The queue ran out, so a video related to _title_ followed. | the empty-queue fallback |
| `Radio(station)` | 📻 _Radio · name_ picked it. | starting a radio, and its entries as the queue advances |
| `NewUpload` | 📥 It is a new upload from something you follow, played from your New group. | advancing into the 📥 New group |
| none | ❔ Totum has no record of why this one started: it began before the reason was recorded. | |

## How it is carried

`PlaybackQueue` is the one place a play starts (`becameCurrent`), so it is the one place that calls
`PlaybackController.setPlayReason(itemId, reason)`. The controller keeps the last reason against the item's id and
`PlaybackState.playReason` carries it only while that item is current, so it can never be shown for the wrong item.
A stream rescue replays the item without a reason and so keeps the one it had. A mode switch in place (Listen/Watch)
keeps it too.

## Consistency

Every way into playback names its reason: tap (every list, search, channel, podcast, playlist, history, library),
link, notification, pin, shuffle, Shorts reel, Ask card, queue row, trail, the music/talk switch, the advance (⏭, ⏮,
auto-advance, gapless), the repeat modes, Explore and the empty-queue fallback. Callers that still read as a tap are
ones where a tap is the truth (an album's or artist's Play, the history and Library rows, "play all" on a playlist,
an audiobook's ▶️). Added 2026-11-06 (consistency sweep): the pinned tile menu said "tap" instead of pin, shuffles
said "tap", and a notification's Play said "link". `PlaybackQueue.playAt` requires a reason, so a new internal
caller has to choose one.

## Diagnostics

`playing.why` in the report (`chosen-tap`, `next-in-queue[Peep Show]`, `unrecorded`, or `-` when nothing plays), the
log line `dewidebug why <id> is playing: <reason>` at every start, and `dewidebug why-is-this-playing asked in the
<full player|mini player> for <id>: <reason>` when the dialog opens.

## Tests still to write

The reason set by the pinned tile menu's Shuffle and song-seed actions, by each shuffle, and by the notification paths.

The reason set by each `PlaybackQueue` entry point and by `step` for a grouped, radio and New-group entry;
`playReason` only for the current item and kept across a recovery replay; the dialog text for every reason; an
emulator look at the title tap and the long-press menu; a check that tapping the title of a playing video in the
full player does not clash with the player's other gestures.
