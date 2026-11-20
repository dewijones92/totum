---
title: The selected device owns the dock's now-playing bar on every tab, and its remote page is one scrolling list with a compact card on top
kind: adr
status: accepted
updated: 2026-11-20
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
- **Lock-screen and notification controls are not built.** The phone's media notification is still the phone's own session; a per-device
  notification is [todo](../todos/target-bar-everywhere.md).

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
