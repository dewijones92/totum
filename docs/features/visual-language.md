---
title: One visual language across the app
kind: feature
status: shipped
area: ui
updated: 2026-09-25
---

# One visual language

Dewi, 2026-08-07: *"make whole app sexy please without losing funcitonality :) ... i trust you to
make it slick and sexy"*.

## The leverage: one row, ten screens

Videos, Search, Library, Queue, History, Playlists, Channel, Podcasts and Notifications all render
through `MediaItemRow`. Restyling it restyles the app — which is also why it is the riskiest thing in
here: one dropped affordance is nine screens losing it at once.

| Change | Why |
|---|---|
| Artwork **120×68** (was 96×54) | The artwork is the fastest thing to recognise in a list and it was the smallest thing in the row. Same 16:9, so nothing is cropped |
| Corners **12dp** (was 8dp) | At the larger size 8dp reads as an almost-square with the corners knocked off |
| Title at **titleSmall** (was bodyLarge) | It was set at the same weight as the subtitle under it, so a row had no hierarchy at all |
| Vertical padding **10dp** (was 16dp all round) | Every row was a third taller than its artwork needed; a screenful now holds seven items instead of five |

## The player

See [player-redesign.md](player-redesign.md) — the surface takes its colour from the artwork, a
left-aligned header, and one control strip where five stacked rows used to be.

## The frame

Bottom navigation animates the selected icon up 15%. The filled/outlined swap alone is a small
signal; scale makes which tab you are on readable at a glance rather than something you look for.

## Not losing anything, mechanically

Two guard tests, both written **against the design as it was** and kept passing through:

- `MediaItemRowKeepsActionsTest` — the row's title and channel, the LIVE and members-only badges, the
  duration, tap-to-play, and the long-press sheet with its actions.
- `PlayerKeepsEveryControlTest` — every control on the video player and on the audio player.

The badges have their own case for a reason: they are pills rather than subtitle text precisely
because the subtitle truncates, and *"you cannot actually play this"* must not be the part that gets
cut. A restyle that tidied them into the subtitle would look neater and be worse.

## Accessibility gaps found on the way

Three icons carried `contentDescription = null` beside otherwise-unlabelled controls:

- **Volume boost** — "Off / Low / Med / High" with nothing saying what they set
- **Playback speed** — "1× / 1.5× / 2×", likewise
- **Every bottom-navigation tab** — the label below is decoration a screen reader may not reach

All three found by writing inventories that could not name them either, which is a decent argument
for writing one.

## The overhaul (2026-09-25)

Dewi: *"overhaul the UI to your liking please :D i dont wanna loose functionalty tho. be bold"*. His
picks when asked: a **floating dock** plus **bold display type**, a **bundled typeface**, **one big
release**, the list filter **behind an icon**, and unplayed items marked by a **dot and a bolder
title**. Before/after screenshots were taken on `totum-api35` in both themes.

| Surface | Now | Seam |
|---|---|---|
| Type | Bricolage Grotesque for display, headline, title and label styles; body stays on the system font | `theme/Type.kt` — see [brand.md](brand.md) |
| Shape | 8 / 12 / 18 / 24 / 32dp, and the surface-container tones (lowest…highest) the scheme never set | `theme/Shape.kt`, `theme/Theme.kt` |
| Shell | Mini player and tabs in ONE floating rounded card; the selected tab is a tangerine pill | `ui/common/Dock.kt` |
| Tab titles | A big display title opens every tab. The Videos title is the feed you are on | `ui/common/ScreenHeader.kt` |
| Header actions | Filter, sort and the notifications bell sit in the title row | `FilterToggle`, `SortControl` |
| Filter field | Hidden behind the ⌕ toggle in every list's header; stays open while it holds text; clearing closes it. Pickers keep it open | `FilterField(hosted = true)` — [list-filter.md](list-filter.md) |
| Sources | Channels and shows as an avatar strip, one component for both pillars | `SourceAvatarStrip` in `SourceChip.kt` |
| Play-state filter | Segmented buttons | `MediaFilterChips` |
| Rows | Rounded cards carrying the SAME pillar wash and played wash; an unplayed dot on the artwork and a bold title; ⋮ and download stacked so the title gets the width; no dividers | `MediaItemRow` |
| Player | Sleep, skip silences, speed, boost, auto-play, listen and fast start as a two-column tile grid; tonal like / dislike / save; up next as cards with artwork | `ui/player/ControlTiles.kt` |
| Library | A 2×2 grid of colour tiles, a storage meter, downloading and failed downloads as cards | `LibraryScreen` |
| Source pages | A hero on a pillar-wash gradient with a full-width subscribe button | `SourceHeader` |
| Empty states | A brand-colour blob behind the icon | `EmptyState` |

What did NOT change, on purpose: one fact per line with its emoji, nothing truncates, both row
washes, no swipe-to-delete, and every control is still reachable.

**One misframed question, stated.** The unplayed-marker question described today's row tint as an
*unplayed* tint. It is not: rows wear their pillar's wash (Dewi, 2026-09-24) and a played row adds
cyan (2026-09-21), both explicit asks. So both washes stayed and the dot was added on top of them,
rather than replacing a tint he had asked for.

Guard tests, in the order that makes them worth having:

- `PlayerKeepsEveryControlTest` was **extended first** — sleep timer, skip silence, auto-play, fast start,
  boost and listen on both players — and passed against the old player before the tiles existed.
- `PlayedRowIsTintedTest` samples inside the card's top padding now that the row sits in a margin, and was
  mutation-checked again: removing the row's background fails both themes.
- `ListFilterTest` opens a hosted filter from its toggle, with cases for the toggle itself, for the keyboard
  NOT coming back when the field scrolls back into view, for the toggle surviving an emptied list, and for
  an emptied queue coming back unfiltered — each seen to fail first.
- `ScreensKeepEveryControlTest` (new): every Library tile opens its page, the signed-in Videos header offers
  the bell, the sort and the filter, and Clear all empties the queue.
- `PlayerKeepsEveryControlTest` now also asserts the controls can be PRESSED, not just found — a tile that
  lost its click would otherwise stay green — and that listening to a video still offers "Watch video".

## The review (Opus, 2026-09-25)

No critical findings; everything traced was still reachable. Of nine important ones, all were fixed:
the keyboard re-grab above, the stale queue filter, Up next showing blank art where every list showed the
show's (it now uses the same `withArtworkFrom`), the Listen rule and the artwork-shape rule each written
twice, the card chrome copied four times (now one `rowCard` modifier), the filter toggle reaching only four
lists, comments removed while their code survived (restored), and the reachability gaps in the tests.
From the suggestions: the dock no longer reads each tab name twice to a screen reader and is a selectable
group; avatar names take two lines each so the strip no longer changes height as it scrolls; the player
tiles are keyed; the filter toggle announces open or closed and logs a tap that changes nothing; one
dark-surface check; one checked-options menu for sort and the tile pickers; every sub-screen uses
`BackHeader`. Left as they are, by choice: the Videos bell scrolls away with the header, as the whole
header does, and a row with both a menu and a download is taller than before because the two stack.

**A second review, of the fixes (Opus).** It found the channel field drawn under the list (the fix put it
in a Box), an open filter that could not be closed once its list was swapped for an empty state, the
podcast page's field opening off-screen, the account playlist page still showing its field, and a guard
that passed vacuously: "Play" matched the Auto-play tile and "Like" matched Dislike. All fixed, each with a
test seen to fail first; the player guard now matches exact labels from resources, and was mutation-checked
by making Dislike unclickable. The player tiles now sit in one keyed grid, so a tile keeps its state when
Speed or Listen comes and goes.

## Deliberately not done

- **Item entrance animations in lists.** They look good in a demo and cost frames on a 400-item feed,
  which is what Dewi's actually is.
- ~~**A shared card treatment for every list.**~~ Done in the 2026-09-25 overhaul: the row's own wash
  became the card, so there is still exactly one surface per row rather than two nested ones.
