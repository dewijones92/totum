---
title: Queue-first playback (AntennaPod-style) + Queue tab
kind: todo
status: shipped — the frontmatter said "in-progress" until 2026-11-14 although the index and feature doc said shipped
area: playback
priority: high
requested: 2026-07-24
updated: 2026-11-14
---

# Queue-first playback + a Queue tab

**Ask:** AntennaPod has a Queue tab on the bottom bar. Everything you press in the
app is queued at the current position in the queue. Make that the **default**
behaviour here — but keep a "peek" option on long-press for items (video or
podcast).

This is the biggest structural change on the backlog: it makes the queue the
**spine** of playback rather than a side-car, which is squarely in line with the
Unified law (one playback entry point for both pillars).

## What exists today

- `PlaybackQueue` (`app/queue/`) holds only what plays *after* the current item,
  **in memory** (`MutableStateFlow`), lost on process death.
- Tapping a row calls each screen's own `play()` → `PlaybackController.play` (podcast)
  or `VideoPlaybackLauncher.play` (video). The queue is not involved, so tapping
  something **discards** the queue's relevance.
- Up-next is a section inside `FullPlayer`; there is no queue destination.

## Proposed shape

1. **One playback entry point.** `PlaybackQueue.playNow(item)` inserts the item at
   the current position and plays it, leaving the rest of the queue intact behind
   it. Every screen's `onPlay` routes through it — so "tap to play" and "queue"
   stop being two different code paths. (Both pillars, one seam.)
2. **The queue becomes persistent.** A Room-backed `QueueStore`, reusing the
   existing denormalized `PlaylistItemColumns` + `playlistItemFrom` mapper (same
   shape already shared by local playlists and play history — no third schema).
   A queue with its own tab must survive a restart.
3. **A Queue tab** in the bottom bar: the full queue (current item + up-next),
   drag-to-reorder, swipe/✕ to remove, tap to jump, clear all. `PlaybackQueue`
   already has `move`, `removeAt`, `clear`, `playFromQueue`.
4. **"Peek" on long-press** = play this **without touching the queue** (a one-off
   listen; today's behaviour), added as one more `SheetAction` in the existing
   `MediaItemRow` sheet, so it lands on every feed on both pillars at once.

## Decided (Dewi, 2026-07-25)

- **Peek = play without touching the queue.** A one-off listen/watch; the queue is
  left exactly as it was. Today's tap behaviour, demoted to a long-press action in
  the existing `MediaItemRow` sheet (so it lands on every feed, both pillars).
- **Account comes off the bottom bar** (top-right avatar, or inside Library), so the
  bar stays at five: **Videos / Podcasts / Queue / Search / Library**.
- `playNow` on an item already queued **moves** it to the current position rather
  than duplicating (matches how play history de-dupes).

## Grouped queue (Dewi's idea, 2026-07-25) — "a list of lists"

Asked whether "Play all" should replace the queue or insert after the current item,
Dewi proposed a third answer: make the queue **a list of lists** — "Play all" inserts
a **sub-list** that plays normally but can be batch-selected in the GUI.

That's better than either option I offered, because it dissolves the dilemma:
replacing loses your queue, inserting buries it — grouping makes inserting *safe*,
because an unwanted group is one gesture to remove.

**The design discipline that keeps it cheap: flat for playback, grouped for display.**

- Queue rows gain a nullable `groupId` + `groupTitle`. Playback ignores them entirely,
  so "what does next mean at a group boundary?" never arises — there is no tree in the
  player, and `playNextInQueue` is untouched.
- The Queue tab renders a header over each **contiguous run** of the same `groupId`.
  Drag an item out of a run and the run simply splits — display handles it, so there
  is no invariant to maintain and no repair logic.
- Batch actions per group: remove the whole group, move it, play from its start.
- Pillar-agnostic by construction: a group is a local playlist, a podcast feed, a
  channel, or an ad-hoc "Play all" — same tag either way.

So **"Play all" inserts a tagged run after the current item and never replaces the
queue.** The group columns go into the schema from the start — cheap now, painful to
retrofit into a shipped DB.

**Done when:** tapping anything anywhere queues-at-current-position and plays;
the Queue tab shows and edits that one queue, with grouped runs from "Play all"
removable in one gesture; it survives a restart; long-press → Peek plays without
disturbing it.

## Progress 2026-07-25

Shipped so far:

- **Persistent queue** (`QueueStore` + Room, DB v10) with display-only group tags,
  reusing the same denormalized column contract as playlists and history.
- **Queue tab** in the bottom bar (Videos / Podcasts / Queue / Search / Library);
  Account moved into Library. Grouped runs with one-tap "remove these", per-row
  reorder/remove, Clear all.
- **Tap plays through the queue** (`playNow`): every feed, Library, history, channel
  and playlist tap now plays *and keeps the queue*, which was the actual pain.
  Verified on-device: with a podcast queued, tapping a video played the video and
  the podcast was still queued afterwards.
- **"Play all" inserts a tagged run** after the current item instead of replacing
  the queue.
- `PlayableItem` unification landed first, so the queue, playlists and history all
  store one shape.

### Resolved: the queue now tracks a cursor, so Peek means something

Asked which way to go, Dewi said "go with what you think is sensible". The current-index
model won, because it turned out to fix two behaviours **worse** than Peek's ambiguity:

- `playFromQueue` **dropped every entry before** the one you tapped;
- auto-advance **consumed the head** — so listening destroyed the queue as you went.

With the playing item a queue *member* addressed by a cursor, jumping and advancing
just move the cursor: nothing is lost, and you can go back. `QueueSnapshot(entries,
currentIndex)` is the state; `upNext` is derived from it. The cursor persists too
(`queue_items.isCurrent`, DB v12), so a restart resumes where you were in the queue —
not just which items were in it.

Peek now genuinely differs: it plays without joining the queue **and clears the
cursor**, so the next advance restarts from the queue rather than pretending the
peeked item was a member. It's surfaced on both feeds as "Play once, don't queue it".

#### Previous note (kept for the reasoning)

The queue holds what plays *after* the current item; the currently-playing item
lives in the playback controller, not in the queue. With tap now preserving the
queue, **"Peek" (play without touching the queue) is behaviourally almost identical
to tapping** — the only difference is that `playNow` de-duplicates. So a "Peek" menu
entry would read as a duplicate of tapping, and it has deliberately **not** been
surfaced in the UI (the `PlaybackQueue.peek` API and its test remain).

Making Peek genuinely distinct means the queue tracking a **current index** —
i.e. the playing item becomes a queue *member* (AntennaPod's model), so a tapped
item visibly joins the queue and a peeked one doesn't. That's a moderate change to
the queue's shape and to the Queue tab. Worth doing only if the visible distinction
matters to you.

### Verified on-device (cursor model)

DB v12 migrated cleanly. Queued two episodes, tapped the **second**: it played, the
first **survived** at position 0, and `isCurrent=1` landed on the second. After a
force-stop and relaunch the Queue tab still showed both with "Now playing" on the
second — cursor restored, not just the list.

### Drag-to-reorder shipped 2026-07-25

Long-press the grip and drag. Hand-rolled (`ReorderState`) rather than adding a
dependency, and deliberately simple: it accumulates the drag and swaps one position
each time the distance passes a row's height, instead of mapping pointer positions onto
item bounds. That reads the same to the user, has no measurement edge cases, and — the
reason it matters here — works even though this list interleaves **group headers**, so
its lazy indices don't line up with the entry indices. Moves go straight through
`PlaybackQueue.move`, which carries the cursor with the entry it points at, so
reordering never changes what's playing. Three controls per row became two.

Verified on-device with raw motion events (a `swipe` can't express long-press-then-drag,
so `input motionevent DOWN/MOVE/UP` was needed): a ~535px drag moved the top row down
one place and persisted immediately; a ~585px drag moved it two places, confirming the
multi-swap loop. Both landed in the DB with no separate commit step.

### Not yet done (corrected 2026-11-14: done)

Routing Search-result taps through the queue was the one open item. It is done: a search hit plays through
`queue.playNow` (`SearchViewModel`, "queue.playNow(PlayableItem(item, PlayHandle.Video(watchUrl)), quiet = true)"), and
search hits have had stable video ids since `94463e75` (see [search-video-hit-ids](search-video-hit-ids.md)). The
`status` above was "in-progress" until 2026-11-14 although the index and [playback-queue](../features/playback-queue.md)
had said shipped for months.
