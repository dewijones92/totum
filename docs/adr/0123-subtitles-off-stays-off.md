---
title: Subtitles off stays off until turned on
kind: adr
status: accepted
updated: 2026-11-06
---

# 123. Subtitles off stays off until turned on

- Status: Accepted
- Date: 2026-11-06
- Supersedes: [ADR 78](0078-captions-are-on-in-english-and-off-is-for-one-item.md) on "off is for one item"

## Context

ADR 78 made captions on in English for everything, with *off* applying to the item it was switched off on, so the
next item had them again (Dewi, 2026-10-23: *"… unless I purposely switch it off, but then I want it to come back on
the next item"*). Report 20261101T225545 and Dewi on 2026-11-05 say the opposite is wanted: a person who switched
subtitles off does not want them back on the next item, nor after the app restarts. Every other setting that stays
put ([settings-that-stay-put](../features/settings-that-stay-put.md)) holds until changed, and captions were the one
that did not.

## Decision

- Captions are a persisted choice: on or off, and the language to show when on. `CaptionChoice` (core/playback) is
  stored through the `CaptionStore` port; the app's adapter writes it to `AppPreferences` (`Settings.captions`, keys
  `captions_on` and `caption_language`), so it survives items and app restarts, rides in the Drive backup and appears
  in diagnostics as `settings.captions`.
- Off stays off for every item until the person turns captions on in the CC menu. Turning them on again picks a
  language, and choosing a language is what turns them on. Switching off keeps the last language so it is remembered.
- The default is unchanged for a person who has never chosen: on, in English.
- `CaptionPreference` keeps no per-item state; it reads and writes the store on every call, so a restore from a
  backup takes effect on the next item.
- The PC screen follows the same preference unchanged: it reads the session's track-selection parameters
  ([ADR 81](0081-the-pc-screen-is-a-page-the-phone-serves.md)), which the controller derives from the choice on every
  item, so a screen that was off stays off.
- Everything else in ADR 78 stands (a file's own tracks are offered, no captions fetched in Listen, the preloader's
  held copy carries its caption files, a torrent's sidecar subtitles).

## Consequences

- A person who switched captions off under ADR 78 had it forgotten at the next item, so nobody has a stored *off*;
  the first off after this ships is the first one to stick.
- A video with no captions in the chosen language shows none, and the menu still offers what exists.
- `CaptionPreferenceTest` inverts its "off lasts one item" case and gains the store-backed cases (off survives a
  restart; turning them on brings the last language back).

## Tests still to write

Settings round trip of `captions` through `SharedPrefsAppPreferences`, the backup export and restore of the two
keys, and a device check that off survives a restart and a gapless hand-over.
