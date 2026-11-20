---
title: 📱 On this phone follow-ups
kind: todo
status: open
priority: medium
area: library
updated: 2026-11-20
---

# 📱 On this phone follow-ups

What [ADR 176](../adr/0176-on-this-phone-is-a-source-behind-the-one-player.md) left out, with the design for each.

## 🖥️ Show a photo on a Chromecast or the Pi's targets

Done 2026-11-20 for a browser on the phone's screen page (ADR 176): `sharePicture`, a `show` command in `screen.html`, a viewer
row. What is left is the two kinds of target that are not a browser opened on the phone's address, neither built because neither
could be proved without a TV or a Pi deploy:

1. **The phone drives a Cast session.** `CastStreamServer` gains an image route for a keyed file (the way it serves a local
   video file now: `LocalRanges`), and the viewer loads a Media3 `MediaItem` with `image/jpeg` through `RemoteCastPlayer`
   (`TotumCastConverter` currently assumes every item has a processed manifest and would need an image branch). Works only
   on the phone's Wi-Fi.
2. **The phone uploads a downsized JPEG (about 1920 px, a few hundred KB) to the Pi**, and the Pi's deck gains an image item:
   `pychromecast` `play_media(url, "image/jpeg")` for TVs and an `<img>` mode in the browser page for browsers. Works away
   from home. Needs a `totum serve` deploy and a new `TargetEntry` kind.

Decide first which; option 2 also gives a slideshow on the TV by queueing several images with a dwell time.

## Phone files to the Pi's speakers, TVs and browsers

Today they always play on the phone (ADR 176). To lift it: the phone's `CastStreamServer` serves the file at a keyed
address the Pi can fetch over the LAN, and the item sent to the Pi carries that address; at home only.

## Smaller

- Phone items in search results: a new `SearchHit` case matched in each exhaustive `when`.
- Remove a Google Photos copy from the viewer (`ImportedPhoneMedia.remove` exists).
- The first real Google Photos run, after Dewi creates the OAuth client (steps in [phone-media.md](../features/phone-media.md)).
- Play queues only the pages loaded; an "add the whole folder" action would need `WholeSource`-style walking.
