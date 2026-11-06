---
title: What's next, in the notification
kind: feature
status: built 2026-11-06 (tests to follow; device check owed)
area: player
updated: 2026-11-06
---

# What's next, in the notification

Dewi, 2026-11-06: the playback notification, and so the lock screen, says what plays after the current item.
`⏭️ Next: <title>` leads the content text, ahead of the artist ([ADR 131](../adr/0131-a-play-carries-its-reason-and-the-notification-says-whats-next.md)).

## What it says

| Situation | Content text |
|---|---|
| Something is queued after it | `⏭️ Next: <title> · <artist>` |
| Repeat one | `🔂 Repeating this one · <artist>` (no Next) |
| Explore is on | `🧭 Explore picks what follows · <artist>` |
| Repeat group, and this is the group's last item | `⏭️ Next: <the group's first item>` |
| Repeat queue, nothing after | `⏭️ Next: <the queue's first item of this kind>` |
| Nothing after it, auto-play off, the sleep timer stops here, or a reshuffled round | the artist only |

The title is the one the advance will actually start: `nextLineOf` (`app/…/playback/NextLine.kt`) answers in the
order `AutoAdvancer.advancePast` decides, and its last step is `nextAfterThePlace`, the function `peekNext` and the
advance share, so stay-in-kind ([ADR 64](../adr/0064-auto-play-stays-in-music-or-talk.md)) and skip-finished ([ADR 74](../adr/0074-the-queue-passes-over-finished-talk.md)) apply to
the line exactly as they do to what plays.

## How it gets there

`core/playback` cannot read the queue, so the app pushes it down: `startPlaybackContext`
(`PlaybackContextWiring.kt`) combines `activeModes` with `nextLineFor` into one `PlaybackContext` and sends it with
`PlaybackController.setPlaybackContext`. `ContextualNotificationProvider` shows the line only while the session's
current item is the one the line follows (`nextAfter`), so a track change never shows the old track's Next.

It refreshes on every input that can change the answer: any queue edit (reorder, remove, add, shuffle), the playing
item, the effective repeat (the kid lock included), Explore, auto-play, and the sleep timer.

## Diagnostics

`dewidebug playback context for the session: PlaybackContext(…)` on every change, then the service's
`dewidebug notification context … -> …; refreshing the notification`, and
`dewidebug notification next line held back: it follows X, playing Y` when a stale line is withheld.

## Known limits

- An item marked played elsewhere while another plays changes what the advance skips; the line corrects at the next
  queue or item change.
- Explore may find nothing related and fall back to the queue; the line still says Explore picks.
- A reshuffled repeat round has no known first item, so no line.

## Tests still to write

`nextLineOf` per row of the table (including kind and finished skipping); the context bundle round trip; the
provider's text with and without an artist, with repeat one, and held back for another item; a device check that
reordering the queue changes the line within a second and that the lock screen shows it.
