---
title: Row status — pillar, played, offline
kind: feature
status: shipped
area: ui
updated: 2026-09-21
---

# Every row says what it is

**Ask (Dewi, 2026-07-25):** items in *any* list should clearly state whether they're
played, whether they're downloaded offline, and whether they're YouTube or podcast.

One `MediaItemRow` serves every list, so this landed everywhere at once — feeds, search,
queue, history, playlists, Library, channel tabs.

## What a row shows

| Signal | Treatment | Why |
|---|---|---|
| **Pillar** | The antenna or video glyph — the same pair the bottom bar uses | Learnable at a glance; no legend needed |
| **In progress** | A thin sliver under the thumbnail | Says *how far*, which a label can't |
| **Played** | A cyan wash across the row, a bare check, and a dimmed title | The row recedes without disappearing, and a finished one is findable by colour alone |
| **Offline** | A circled down-arrow in the status line | Separate from the trailing button, which is the *action* |

Three deliberate choices:

- **The glyphs differ in kind, not just shape.** Offline was first a circled check, which
  sat next to the played check and read as one signal. A down-arrow can't be confused
  with a tick.
- **Quiet by default.** An unplayed, streaming item shows only its pillar. Status that
  shouts on every row stops carrying information.
- ~~**Titles cap at two lines.** Long podcast titles were running to five, which made every
  row a paragraph.~~ **Reversed 2026-09-21** on Dewi's instruction: nothing in the app truncates
  and every title wraps in full — see [text-wraps-never-truncates.md](text-wraps-never-truncates.md).
  A long-titled row really is four lines tall now, which is the trade he chose knowingly.

## The gap this exposed: "played" wasn't representable

Progress rows for finished items were **deleted** — so a finished item was
indistinguishable from one never started. There was no concept of *played*, only
*part-way*. Fixed at the root rather than papered over:

```
PlayState = Unplayed | InProgress(positionMs, durationMs?) | Played
```

- `playback_progress` gains `completedAtEpochMs` (migration **v12 → v13**). The row
  survives; restarting from the beginning becomes a property of *playback*
  (`resumePositionMs` reports nothing for a completed item) rather than of storage.
- Marked automatically at end-of-item, and by hand from the row's action sheet
  ("Mark as played" / "Mark as unplayed") — AntennaPod's most-used action.
- **Replaying clears it** once real progress accrues, but a trivial position does not:
  otherwise every replay would silently mark an item unplayed on its first tick.

## Why no screen had to change

Play state is provided **once**, around the whole shell, and read as `MediaItemRow`'s
default (`LocalPlayStates` / `LocalSetPlayed`). Ten screens and their view models needed
no plumbing; passing it explicitly still works, which is what previews and tests do.

The pillar is **required** rather than defaulted: mixed lists get it from the item's
`PlayHandle` (which knows exactly — `PlayHandle.pillar`), single-pillar screens state it
outright. Nothing sniffs a URL, and a new list can't forget to say which pillar it shows.

## Tests

- `core/domain/.../PlayStateTest.kt` — fraction maths (including unknown duration and
  positions past the end), `isPlayed`, negative positions rejected, `PlayHandle.pillar`.
- `core/database/.../RoomPlaybackProgressStoreTest.kt` — finishing marks played rather
  than forgetting; part-way reports its progress; marking by hand needs no prior
  playback; marking unplayed clears everything; replaying keeps the mark until real
  progress. 14 instrumented tests green on emulator-5554.

Verified on-device: marked a queue row played → `completedAtEpochMs` set in the database
→ the row gained its check and dimmed title.

## Follow-on this unlocks

Cheap now that play state exists: a **"hide played"** filter on the feeds, auto-advance
skipping played items, and an honest Library count ("3 played, 12 unplayed").

## Offline state is provided, not plumbed (2026-07-27)

`LocalDownloadStates` joins `LocalPlayStates` at the app root, and `MediaItemRow`
defaults to it. Every screen used to pass `downloadState` itself and two did not:
search results and new-item notifications passed a hardcoded `NotDownloaded`, so a
downloaded video showed as not downloaded on exactly the screens you would find it
from. Nobody chose that — a required parameter with a plausible value to hand is easy
to satisfy wrongly.

## Played was wrong twice (audited 2026-07-27)

Asked to check items are actually labelled played. The display was fine; what set the
state was not.

**Nothing marked an item played when playback reached the end.** The only route was the
progress store's heuristic — position within 15s of the end — saved by a ticker that runs
*only while playing*. It usually got there, because the last save lands within 5s of the
end, but it could never fire for an item with no known duration (a live stream), and it
was inference where a fact was available. `STATE_ENDED` now marks it directly.

**A flat 15s tail is wrong for short items.** It marked a 30-second Short played at the
**halfway point**, and a 60-second one at 75%. The tail is capped at 10% of the item, so
"nearly finished" means the same at any length: 30s → 27s (90%), 3h → unchanged at 15s.

Verified on device: playing to the end logs `ended`, writes `completedAtEpochMs`, and the
row renders `content-desc="Played"`.

## A played row wears a cyan wash (2026-09-21)

**Ask:** *"any played item I want the background color of it to have a tinge of a color? not sure
wha ttho???"* — cyan, chosen from the app's own palette after being offered cyan / green / lemon /
neutral.

- **Cyan, not tangerine**, because tangerine already means *active*: it is the play button, the
  now-playing equaliser and every primary action, so a finished row painted with it would say the
  opposite of what it is. Not green either — this brand has three hues and "done" is not worth a
  fourth.
- **Played only.** Part-way rows already carry the progress sliver and the queue labels the row it
  is on, so tinting those too would leave nothing untinted to compare against. Offered; his call.
- **The alpha is luminance-aware**, and that came from looking rather than reasoning: at 8% the
  light theme is plainly cyan and the dark theme is a *lighter grey band* — measurably bluer, and
  not blue to the eye. So the wash is 8% on a light surface and 14% on a dark one, keyed off
  `colorScheme.surface.luminance()` rather than a theme flag, so a scheme that is neither of ours
  still gets a decision. (The first version of this note said "the same 8% cyan" and blamed the
  surface alone. Round one of the gauntlet caught it: `secondary` is **Cyan40** in the light theme
  and **Cyan80** in the dark, so the tone differs too. Two washes, each chosen for its surface.)
- **Two backgrounds do not choose between themselves, they composite.** The Notifications tab passed
  its unread wash through `modifier` and the row painted the played wash on top, making a third
  colour that read as neither "new" nor "finished". `rowTint(playState, unread)` decides once —
  played wins, being the later fact — and `MediaItemRow` is the only thing painting a background.
- One seam, as ever: `playedRowTint` sits beside `playedTitleAlpha` in `MediaItemStatus.kt` and
  `MediaItemRow` draws it under the click, so the ripple still lands on top.

**Part-way rows are asserted NOT to be tinted**, since "played only" was a decision and a decision
nothing pins is one that drifts.

**Verified by pixel**, not by the colour function: `PlayedRowIsTintedTest` renders a played and an
unplayed row, captures each, and reads the corner pixel — in **both** themes. A test of
`playedRowTint` would stay green if the modifier were ordered behind the surface, applied to the
wrong node, or dropped entirely, and this repo has shipped exactly that shape of defect (eleven
green queue-row tests over rows that rendered solid red, cbf9916). Mutation-proven: deleting the
`.background(...)` fails both themes at the "should differ" assertion, and the unplayed control
reads the surface colour exactly, so "everything is tinted" and "nothing is" are distinguishable
outcomes rather than one indistinguishable pass.
