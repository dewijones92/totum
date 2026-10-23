---
title: The Pi back end lives in this repo
kind: adr
status: accepted
updated: 2026-10-23
---

# 85. The Pi back end lives in this repo

- Status: Accepted
- Date: 2026-10-23

## Context

Dewi, 2026-10-23: *"Is the Totum front end and back end all in this repo? If not, I think it's a good idea if it
is."* The crash sink already lived here (`tools/crashlog-server`), but the torrent chain the app depends on,
Prowlarr, FlareSolverr, TorrServer with its watchdog, and totum-audio, lived in the Pi's `dot-files` repo, so a
change to the app and the service it talks to were two repos and two histories. totum-audio's running code had
also drifted from what was committed: a fix from 2 Aug (the token stamped onto segment URLs) was never committed.

## Decision

- `server/` holds the four services, each still its own compose project (`name:` pinned, so the project and the
  built image keep their names), deployed from the Pi's `~/code/totum` exactly as the crash sink is. The
  watchdog's units are symlinked from `server/torrserver/`.
- What is the whole Pi's stays in `dot-files`: nginx (one generated config; it proxies by container name),
  gluetun and `reattach-passengers.sh`. `server/README.md` documents that boundary and the checks.
- Moved one service at a time, each recreated from its new directory so docker's labels point here (the
  re-attach script and `updatepi.sh` find projects by those labels), and each checked before the next.
- totum-audio moved as it was running (byte-identical to the container's `/app/server.py`), not as committed.

## Consequences

- A push that only touches `server/` still runs the app's CI and makes a release, as any push to `main` does.
- `dot-files` keeps the Pi-wide docs and points here for these four; its `vpn-stack` comments and passenger
  recreate loop name the new paths.
