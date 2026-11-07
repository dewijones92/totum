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

Not built: Jellyfin's watched state, transcoding for a video the phone cannot decode, search. (Posters were not built here; they landed the same day, below.)

Tests: `JellyfinClientTest` (the top level with no query and the device token, a folder and a page by parent and start with the
parent encoded, the Pi's own words for "not set up" and "did not answer", an answer that is not a listing, no token means
nothing sent, the stream address keyed by id and container with no key in it), `HomePiCallTest` (the emoji rule: it found
that an "ℹ️" answer from the Pi was given a second emoji; fixed), `JellyfinRoutesTest` (the Pi's route), `TargetStreamsTest`
(the id mapping) and `ServeRoutesErrorsTest` (a Pi with no Jellyfin answers 503 on both routes). Instrumented, compiled and not
run: `JellyfinScreenTest` (the top level, a failure and try again, a folder and back, back on the top level leaves, an empty
folder, show more, a film plays through the queue as a group).

Still owed: a run against the real Jellyfin on the Pi (a film, an episode, a seek in the middle of a long file over mobile
data).

## Consistency pass, 2026-11-07 (found on the emulator)

[ADR 154](../adr/0154-jellyfin-is-browsed-and-streamed-through-the-pi.md) amendment and [ADR 157](../adr/0157-a-player-error-with-no-recovery-tells-the-user-and-offers-listen.md):
posters (`/jellyfin/image/<id>` on the Pi, the shared image loader now sends the device token), no follow mark, shown as a
video, the year and overview, the shared back header with the place kept, and a film the phone cannot decode says why and
offers 🎧 Listen. **Needs a deploy of `totum serve` on the Pi for the posters.** The emulator cannot decode 10-bit HEVC,
so "21 Jump Street" there is the test for the failure message; Dewi's Pixel 7 can.

Tests: `JellyfinItemsAreShownLikeVideosTest` (shown as a video, no mark, the year, the description),
`JellyfinItemTest` / `JellyfinClientTest` (year, poster address), `JellyfinRoutesTest` (the image route: key, type, 404, a
bad id refused, a Pi with no Jellyfin), `HomeServerImagesTest` (the token goes only to the home host),
`JellyfinScreenTest` (instrumented: the shared back, no second read of the top level, the queued item's poster and year; run on `totum-api35` on 2026-11-07 with `AnUnplayableVideoSaysWhyWordsTest`, 10 passing). Seen on the emulator against the Pi's real Jellyfin: the list with years, the video icon and no follow chip, the shared back, a HEVC 10-bit film failing with the message and 🎧 Listen playing its sound, About this with Published, Type Video and the description. Not seen: a poster (route not deployed).
