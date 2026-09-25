---
title: The player screen — tinted by what is playing
kind: feature
status: shipped
area: player
updated: 2026-09-25
---

# The player screen

Dewi, 2026-08-07: *"redesign the player screen to be more sexy?????? ... but i dont wanna loose any
functionality"*, and when asked which direction: artwork-derived colour.

## Not losing anything, mechanically

`PlayerKeepsEveryControlTest` was written **first**, against the screen as it was, and kept passing
through every change. That order is the whole value — a checklist written afterwards only records
what survived. The screen has around twenty controls across eighteen files, far more than anyone can
hold in their head while moving things.

It asserts a control is **reachable**, not where it is: by text or content description, scrolled to
if need be. The design is free to move anything; it is not free to lose it.

It earned its place immediately. Speed briefly went behind a "More" sheet and the test failed at
once — which is what led to dropping the sheet altogether.

## What changed

**The surface takes its colour from the artwork.** A soft gradient of the item's own dominant colour
behind the stage, fading to the ordinary surface a third of the way down so body text below is read
on the colour it was designed for. Animated over 600ms, because an abrupt change on every track
advance is jarring and the fade is most of why it reads as considered rather than gimmicky.

**A left-aligned header.** Channel above title, flush left, with the badge, overflow menu and Cast as
a trailing cluster on the same row. Three centred lines became one block: centred text has no common
edge to run down, and a long title centred over a short channel name reads as two unrelated things.

**One control strip instead of five stacked rows.** Sleep timer, skip-silence, speed, volume boost
and listen/watch each used to be a full-width row with its own spacing — five bands between the play
button and the description, which is what made it read as a settings page. They are now one wrapping
row in a single container. Speed and boost became compact pickers (icon + current value + menu),
matching the shape the sleep timer already had.

Everything stayed on the surface. An earlier version put speed and boost behind a sheet; the guard
test flagged it, and on reflection a control whose *value* matters at a glance — Dewi listens at 1.5×
— should not need a tap to read.

## Tiles (2026-09-25)

The control strip became a two-column grid of tiles: sleep timer, skip silences, speed (audio only; a
video has it on the overlay), volume boost, auto-play next, listen/watch and fast start. A tile shows
its value under its name and fills tangerine while it is on, so "what is set" reads at a glance.

- Toggles are `toggleable` with the Switch role, so a screen reader hears on and off rather than a bare
  button. Pickers open the same menus they did.
- Fast start's label had run off the right-hand edge with its switch cut in half; it is now a title and
  a detail line (`sabr_playback`, `sabr_playback_detail`).
- Like and dislike are tonal buttons, and up next is a list of cards with artwork.

## Picking the colour

`ArtworkColour` is plain arithmetic on packed ARGB ints, deliberately, so the part worth proving is
provable on the JVM rather than on a device.

**Averaging the pixels does not work** — the mean of any photograph is a muddy grey-brown, so every
item would come out the same dull colour. What people read as "the colour of this image" is its most
*vivid* region, even when small. So: bucket coarsely, score each bucket on coverage **and** vividness
(saturation squared), take the winner, and return that bucket's own average rather than its centre —
the centre would quantise every result to one of a few dozen colours and two similar thumbnails would
come out identical.

Near-black and near-white are excluded outright. Almost every video thumbnail has letterboxing and a
white logo or sky, and either would win on coverage every time while saying nothing about the image.

Null when there is genuinely nothing to say, and the caller falls back to the brand — better than a
confident grey.

## The brand caveat, stated

`CLAUDE.md` records a deliberate decision: dynamic colour **off** so the tangerine/cyan brand is
actually seen. That was about Material You substituting the *wallpaper's* palette across the whole
app, which is a different mechanism from one screen tinting itself with the thing it is playing —
but the brand is quieter on this screen than elsewhere. `PlayerBackdrop.SOURCE` is a single switch:
set it to `BackdropSource.Brand` for a fixed brand gradient, nothing else to change.

## Two accessibility gaps found while writing the inventory

Both icons had `contentDescription = null` next to a row of bare values:

- **Volume boost** — "Off / Low / Med / High" with nothing saying what they set.
- **Playback speed** — "1× / 1.5× / 2×", likewise.

A screen reader announced four levels of nothing. Found because the inventory could not name them
either, which is a decent argument for writing one.

## Coverage

| Level | What it holds |
|---|---|
| JVM unit | `ArtworkColourTest` (10) — a small vivid area beats a large dull one; letterboxing and white logos ignored; similar images do not collapse to one colour; transparent pixels excluded; null when nothing is usable; always opaque |
| Instrumented | `PlayerKeepsEveryControlTest` (2) — every control on the video player and on the audio player, still reachable |
