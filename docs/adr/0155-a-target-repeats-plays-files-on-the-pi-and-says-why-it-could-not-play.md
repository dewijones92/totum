---
title: A target repeats, plays sound files on the Pi, and says why it could not play
kind: adr
status: accepted
updated: 2026-11-07
---

# 155. A target repeats, plays sound files on the Pi, and says why it could not play

- Status: Accepted
- Date: 2026-11-07
- Builds on: [ADR 27](0027-radio-repeat-and-shuffle-are-queue-features.md) (the phone's repeat), [ADR 152](0152-a-target-is-anything-the-pi-plays-to-and-each-has-its-own-queue.md) (targets and decks)

## Context

[Home Assistant's brown noise, jazz and shows play through Totum](../todos/home-assistant-sounds-through-totum.md): HA's
`hermes-audio-ctl` is to stop running its own mpv and ask Totum's targets instead. That needs four things a deck did not
have: brown noise loops, brown noise is a file the Pi holds, a failed or unplayable item has to be visible to a caller
that is not a phone, and the caller (HA) has to be reachable and recognisable.

## Decision

- **Repeat is the phone's own type.** `RepeatMode` moved from `:app` to `:core:domain` (same six values, same helpers) so
  the Pi and the phone share one definition. `TargetState.repeat` (default OFF, so a deck file written before this reads as
  off) is shown in `GET /targets`; it is set by `TargetQueueRequest.repeat` (null leaves it as it was) or the
  `REPEAT` action of `TargetControl`, and saved with the deck.
- **The deck decides what happens at an end**, so speakers and TVs behave the same: `RepeatMode.nextAfterEnd`
  (`:core:data`, pure) gives the next index. ONE plays the same item again; QUEUE goes from the last item back to the
  top; OFF moves on. Two guards stop a loop: an item that **failed** is never repeated, and neither is one that ended
  **within 2 s of starting** (a zero-length or instantly-finishing file would otherwise restart as fast as the process
  can spawn). The next button still skips, as on the phone. `RepeatMode.onATarget` admits OFF, ONE and QUEUE only: the
  reshuffling and group modes are refused with a warning and the previous mode is kept (they need the phone's group
  logic and are not built).
- **A sound file on the Pi is one rule** (`SoundFiles`, `cli/serve/targets`). An item whose handle carries a local path
  that starts `file:` (a Podcast or LocalVideo handle, e.g. `file:///sounds/brown-noise.opus`) is a file on the Pi. Only
  `file:///sounds/<name>` is accepted, where `<name>` is one path segment of letters, digits, `.`, `_`, `-` and spaces,
  not starting with a dot; the file is resolved to its canonical path and must sit directly in the configured sounds
  directory (`TOTUM_SOUNDS`, default `/sounds`), so `../`, a nested path, an encoded dot, another scheme or host, a
  symlink that leaves the directory, a missing file and a directory are all refused, each with a reason. A refused file
  item gets no stream and is never handed to the phone's route. Ordinary paths (a phone's own download path) are not file
  items and go the way they always did. The container mounts `/var/lib/hermes-audio` at `/sounds` read-only.
  Players already take a Pi path (mpv directly, a Chromecast through `SoundStreams.offerFile`, since replaced by the core's manifest, [ADR 156](0156-one-casting-brain-the-pi-casts-with-the-phones-own-code.md)).
- **A target that cannot play says why.** `TargetSounds.whyNone(item)` gives the reason an item has no stream (the file
  rule's refusal, "Jellyfin is not set up on this Pi", else "no stream was found for it"); `TargetPlayer.failure` gives a
  player's own reason (mpv: "the player exited with code N", with no key in it; a TV: the generic "the player stopped
  with an error" for now). The deck sets `problem` to "❌ Couldn't play <title>: <reason>" when it passes over an item or
  a player fails, and clears it when the next item plays, on STOP, and when a new request arrives. The phone's chip
  already renders `problem`.
- **8770 is published on the Pi's loopback only** (`127.0.0.1:8770:8770` in `server/totum-fetch/docker-compose.yml`), the
  same trust as `hermes-audio-ctl` on 8099, so Home Assistant and the host can call `/targets`.
- **A caller is a requester.** HA sends `{"id":"home-assistant","label":"🏠 Home Assistant"}` as `from`; the queue shows
  "➕ Added by 🏠 Home Assistant", and as it is never the YouTube owner nothing it queues reports to YouTube
  (already so; now a test).

## Consequences

- Home Assistant's `hermes-audio-ctl` can become a thin adapter (the handoff is `tools/handoff/hermes-audio/`); until
  it is deployed the Pi still has two owners of the speakers.
- **Live YouTube on a Pi target is not built here.** It comes with the move of the phone's casting brain into
  `:core:cast`, which the Pi will use unchanged; the jazz station in HA stays a live item that will fail with a
  `problem` until then.
- A TV's failure reason is generic until the Chromecast player reports one.
- Nothing here has run against a real speaker or TV.
