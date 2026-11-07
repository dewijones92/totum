---
title: A speaker's queue plays on the Pi, on the shared queue model
kind: adr
status: accepted
updated: 2026-11-07
---

# 116. A speaker's queue plays on the Pi, on the shared queue model

- Status: Accepted (the Pi side is built). Amended by [ADR 152](0152-a-target-is-anything-the-pi-plays-to-and-each-has-its-own-queue.md): a speaker is one kind of target, `/speakers` is `/targets`, and the phone side is the Queue tab's target chips
- Date: 2026-11-01

## Context

Dewi, 2026-11-01: *"shuffle Vivaldi on my one Bluetooth speaker, but … a separate queue … on my LG soundbar queue,
shuffle all Bluey"*, then *"a thin layer … just like a different queue … its own colour"* and *"I don't want you
duplicating things"*. A phone streams to one Bluetooth speaker at a time. The home Pi is already paired with the LG
S65Q soundbar and the dprofy Soundmic, and `totum serve` (ADR 110) already resolves YouTube with the app's own code.

## Decision

- **The Pi holds each speaker's queue and plays it.** `totum serve` gains `GET /speakers`, `POST /speakers/queue?id=`
  and `POST /speakers/control?id=`, behind the same token gate as `/jobs`.
- **No second queue model.** A speaker's queue is a `QueueSnapshot`, changed only by the queue operations that moved to
  `:core:data` for this (`appendingAll`, `insertingAllNext`, `removingAt`). The wire format (`core/data/…/speakers/`)
  carries each entry with its group, so a "🔀 Bluey" group looks the same on the phone and the speaker.
- **Any paired speaker, picked each time.** A speaker is any paired Bluetooth device with an Audio Sink
  (`bluetoothctl info`), named by its alias; discovery is cached for a minute.
- **Linked only while playing.** The Pi links the speaker when its queue starts (`bluetoothctl connect`, then waits
  for the PulseAudio sink) and unlinks it when the queue stops or ends, unless something else is playing on it or it
  was already linked before Totum asked.
- **mpv plays the sound**, one process per item on the speaker's sink, controlled over its JSON socket (pause, seek,
  volume, position).
- **The sound of anything**: the copy at home if there is one, else YouTube's audio-only link from the signed-in
  resolver (waiting out a fresh link by the phone's own rule, `YoungStreamUrl`, now in `:lib:common`), else the feed
  or torrent link with the home rewrites. The next item is looked up when one starts, so hand-overs do not wait for
  a challenge solve (9 s cold on the Pi).
- **A queue survives a restart, paused**: it is saved per speaker and never resumes by itself.
- The container gets `mpv bluez pulseaudio-utils`, the user's PulseAudio socket and cookie, and the system D-Bus.

## Consequences

- Verified on the Pi on 2026-11-01 with a throwaway image of this code: both speakers found and the Pixel skipped; a
  podcast and a YouTube video played on the soundbar, pause held its place, stop unlinked it; the Soundmic (off)
  answered "⚠️ Couldn't reach dprofy Soundmic". First sound for a YouTube video took 13 s, 9 s of it solving.
- The phone side (a speaker switch in the Queue tab with its own colour, "where" on play/queue/shuffle all, the mini
  player and notifications) is the next part of this ADR.
- Shared code made for this: `File.writeTextReplacing` (three stores used their own copies), and one
  `HttpExchange.reply`/`query` and `Routes` for the CLI's servers (three private copies before).
