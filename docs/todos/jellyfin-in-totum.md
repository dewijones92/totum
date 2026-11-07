---
title: Jellyfin's films and series inside Totum
kind: todo
status: built 2026-11-07, Batch A (tests owed)
batch: A (2026-11)
priority: medium
updated: 2026-11-07
---

# Jellyfin's films and series inside Totum

Dewi 2026-11-05 (agreed idea): the films and series the Pi files into Jellyfin (ADR 111) can be browsed and played in Totum with the same queue, casting and resume. Needs how the phone reaches Jellyfin (behind the private area) and its API key handling; one more source behind the existing seams, not a second player.

## Built 2026-11-07 (code first; tests owed)

[ADR 154](../adr/0154-jellyfin-is-browsed-and-streamed-through-the-pi.md). The Pi browses Jellyfin and proxies the file
with the key it already holds (`/jellyfin/browse`, `/jellyfin/stream/<id>.<ext>`); the phone has a Library tile and page
(films, series, seasons, episodes), an item is a `PlayableItem` (`jellyfin:<id>`) so the queue, groups, resume and the Pi
targets work unchanged. The phone reaches Jellyfin only through the private-area host, as decided.

Not built: posters, Jellyfin's watched state, transcoding for a video the phone cannot decode, search.

Tests owed: `JellyfinClient` against a mock server (token header, browse, error text; the Pi's route is covered by
`JellyfinRoutesTest` and the id mapping by `TargetStreamsTest`, the client itself not at all), a Compose test of `JellyfinScreen` (loading, error and retry, folder trail, back, an episode plays its
season as a group), a run against the real Jellyfin on the Pi (a film, an episode, a seek in the middle of a long file
over mobile data), and whether the app's image loader sends the device token (for posters).
