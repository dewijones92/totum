---
title: Everything Totum runs on the Pi lives in this repo
kind: todo
status: done 2026-10-23 (ADR 85)
area: pi/infra
priority: medium
requested: 2026-10-23
updated: 2026-10-23
---

# Everything Totum runs on the Pi lives in this repo

Dewi, 2026-10-23: *"Is the Totum front end and back end all in this repo? If not, I think it's a good idea if it is."*
Today `prowlarr/`, `flaresolverr/`, `torrserver/` (with its watchdog timer) and `totum-audio/` live in the Pi's
`dot-files` repo. Proposed: move them to `server/` here, each still its own compose project deployed from
`~/code/totum` on the Pi (as `tools/crashlog-server` is), one service at a time, checking search and play after each;
TorrServer sits in gluetun's network namespace, so read the Pi repo's notes on recreating such containers first. The
nginx vhosts stay in `dot-files` (one generated config for the whole Pi), documented here.

Done 2026-10-23: `server/` ([README](../../server/README.md), [ADR 85](../adr/0085-the-pi-back-end-lives-in-this-repo.md)).
