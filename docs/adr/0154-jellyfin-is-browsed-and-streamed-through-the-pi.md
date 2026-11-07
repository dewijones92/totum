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
- Posters: see the amendment below.
- Where the key goes, exactly: Jellyfin's own API calls carry it in an `Authorization` header from the Pi. A stream
  address for a player (`Jellyfin.streamUrl`) carries it as `api_key=` in the query; that address goes to mpv and ffmpeg
  on the Pi (visible in their argument lists inside the container) and, for a TV target, to the TV over the LAN, so it
  works only if `TOTUM_JELLYFIN_URL` is an address the TV can reach (a LAN address, not a container name) and the key
  then travels unencrypted on the LAN. It is never sent to a phone. Anything logged from mpv's output or ffmpeg's
  stderr has `api_key=` values replaced with `***` (`withoutApiKeys`); ffmpeg's stderr is now read and logged that way
  instead of being inherited. Accepted for a household and written down.
- Watched state is not read from or written to Jellyfin.
- Not run against a real Jellyfin: the route tests use a canned Jellyfin response.

## Amended 2026-11-07: Jellyfin items are consistent with every other list

Found on the emulator: the Jellyfin page was the one list that looked different (no pictures, a "❔ unknown" follow chip
on every film, the podcast microphone on a film, no year, its own "⬅️ Back" text button). Each is now one rule, not a
Jellyfin special case in the screen:

- **Posters.** `GET /jellyfin/image/<id>` on the Pi is Jellyfin's Primary image (`/Items/<id>/Images/Primary`, 480 px
  high), read with the Pi's key, behind the device token like the stream, the id checked by `JELLYFIN_ID` before
  Jellyfin is asked, `Content-Type` and `Cache-Control` passed back, Jellyfin's own 404 for an item with no picture
  passed back as a 404 (the image loader then shows the neutral placeholder). `JellyfinApi.imageUrl` is the
  address and `JellyfinApi.playable` builds the `PlayableItem` with it as `thumbnailUrl`, so the row, the queue, Carry
  on, history, the mini player and the notification show it through the paths every item uses (the queue and history
  tables already store the thumbnail). The image loader had never sent the device token: the shared Coil loader now
  rides an OkHttp client with `HomeServerAuthInterceptor` (the token goes only to the home host, ADR 90), the player's
  backdrop uses that shared loader instead of a fresh default one, and the media session's notification artwork loads
  through `homeServerAware(DefaultDataSource.Factory)`.
- **No follow mark.** `FollowedSources.nothingToFollow(item)` in `:core:domain` is the one rule for "this item came from no
  channel or show": book chapters, torrent files and now Jellyfin films and episodes (id prefix `jellyfin:`); the row's
  mark reads it. This amends [ADR 22](0022-every-row-says-whether-you-follow-its-source.md).
- **Shown as a video.** A Jellyfin item still plays through the podcast handle (the Pi's stream address, no extraction,
  downloads as a file), but `shownAs` says `Shown.JELLYFIN`: the video's icon and channel-style maker line (an episode's
  series; a film has none) with its own honest label "Jellyfin" (the video's label says "YouTube"), so the row icon, pin
  kind, player badge and mini-player glyph are a video's, and "About this" says "📺 Video" rather than "Podcast
  episode". Listen and Watch were already offered by the extension of the address
  ([ADR 70](0070-listen-and-watch-follow-the-picture-not-the-pillar.md)). A torrent file's "About this" still says
  "Podcast episode"; not changed here.
- **Year and overview.** The year is the item's `publishedText`, so "📅 1995" appears in the row's facts and in "About
  this" ([ADR 65](0065-the-player-says-everything-about-the-item.md)); the overview is its `description`, which the
  Details tab already reads. Rows rebuilt from the database after a restart keep the year but not the description (the
  known gap recorded in [ADR 108](0108-consistent-everywhere-and-its-recorded-exceptions.md)).
- **Back.** The page uses the shared `BackHeader` and `ListThenDetail` ([ADR 88](0088-a-page-opened-over-a-list-keeps-its-place.md)):
  a series opened over the top level keeps the top level's place, and going back does not read the listing again.
- A decode failure of the original file now says why and offers Listen: [ADR 156](0156-a-player-error-with-no-recovery-tells-the-user-and-offers-listen.md).
- The Pi route needs a deploy of `totum serve`; the phone without it shows the placeholder (the route answers 404).
