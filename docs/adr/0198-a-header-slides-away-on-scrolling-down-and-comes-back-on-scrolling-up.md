---
title: A header slides away on scrolling down and comes back on scrolling up, from one place
kind: adr
status: accepted
updated: 2026-11-28
---

# 198. A header slides away on scrolling down and comes back on scrolling up, from one place

- Status: Accepted (built, unit-tested and driven on `totum-api35` at 1080x2400 and 320x640@160; not yet seen on Dewi's Pixel 7)
- Date: 2026-11-28
- Amends: [ADR 61](0061-the-ui-does-no-work-per-playback-tick.md) (the Queue's large title no longer shrinks; the
  title-feedback paragraph and `CollapsingTitle` are gone), [ADR 66](0066-one-sort-and-filter-menu-everywhere.md)
  (`CollapsingTitle` is now `ActionsHeader`, with no 104 dp minimum), [ADR 24](0024-music-tab-and-search-in-every-header.md)
  (the Queue's header is `ActionsHeader`)

## Context

Dewi, diagnostics report 20261128T021751 (v0.1.973, Pixel 7), with a screenshot of the Queue tab: *"is there a way to
slightly organise the stuff better on this screen? … in some apps when you scroll one direction … all the controls are
visible but you scroll the other direction … the controls hide so there's more screen space for the items in the list."*

Above the list the Queue tab spent about 37% of the screen: the device chips, an empty gap of about 60 dp, the title row,
a second row of actions and the offline banner.

- **The gap has two sources** (found by reading the layout bounds with `uiautomator` on `totum-api35`, 1080x2400 at 420 dpi):
  1. `CollapsingTitle` reserved `ExpandedHeight` = 104 dp and sat its title on the bottom of that box, so a 32 sp title
     (about 50 dp with its padding) left about 54 dp of nothing above it. Fixed here.
  2. The chip row is as tall as its tallest chip, and the row is top-aligned. A device that is playing shows its title in
     its chip's second line, which wraps (ADR 152, `aDevicesChipSaysWhatItIsDoingInWordsThatWrap`): on the emulator
     "📺 Living Room" with a three-line tennis title is 82 dp tall (and off screen to the right, behind the scroll hint),
     the other chips are 50 dp, so the 4 dp row padding plus about 36 dp under every shorter chip is blank. Dewi's own
     screenshot shows the same blank under his three visible chips. **Not changed here**: shortening it means a chip
     stops saying what its device is playing, which is a product call (see Consequences).
  Only the Queue shows the chip row (`hasItsOwnDevicePicker`); every other tab shows the slim Play on row, which has no gap.
- **Every header was fixed or in the list.** Videos, Podcasts, the Library hub, Downloads and a few pages put their header
  in the list as the first item (it scrolls away with the rows and returns only at the very top); the Queue, Search/Music,
  the Library pages, groups, playlists and source pages put it above the list and never moved it.

## Decision

### One seam

- **`HeaderScroll`** (`ui/common/HeaderScroll.kt`) is the behaviour, held once per host. It is a `NestedScrollConnection`
  that **consumes the drag in `onPreScroll`** (the Material "enter always" pattern): a drag of the content upwards (scrolling
  down) first hides the header, and only then lets the list move; a drag the other way, any distance, shows it first and
  then lets the list move. The state is one fraction, `hidden` (0 shown to 1 gone), converted from pixels with the travel
  (the summed height of every header piece currently on screen), so a piece appearing or leaving mid-scroll cannot jump it.
- **`Modifier.scrollsAway()`** puts a piece of a header on that state. It is a layout modifier, so the fraction is read in
  the layout pass: scrolling relayouts the header and the list under it and **recomposes nothing** (ADR 61). Each piece
  slides up inside its own clipped box and the box shrinks by the same fraction, so the list gains the height; pieces move
  together. A `pointerInput` on the piece (Initial pass, never consumes) **shows the whole header when it is touched while
  part-hidden**, so a control that opens a sheet or a dialog is never left half off screen.
- **Reused, not rebuilt:** `TitleAndActions`, which every `ScreenHeader`, `BackHeader` and the Queue's title already went
  through ("so no header squeezes its title"), now applies `scrollsAway()` itself. So every existing header, and every future
  one, slides away without its screen asking. `SourceHeader` (channel and podcast pages, which draws its own column) applies
  it to that column. The Queue's chip row and offline banner take `Modifier.scrollsAway()` at the call site.
- **`HeaderScrollHost`** provides the state and the connection once per place a header can be: each of the five tab
  destinations (`Destination` in `AppShell`, the one place a tab maps to its screen), and the Ask, Search and music-page
  overlays (`TabOverlays`). A screen with no host (a preview, a test, a dialog) gets a pass-through header.
- **Comes back** when the list is at the top (any scroll up reaches it), **when the tab changes** (each tab's host is new
  when its destination is composed, so it starts fully shown), **when a page opens or closes over a list** (`ListThenDetail`
  and `KeepsEachPlace` call `RevealHeaderWhen`, so a page never opens with its header hidden by the list under it), and when
  the header is touched.

### Headers that were in the list are out of it

Videos (`VideosHeader`), Podcasts, the Library hub, Downloads, an artist page, an artist's other pages, an album, a
playlist and the "which artist" picker had their header as `item { … }`. They are now above their `LazyColumn` in a
`Column`, with `Modifier.weight(1f)` on the list, so the same slide applies. The first list item is no longer the header:
saved scroll indexes written by an older build are one off for a launch.

### Left as they were: a header that is part of a scrolling page

A page that is one scrolling column of settings or a dashboard has no list under a header, and its back row already scrolls
away with the page. Those pass `scrollsAway = false` and are unchanged: Settings, Exsurge, Bedtime, Phone use, Work apps, Daily
alarms, Trip, Kid TV and the two Stats pages.

### The blank gap

The reserved 104 dp is gone. `CollapsingTitle` is deleted. `ActionsHeader` is what it was minus the collapse: the same title and the same wrapping actions
(`TitleAndActions`), with `ScreenHeader`'s padding (14 dp above) and no reserved height. The Queue's title now sits where the
other tabs' titles sit. The collapse was also the source of ADR 61's known feedback loop (its height set the list's viewport and
the list's offset set its height); the new fraction depends on scroll deltas only, never on the list's state or size.

### The offline banner scrolls away with the header

"✋ 318 ready offline · 5793 to download by hand" and its "✈️ 318 play with no signal" chip are a summary and one filter, not
something to keep on screen: left fixed it costs about 60 dp for ever, which is the cost Dewi complained about. It slides with
the header, and comes back with it on any scroll up. It is one of the header's pieces, so it cannot be left behind or drawn twice.

### The Play on row, the strip, the bars

- **The Play on row stays** on Videos, Podcasts, Music and Library. It lives in the shell's top bar beside the live-operation
  banners, which must never hide, and moving it in or out re-runs the Scaffold's content lambda per frame (the same cost ADR 61
  removed). It is one slim line (about 34 dp). Recorded as an exception to ADR 108: the Queue's chip row hides with its header,
  the same picker on the other tabs does not.
- **The progress strip, the out-of-date banner, the polite-client banner and the home-problems banner stay**: they are above the
  tab content in the same top bar, and a live operation is never hidden.
- **The dock (mini player, a device's bar, tabs) never hides.**
- **Search keeps its search field**: the title row slides away and the field stays, because it is the one input of that page.

### Accessibility

Headers stay fixed (`HeaderScroll.fixed`) while TalkBack's touch exploration is on, or while the system animator scale is 0
("remove animations"), and the host logs which it is, once per change. A header that was hidden when either turns on is shown at
once, so nothing is hidden with no way back.

### Logging

`[header]` lines: `<host> headers slide away on scrolling down` / `stay fixed (TalkBack or animations off)` once per host;
`header pieces=N travel=Mpx` whenever a piece comes, goes or changes height; one line when the header reaches fully hidden and
one when it is fully back, never one per frame; `header revealed: <why> (was N% hidden)` for each reveal (a touch, a page, a
place, fixing starting).

## Consequences

- Dewi's real queue (about 6,000 rows): the list is a lazy list, the header offset is read in the layout pass, and a slide
  relayouts the visible rows (about ten) for the 100–200 dp of a scroll it lasts, then nothing. `PlaybackTicksRedrawNoScreenTest`
  and the recomposition counters are unchanged by it.
- A list that fits on the screen with its header shown can have the header slide away on a drag and leave a blank area under the
  list; a drag the other way brings it back. A header is not revealed just because the list is at the top (that fed back into the
  list's size); it is revealed by any scroll up, a touch, or a page change.
- **Open question for Dewi (the chip row's other blank):** options are (A) a chip's second line says only the state
  ("▶️ Playing", "⏸️ Paused", "⚪ Idle"), the title living in the Play on menu, the dock bar and the remote page; (B) keep the
  title and the blank, now at least hidden on scrolling down; (C) the title only on the selected chip. A is recommended; it
  changes ADR 152's wording and the tests that assert the chip's title.
- A page whose header scrolls with its content (the exceptions above) is a recorded exception to ADR 108's "the same kind of
  thing behaves the same", with the reason above.
- **ADR 66's warning stands:** `MultiSelectTest` "in the queue several rows move to the top together" must be run at
  1080x1920@480 whenever the Queue's header height changes. It changed here (the 54 dp gap is gone), so it was run at that
  geometry on 2026-11-28: OK, 12 tests, 23 s, no hang. The same day on `totum-api35` at 1080x2400: 52 related instrumented tests
  OK (`TheQueueTabSpendsItsHeightOnTheListTest`, `QueueTitleSettlesAtAnyHeightTest`, `TargetBarAcrossTabsTest`, `TargetChipsTest`,
  `AppShellTest`, `ScreensKeepEveryControlTest`, `ListThenDetailTest`, `PlaybackTicksRedrawNoScreenTest`,
  `VideosHiddenUntilAskedTest`, `BatchAScreensTest` and the new one); `:app:testDebugUnitTest` (all of it), `:app:lintDebug`,
  `detekt` and `preflight` green.
- At 320x640@160 the Queue's header and the mini player together fill almost the whole screen, so no row is visible at the top
  and a swipe has to start in the thin strip of list that shows; once it scrolls the header goes and rows 2 to 4 appear (seen).
- Tests: `HeaderScrollTest` (JVM, 10 cases: scrolling down consumes the header's height before the list moves, any scroll up
  gives it back first, a piece leaving, fixed headers never move; seen red with the consumption switched off, "expected:<-100.0>
  but was:<0.0>" and "expected:<60> but was:<150>"); `HeaderSlidesAwayOnScrollTest` (instrumented: a plain header block, and the
  Queue tab through `AppShell`: the list gains the header's height on scrolling down, the device chip is no longer displayed, and
  both are back in full on scrolling up). The test emulator runs with animations off, so the tests provide `LocalHeadersFixed =
  false`; the real policy is covered by `headersFixed` in the JVM test.
