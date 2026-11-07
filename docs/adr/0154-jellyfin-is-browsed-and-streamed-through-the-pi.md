---
title: Jellyfin is browsed and streamed through the Pi, with the key kept there
kind: adr
status: accepted
updated: 2026-11-07
---

# 154. Jellyfin is browsed and streamed through the Pi, with the key kept there

- Status: Accepted
- Date: 2026-11-07
- Builds on: [ADR 111](0111-finished-torrents-at-home-are-filed-into-jellyfin.md) (the Pi already holds a Jellyfin API key
  and files finished torrents into its libraries)

## Context

Dewi, 2026-11-05: the films and series the Pi files into Jellyfin should be browsable and playable in Totum with the
same queue, casting and resume. The Pi's `Jellyfin` class only announced new files; the phone has no business holding
Jellyfin's key or reaching it directly (Jellyfin sits behind the home network, the phone reaches the Pi through the
token-gated `totum.` host).

## Decision

- **Two read routes on the Pi, behind the device token like the rest of `totum serve`:**
  `GET /jellyfin/browse[?parent=<id>]` returns a `JellyfinListing` (films and series at the top; a series' seasons; a
  season's episodes) read from Jellyfin's `/Items` with the Pi's key, and `GET /jellyfin/stream/<id>.<ext>` is Jellyfin's
  own file (`/Videos/<id>/stream?static=true`) proxied with the player's `Range` header passed through and the status,
  `Content-Type`, `Content-Range` and `Accept-Ranges` passed back. The phone never sees the key. Where it does go is
  below: to Jellyfin from the Pi, and in a stream address to a Pi-side player (mpv, ffmpeg) or a TV on the LAN. An id
  that is not a Jellyfin id (`JELLYFIN_ID`, checked by `jellyfinId()` on every path) is refused before Jellyfin is
  asked, so a phone cannot steer the request.
- **One source behind the existing seams, not a second player.** An item is a `PlayableItem` with a `PlayHandle.Podcast`
  and the Pi's stream address as its `mediaUrl`, id `jellyfin:<id>`. The address keeps the file's own extension, so
  `Picture` knows it has a picture and Listen/Watch, the queue, groups, resume, downloads and the mini player treat it as
  they treat a torrent file. Tapping an episode plays the rest of its season as a queue group.
- **A Pi target resolves it itself.** `TargetStreams` (the deck's `TargetSounds`) turns a `jellyfin:` id into Jellyfin's stream address with
  the key, for mpv and ffmpeg on the Pi, instead of the phone's `/fetch/` address (which needs the device token).
- **Listings are read a page at a time** (`Limit`/`StartIndex`, 100 per page; the listing says `total` and `next`, the
  page says "⬇️ Show more"). A Pi with no Jellyfin configured answers 503 "ℹ️ Jellyfin isn't set up on the home
  server" (`JellyfinNotSetUp`) rather than "no such route", and an answer that is not a library listing (a login page)
  is a 502 "not a library listing (is its key right?)", never an empty library. The phone's page load reports through
  the operations strip. The tile still shows whenever a home server is set (it is not hidden when Jellyfin is off; owed).
- **Library tile.** A "🎞️ Jellyfin" tile and page appear when a home server is set; the page walks the listing with a
  back trail.
- The `Jellyfin` class gained `get`, `open` and `streamUrl`; the filing code's POSTs now share its `auth()`.

## Consequences

- The phone plays the original file (`static=true`), so a video the phone cannot decode does not play; transcoding is not
  offered.
- No posters yet: the image route would need the phone's image loader to send the device token header, which was not
  checked.
- Where the key goes, exactly: Jellyfin's own API calls carry it in an `Authorization` header from the Pi. A stream
  address for a player (`Jellyfin.streamUrl`) carries it as `api_key=` in the query; that address goes to mpv and ffmpeg
  on the Pi (visible in their argument lists inside the container) and, for a TV target, to the TV over the LAN, so it
  works only if `TOTUM_JELLYFIN_URL` is an address the TV can reach (a LAN address, not a container name) and the key
  then travels unencrypted on the LAN. It is never sent to a phone. Anything logged from mpv's output or ffmpeg's
  stderr has `api_key=` values replaced with `***` (`withoutApiKeys`); ffmpeg's stderr is now read and logged that way
  instead of being inherited. Accepted for a household and written down.
- Watched state is not read from or written to Jellyfin.
- Not run against a real Jellyfin: the route tests use a canned Jellyfin response.
