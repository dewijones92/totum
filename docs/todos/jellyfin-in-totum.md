---
title: Jellyfin's films and series inside Totum
kind: todo
status: built 2026-11-07, Batch A (tests written 2026-11-07; device checks owed)
batch: A (2026-11)
priority: medium
updated: 2026-11-07
---

# Jellyfin's films and series inside Totum

Dewi 2026-11-05 (agreed idea): the films and series the Pi files into Jellyfin (ADR 111) can be browsed and played in Totum with the same queue, casting and resume. Needs how the phone reaches Jellyfin (behind the private area) and its API key handling; one more source behind the existing seams, not a second player.

## Built 2026-11-07 (code first; tests written 2026-11-07)

[ADR 154](../adr/0154-jellyfin-is-browsed-and-streamed-through-the-pi.md). The Pi browses Jellyfin and proxies the file
with the key it already holds (`/jellyfin/browse`, `/jellyfin/stream/<id>.<ext>`); the phone has a Library tile and page
(films, series, seasons, episodes), an item is a `PlayableItem` (`jellyfin:<id>`) so the queue, groups, resume and the Pi
targets work unchanged. The phone reaches Jellyfin only through the private-area host, as decided.

Not built: posters, Jellyfin's watched state, transcoding for a video the phone cannot decode, search.

Tests: `JellyfinClientTest` (the top level with no query and the device token, a folder and a page by parent and start with the
parent encoded, the Pi's own words for "not set up" and "did not answer", an answer that is not a listing, no token means
nothing sent, the stream address keyed by id and container with no key in it), `HomePiCallTest` (the emoji rule: it found
that an "ℹ️" answer from the Pi was given a second emoji; fixed), `JellyfinRoutesTest` (the Pi's route), `TargetStreamsTest`
(the id mapping) and `ServeRoutesErrorsTest` (a Pi with no Jellyfin answers 503 on both routes). Instrumented, compiled and not
run: `JellyfinScreenTest` (the top level, a failure and try again, a folder and back, back on the top level leaves, an empty
folder, show more, a film plays through the queue as a group).

Still owed: a run against the real Jellyfin on the Pi (a film, an episode, a seek in the middle of a long file over mobile
data), and whether the app's image loader sends the device token (for posters).
