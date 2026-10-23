---
title: Captions are on in English, off is for one item, and a file's own tracks are offered
kind: adr
status: accepted
updated: 2026-10-23
---

# 78. Captions are on in English, off is for one item, and a file's own tracks are offered

- Status: Accepted
- Date: 2026-10-23

## Context

Report 0.1.686 (a Peep Show torrent): *"cant see sub titles on current item???"*. The MKV carried an English
subtitle track (`text track selected: none of 1 offered`), but the CC menu was built only from the caption files the
resolver fetches for YouTube, so a file's own tracks had no button and stayed switched off. The same morning Dewi
asked for subtitles *"defaulted on English … across everything I play … unless I purposely switch it off, but then I
want it to come back on the next item"*. Until now captions started off and a choice lasted for the session
(settings-that-stay-put, 2026-08-09).

Checking the default on the emulator found a third fault: the preloader's held copy of the next item is built without
its caption files, so when the player took that copy over (every gapless hand-over) the captions were silently gone.

## Decision

- `CaptionPreference` (core/playback): English by default; **off applies to the item it was switched off on** and the
  next item has captions again; a language chosen in the menu carries on to every next item. Re-applied on every play
  and on every media-item transition, so gapless hand-overs get it too. Session-level, not stored.
- The menu lists `PlaybackState.subtitleChoices`: the fetched tracks, then the file's own text tracks from the player
  (`subtitleChoices`, deduplicated by base language, "English (in the file)"; one with no language is "Subtitles (in
  the file)" and is chosen through Media3's undetermined-language selection). The About card lists the same.
- While casting the phone shows artwork, not the video overlay, so the CC button also sits on the "📺 Casting to …"
  card (report 0.1.686, 11:42: *"how to enable sub titles?"* while casting Peppa Pig). The TV follows the phone's
  choice (ADR 68).
- In Listen mode the text track type is disabled, so no caption file is fetched for an item nobody is watching.
- The preloader hands over its held copy only to an item with no caption files; one with captions loads afresh
  (logged), keeping its captions. Gapless still crosses over, because the armed item is the player's own second entry.

## Consequences

- Every YouTube video now fetches one caption file when it is watched (through the polite gate, like any caption).
- A held preload is wasted for a captioned next item; carrying the caption files into the hold would recover it
  (`docs/todos/preload-keeps-captions.md`).
- Tests: `CaptionPreferenceTest`, `SubtitleChoicesTest`, `SubtitlesInsideTheFileAreOfferedTest` (a generated MKV with
  an English SRT, on a real player). Verified on totum-api35: default `en of 6 offered`, first caption at 1.4 s; off
  for one item only; captions kept across a gapless hand-over.
