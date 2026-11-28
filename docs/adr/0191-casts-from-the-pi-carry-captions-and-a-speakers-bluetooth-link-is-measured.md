---
title: Casts from the Pi carry captions under the phone's own caption choice, and a speaker's Bluetooth link is measured and logged
kind: adr
status: accepted
updated: 2026-11-28
---

# 191. Casts from the Pi carry captions under the phone's own caption choice, and a speaker's Bluetooth link is measured and logged

- Status: Accepted (built and unit-tested; the Python helper and the host scripts tested against fakes; not driven against a real TV or the soundbar from here)
- Date: 2026-11-27
- Amends: [ADR 152](0152-a-target-is-anything-the-pi-plays-to-and-each-has-its-own-queue.md),
  [ADR 156](0156-one-casting-brain-the-pi-casts-with-the-phones-own-code.md),
  [ADR 165](0165-a-speaker-is-kept-awake-and-has-one-volume-its-own.md)
- Builds on: [ADR 78](0078-captions-are-on-in-english-and-off-is-for-one-item.md) and
  [ADR 123](0123-subtitles-off-stays-off.md) (the caption choice), [ADR 180](0180-the-selected-device-owns-the-dock-bar-and-the-remote-page-is-a-card-over-its-list.md)

## Context

Two things from Dewi's reports of 21 to 23 November (0.1.944):

1. **A cast from the Pi had no captions** (20261123T080258). A phone-direct cast serves `subtitles/N.vtt` in its manifest and the phone's
   Subtitles button drives the TV; a cast the Pi makes built its `CastItem` without the resolved tracks (`HomeDownloads.castOf`), the
   Chromecast helper had no text-track code and the remote page had no CC control.
2. **The soundbar's audio cuts out** (his guess: "the Pi is too far away"). Nothing in a report could say whether it was range.

## Decision

### Captions: one set of tracks, one choice, one picker

- **ONE BRAIN for the tracks.** `TvSubtitles` in `:core:cast` holds what the TV can read (WebVTT and SubRip) and maps the resolved
  `SubtitleTrack`s to `CastSubtitleSource`s. The phone's `CastStreams.toCastItem` filters by `TvSubtitles.carries`, and the Pi's
  `HomeDownloads.castOf` builds its `CastItem.subtitles` with `TvSubtitles.from`. The same `CastStreamServer` then serves the same
  `subtitles/N.vtt` (cleaned and placed for a TV by `TvCaptions`) in the manifest of a cast from the Pi.
- **ONE BRAIN for the language.** `CaptionPick` in `:core:cast` picks a track: an exact language in the order wanted, else the same
  language, never another. `TotumCastTrackSelector` (the phone's) now calls it; the Pi's `ChromecastPlayer` calls it with the receiver's
  tracks. The code normaliser differs by platform (Media3's on the phone, `Locale` on the Pi; `CaptionPickTest` pins the same cases).
- **ONE choice, with the phone's default.** Each deck holds a `CaptionChoice` (on, in English until told otherwise; off stays off across
  items and restarts, [ADR 78](0078-captions-are-on-in-english-and-off-is-for-one-item.md), [ADR 123](0123-subtitles-off-stays-off.md)),
  saved with the deck's queue. A new item is told the choice as it starts (`TargetPlayer.showCaptions`); a choice from the phone
  (`TargetAction.CAPTIONS` with `CaptionsAsk(on, language)`) changes it and is told to the player at once.
- **The helper reports tracks and takes a track.** `chromecast_helper.py` says a `tracks` event (the receiver's text tracks and which is
  active) when they change, and takes `{"cmd":"captions","trackId":N|null}` (`enable_subtitle` / `disable_subtitle`). Picking is not
  done in Python: the helper is transport, `ChromecastPlayer` decides, and logs each decision with its inputs (`wanted`, `offered`, `showing`,
  the verdict). It applies the choice again whenever the receiver lists its tracks, because they arrive after the load.
- **A CC tile on the remote card** (`RemoteCaptions`), the player's own picker tile in the device's colour, only while the device reports
  tracks (`TargetState.offersCaptions`): Off and each offered language, named by the phone's own locale. `TargetState.captions` carries
  `on`, `language`, `offered` and `shown`.
- **Amended 2026-11-28:** the relayed-HLS path now carries the same tracks, as an HLS subtitles group
  ([ADR 156](0156-one-casting-brain-the-pi-casts-with-the-phones-own-code.md)), and a Jellyfin item's own text subtitle streams are offered
  from Jellyfin ([ADR 164](0164-casting-fixes-from-the-diagnostics-of-7-to-11-november.md)). A torrent's embedded tracks are still not.
- **Not covered:** a YouTube item already downloaded at home is cast from its file with no tracks (the file has none); a torrent
  item gets only a sidecar or an MKV's own tracks, as a phone-direct cast does (`fromTheFile`); the receiver must list the
  manifest's text tracks as `MediaTrack`s for `tracks` to say anything, which the phone's selector relies on too, and which has not been
  seen on the LG from the Pi. If it does not, the CC tile never appears and the log says "no track offered yet".

### The Bluetooth link: measured on the host, logged in one counted line

What is available, from the Pi on 2026-11-27 (read-only):

| Source | In the `totum-fetch` container | On the Pi host |
|---|---|---|
| `hcitool con` / `rssi` / `lq` / `tpl`, `btmgmt`, `hciconfig` | installed, but **blind**: `hcitool con` and `dev` list nothing, because the container's network namespace sees no HCI device | works as the `pi` user: `hcitool rssi F4:4E:FD:21:92:6D` gave `-13`, `lq` 255, `tpl` 12 |
| BlueZ D-Bus `Device1.RSSI` | empty for a connected BR/EDR device | the same |
| mpv's log | `Audio device underrun detected.` lines | |

So the container cannot measure the link and the host can. The design follows:

- **A host collector.** `server/systemd/totum-bt-link.service` runs `totum-bt-link.sh` as `pi`: every 15 s it writes
  `at=<epoch> rssi=<n> lq=<n> tx=<n>` for each connected ACL device to `/var/lib/totum-fetch/home/.totum/bt-link/<MAC with _>.txt`, which the
  container already sees as `/home/totum/.totum/bt-link/` (the home mount). `rssi` is `hcitool`'s own: relative to the receiver's best range,
  0 inside it and more negative the further out.
- **One counted line per tick.** While a speaker plays, `TargetDeck.tick` (every 30 s) logs
  `dewidebug <id>: link rssi=… lq=… tx=… (host sample Ns old …), underruns=N since the last line (M in this play); keep-awake reconnects D done
  and F failed, connection drops while playing: K`. Underruns are counted from mpv's log (`UnderrunCounter`), reconnects from the keeper, drops
  from `lost`. A stale sample (over 90 s) says STALE; no sample says to check that the unit is installed and the speaker is on the Pi's own
  adapter. A report can then show whether the underruns follow a falling RSSI or a low `lq`.
- **What it cannot prove.** `hcitool rssi` reads only while the ACL link is up, and an A2DP link that drops and re-forms between two samples is
  seen as a drop (counted) with no number. Whether the numbers mean "range" for this soundbar is for the first report to show.

## Consequences

- A cast from the Pi can show captions and the phone can change them, with the same defaults and the same language rule as a phone-direct cast.
- The remote card gains one tile (only when there is something to pick) and `TargetState` one field, absent in an older Pi's answer (read as
  the default).
- `chromecast_helper.py` changes with the container image: it needs the rebuild and recreate of `totum-fetch` to be read.
- The Pi's host gains one unit (`totum-bt-link.service`); without it the link line says so and nothing else changes.
- **At Dewi's scale** (5,857 queue items, 1,623 subscriptions): captions and the link line are per item and per tick; a deck's saved queue
  gains two small fields.
- **Not seen on a real TV or soundbar from here:** whether the receiver lists the manifest's text tracks and takes `enable_subtitle`, and what
  the soundbar's RSSI does when it cuts out.
