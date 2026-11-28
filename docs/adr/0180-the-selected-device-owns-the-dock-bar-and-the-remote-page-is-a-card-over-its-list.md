---
title: The selected device owns the dock's now-playing bar on every tab, and its remote page is one scrolling list with a compact card on top
kind: adr
status: accepted
updated: 2026-11-28
---

# 180. The selected device owns the dock's now-playing bar on every tab, and its remote page is one scrolling list with a compact card on top

- Status: Accepted (built and unit-tested; screenshots at 1080x2400 and 320x640@160; not driven against a real TV or soundbar from here)
- Date: 2026-11-20
- Amends: [ADR 152](0152-a-target-is-anything-the-pi-plays-to-and-each-has-its-own-queue.md) (the remote page, the chips and the
  colour editing), [ADR 165](0165-a-speaker-is-kept-awake-and-has-one-volume-its-own.md) (the BlueZ transport path and the
  keep-awake back-off), [ADR 169](0169-play-on-is-a-choice-that-remembers-the-last-device.md) ("the mini player shows the phone's item,
  not a remote device's" is no longer true)

## Context

Dewi, 2026-11-20: *"do at least the basic play controls from my app and whatever receiver … play pause, rewind etc"*, and on telling
devices apart: *"yes, with different colors etc etc, so I can really distinguish"*.

A device the Pi plays to could only be controlled from the Queue tab. His screenshot of 2026-11-18 showed the cost: the phone's own
mini player offered a paused "play" while the page above it showed the LG playing with a "pause" (two contradictory buttons),
the remote page's header took about 55% of the screen above a queue of one visible row, the transport was a mix of Material icons and
an emoji-and-word Stop button, and Repeat, Sleep, Skip silences and Boost were four different widgets.

The same day the Pi's logs showed the soundbar's hardware volume had never worked (every read fell back to "no A2DP transport") and
two chatty loops: discovery lines repeated every minute, and the Soundmic's keep-awake made 458 failed reconnects while it was
switched off.

## Decision

### The bar: the selected device replaces the phone's mini player on every tab

- **One rule, `List<TargetState>.barFor(selected)` in `:core:data`:** the selected device, but only while it has something to show
  (a current entry). An idle selected device, a device the Pi stopped listing, or the phone being the choice all leave the phone's own
  mini player where it was, so nothing the phone is playing is ever hidden behind an empty remote. Every decision is logged once on
  change, with its reason (`dock bar is the remote for LG TV: …` / `… has nothing playing or queued to show`).
- **One bar, not a second player.** `MiniPlayerBar` was split into `NowPlayingBarFrame` (artwork, text, a controls row, a footer, a
  hairline progress line) and `ArtworkWithBadge`; the phone's mini player and the new `TargetNowPlayingBar` are both that frame with
  different slots. The bar sends the same `TargetAction`s the page does (`PLAY`/`PAUSE`, `SEEK`, `NEXT`) through `TargetsRepository`,
  whose answer replaces the polled state at once, so a tap shows at once.
- **What it shows:** the device's art (through the spoiler shield's `face()` like every row), `📺 LG TV · ▶️ Playing` (the same words as
  the chips, from `chipLabel`), the title and author, back 10 and forward 30 (`SeekSteps`, now public in `:core:playback` and used by
  the page as well; the page had its own copy of the two numbers), a filled play/pause in the device's colour, and skip. Seek is disabled
  for a live item (no length). While the device is starting, a spinner replaces play/pause.
- **Unmistakably that device:** a tinted background and a 2 dp border in the device's colour, the colour dot beside its name, its kind
  glyph on the artwork, and a progress line in its colour. The play button picks black or white text by contrast, not by hope
  (`readableOn`).
- **Tapping the bar opens that device's remote page** (the Queue tab, which already shows the selected device's page).
- **The bar polls while it is shown.** The Play on row asks the Pi every 3 s while a device is selected (10 s while the phone is the
  choice), so the bar's state is at most a few seconds old on tabs that have no chips of their own.
- **Lock-screen and notification controls** were not built here; they are [ADR 188](0188-the-selected-device-is-a-second-media-session-for-the-lock-screen-and-notification.md),
  a second media session whose existence follows this ADR's `barFor` rule.

### The remote page

- **One `LazyColumn`.** The header is its first item, so the page scrolls as a whole and the queue is no longer squeezed under a
  non-weighted column (the same defect class as the Queue header before it). A compact card holds a heading, the title, a seek bar with
  elapsed and total at its two ends, the transport and the volume line. Repeat, Sleep, Skip silences, Boost and Colour sit behind one
  "⚙️ Controls" row.
- **Heading:** the screen's own text colour (device colours on a dark background failed contrast) with the device's colour as a dot, and
  the link state in the words: `▶️ Playing on LG TV · 🟢 Linked`. `TargetConnection` (`:core:data`) is one rule from `power`, `keepAwake`,
  `trouble` and `linked`: asleep, waking, reconnecting, problem, linked, ready, in that order of precedence.
- **Transport:** previous, back 10, a large filled play/pause in the device's colour, forward 30, next; Stop is an icon-only button at the
  right of the heading. No more emoji-and-word buttons beside Material icons.
- **Seek bar:** a neutral inactive track, the active part in the device's colour.
- **Volume line:** an icon, the slider, an inline percentage and a one-word owner (`Speaker`, `Totum`, `Not known`), with the reason behind an
  info tap (a dialog), instead of a long sentence above the slider.
- **One control component.** Repeat (`RepeatTile`), Sleep (`SleepTimerControl`), Skip silences (`SkipSilenceTile`) and Boost
  (`ToggleTile`) are the player's own tiles, all `ControlTile`, with one on/off encoding (🟢 On / ⚪ Off) and value pickers for Repeat and
  Sleep. Where the item has a picture, skip silences and boost are not drawn and the existing note says why.
- **Queue heading:** `📋 Up next on LG TV · 54` (the number of items in the list below it). A group's title takes the screen's text colour,
  with the device colour as a dot.

### The chips

- Each device chip has a small **connection dot** (`TargetConnection`: green linked, grey ready, amber waking or reconnecting, dark grey
  asleep, red problem); the Play on row's chip carries it too. The phone's chip has none.
- **A scroll affordance:** a fade and a chevron at whichever edge has more chips.
- **Distinct glyphs per kind:** the Chromecast's was the same 📺 as the TV's, so it is now 📽️ (`TargetKind.CHROMECAST`, the one place),
  pinned by `TargetBarRulesTest`. Change that one string if 📽️ is wrong.
- **Colour moved off the page:** a long-press on a chip (a custom chip, `combinedClickable`, with a screen-reader label) or the Colour tile
  in Controls opens one `TargetColourMenu`, whose swatches are 48 dp.

### Pi side

- **BlueZ 5.55 puts an A2DP transport at `/org/bluez/hciN/dev_MAC/sepN/fdN`.** `BluezTransport` matched only `dev_MAC/fdN`, so the
  soundbar's hardware volume had never worked ("the speaker's own is not available: no A2DP transport"). The pattern is
  `dev_MAC(/sep\d+)?/fd\d+`, with the old layout kept as a test case; when nothing matches, the device's own lines from the bus tree are
  logged (once per change) so the layout can be read from a report. The test fake now has the real layout, and was red against the old
  pattern.
- **Log noise:** `found N speaker(s)`, `paired … is not a speaker` and `configured N cast target(s)` were logged every minute. `LogOnChange`
  logs a line when its content changes and otherwise once every 30 minutes with how often it was seen.
- **Keep-awake backs off on "Host is down".** A reconnect that fails with "Host is down" (the speaker is switched off or out of range)
  doubles its wait up to an hour instead of ten minutes, says so once, and resets (with one line) when the speaker is seen again. To read the
  text, `Shell` gained `runKeepingFailure`: `bluetoothctl connect` exits non-zero on a failure and `run` hid its output.

## Consequences

- With a device selected and playing, every tab's bottom bar is that device's, in its colour; choosing the phone brings the phone's back.
- The page's controls are one tap further away (behind "⚙️ Controls"), and volume and the transport are not. Collapsed by default.
- `TargetChip` is no longer a Material `FilterChip` (it needed a long press), and the chip label no longer carries the colour as a
  `FilterChip` border: the border and fill are drawn by the chip itself; `DeviceColourSurfacesTest` still pins that the border is the
  device's colour.
- The dock reads the polled `TargetState`, so the bar's progress moves when the poll arrives (every 2 s on the Queue tab, 3 s elsewhere),
  not smoothly.
- **At Dewi's scale** (5,857 queue items): a remote queue is the Pi's list for that device, not the phone's; the page is a `LazyColumn`
  keyed by position, so the compact card adds one item and nothing else changes with queue length.
- Not run on a real TV or soundbar from here: whether the corrected transport path now moves the LG soundbar's own volume is the
  coordinator's check on the Pi (`busctl --system tree org.bluez --list | grep sep`).

## Amended 2026-11-27: one set of controls per screen, boost under a picture, and what each device is doing

Dewi, from reports 20261121T062216, 091331, 075241 and 080258: *"surely just one, the same controls as the phone is using but obviously using
different colours"*, and, on 27 Nov, that the device in use should be obvious at a glance.

- **No dock bar on the Queue tab while the remote card is there.** `List<TargetState>.dockBarFor(selected, onQueueTab)` in `:core:data`, next
  to `barFor`, answers `Remote(state)`, `Phone` or `Hidden`: a selected device with something to show is `Remote` on every tab but the Queue
  tab, where the page's own card already holds its transport and the bar was a second copy whose tap went `Queue -> Queue` and did
  nothing; there it is `Hidden`, and the dock draws neither the device's bar nor the phone's mini player (the phone's would have been the
  two-contradictory-buttons case of this ADR's context). An idle or unlisted selection is `Phone` on every tab. `TargetDockBarTest` pins the
  three answers; red first against the old rule, which answered `Remote` on the Queue tab. The decision is logged once per change with its
  reason. On the other tabs a tap on the bar selects the Queue tab, which shows the selected device's page: the same device.
- **The card's controls are the phone's, in the device's colour.** The Controls tiles are the player's own `ControlTile`s; inside the card
  they take the device's colour for their on-state (`LocalTileAccent`, black or white text by contrast). What the phone's player offers and
  the card offers, tile by tile: Repeat yes; Sleep yes; Skip silences yes (sound only: a picture cannot be cut to match);
  Boost yes, **now also under a picture** (below); Captions yes, only while the device reports tracks (ADR 191); Colour is the card's own.
  **Not offered, and why:** playback speed (the Pi's sound chain and the Chromecast's stream have no speed stage, so there is nothing to send it
  to: building one is its own item); Listen/Watch (a target either shows a picture or does not, and the Pi decides that from the device's kind);
  Explore, back and forward through history and the gapless arm (phone queue features that the Pi's deck does not have).
- **Boost under a picture.** The card hid both Skip silences and Boost while a TV showed a picture, on the premise that nothing would
  process the sound, yet the Pi already decodes and re-encodes the audio for the TV (the `audio=processed … boost=OFF` log line), and
  `PlayRequest.sound` carries the boost into that engine. `TargetState.offersBoost` is now always true and `offersSkipSilence` is false
  only while a picture shows (`TargetRulesTest`; red first against the old "hide both" rule). Changing it restarts the item at the same
  place, as for sound-only items. Not seen on a real TV from here.
- **What each device is doing, on the chips and the picker.** `TargetState.doingLine(title)` in `:core:data`: `▶️ Toy Story`, `⏸️ Toy Story`,
  `⏳ Toy Story` while starting, `⚪ Idle` when nothing is queued or the device is asleep with a saved queue, with the title through the
  spoiler shield like every row. It sits under the device's name on its chip and in the Play on row and picker (a second line that wraps, a
  chip is at most 220 dp wide), from the same polled `TargetState` the bar and the page read, in the device's tinted, bordered chip.
- **Amended 2026-11-28 (Dewi's option A, report 20261128T021751 finding 245): a chip says the state, never the title.** A playing device's
  chip carried its title on a second line that wrapped, so it was 82 dp tall against 50 dp for its neighbours, and the Queue's chip row,
  which is as tall as its tallest chip and top-aligned, left a blank under every shorter chip (ADR 198 found it and left it as a product
  call). The chip's second line is now `TargetState.stateLine()`: `▶️ Playing`, `⏸️ Paused`, `⏳ Starting`, `⚪ Idle`, which is
  `doingLine(null)`, so the words and the emoji come from the one function. This applies to every `TargetChip` that is given a `doing`
  line: the Queue's chips and the chip in the shell's Play on row (both fed by `stateLines`). The title is still shown where it can
  be read: the dock bar (`📺 LG TV · ▶️ Playing` over the title), the remote card (title under the heading), and the Play on menu,
  whose items keep `doingLine(title)` (`▶️ Toy Story`, from `doingLines`) so you can see what each device plays before you pick one. A
  chip's other markers are unchanged: 💤 and ⏰ on the name, the connection dot. The 220 dp cap stays for a long device name, which still
  wraps (ADR text rule). The chips in the Queue's row take a 50 dp minimum (the height of a two-line chip), so the single-line chips
  (This phone, ➕ Play on…, Switch off) match the two-line ones instead of the row being as tall as its tallest chip with blank under the
  rest. Tests: `TargetDockBarTest` (`stateLine` never carries a title), instrumented `TargetBarAcrossTabsTest`
  (`aPlayingDevicesChipIsNoTallerThanAnIdleOnesHoweverLongItsTitle`, seen red on 1080x2400@420 against the title-on-the-chip build:
  "every chip in the row is the same height: [126, 215, 126]", that is 48, 82 and 48 dp; green after, within 2 px of rounding; and
  `theTitleStaysInThePlayOnMenuWhileTheRowsChipSaysOnlyTheState`). Both screenshots are in `~/claude-tasks/chip-state-shots/`.
  `HeaderSlidesAwayOnScrollTest.draggingTheHeaderItselfSlidesItAwayWhateverStripOfListShows` asserted `assertIsNotDisplayed()` on the
  phone chip after five drags, which only held because that chip ended 1.5 dp above the row's bottom edge while the header was left
  20 dp tall (the 50 dp lg chip beside it already showed that sliver); with the chips all 50 dp the sliver of the phone chip is 3 px, so
  it now asserts that less than a quarter of the chip is still visible.

