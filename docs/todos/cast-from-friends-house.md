---
title: Cast from a friend's house, home items fetched from the home server
kind: todo
status: built; tests written; the Pi side not applied
batch: C (2026-11)
priority: medium
updated: 2026-11-27
---

# Cast from a friend's house, home items fetched from the home server

Dewi, 2026-11-20 (all recommendations accepted): at a friend's house the phone drives the friend's Chromecast through the normal Cast
session; a Jellyfin film, a torrent at home or a home copy is fetched by the Chromecast from the Pi over the internet, by a link scoped to
one session, valid six hours, revoked when the cast ends. Decision, threat note and evidence: [ADR 182](../adr/0182-a-friends-chromecast-fetches-home-items-from-the-home-server-by-a-session-link.md);
how it behaves and how to test it: [casting.md](../features/casting.md).

## Built 2026-11-27

The Pi's public gate (`PublicCasts`, listener on 8773), the mint and revoke routes (`CastOutRoutes`, `HomeOnlySources`), the casting brain's
`openFor` and `serve`, the phone's route choice and client (`PublicCastRoute`, `HttpPublicCastApi`, `HomeLan`, `HomeLanProbe`) wired into
`CastStreams`, the nginx patch and snippet (`tools/handoff/cast-out/`), the Shaka run (`tools/cast-bench/standin/public-standin.sh`).

## To do

- **Dewi or the orchestrator, on the Pi:** apply the nginx patch and snippet, set `TOTUM_CAST_PUBLIC_BASE`, deploy a CLI release with this
  change (`tools/handoff/cast-out/README.md`).
- **A real run:** the Chromecast on a second phone's hotspot, Dewi's phone on it too (the steps are in casting.md). Nothing here has fetched
  from a real Chromecast, over real TLS, or run the phone's converter on a device.
- **Pre-mint before the hand-over.** The Cast converter runs on the main thread and now waits up to 7 s for the mint on the away path, once per
  item. Minting when the Cast session becomes available (and for the next queue item) would remove the wait.
- **Skip silence on this route.** Left off because the phone cannot map the TV's clock back; the Pi's own targets can (`CastTimeline`). Exposing
  the timeline in the answer would let `TimeMappedCastPlayer` map it.
- **Live items** are not carried by the gate; they stay phone-direct.
- **A setting**, if Dewi wants to switch the route off.
- **The picker on a friend's Wi-Fi** still offers the home devices (ADR 169's rule); `HomeLanProbe` could replace `PlayOn.atHome` if Dewi
  wants one rule everywhere.
