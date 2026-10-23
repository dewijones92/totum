---
title: Watch from the phone on the PC display (a browser receiver page)
kind: todo
status: requested 2026-10-23, decided
area: casting
priority: high
requested: 2026-10-23
updated: 2026-10-23
---

# Watch from the phone on the PC display (a browser receiver page)

Decided 2026-10-23 (Dewi): a **browser receiver page**. A Totum page opened once in Edge on the PC (bookmark or QR from
the phone) shows up as a cast target; the phone serves the same static DASH stream it serves the TV (ADR 68: relayed
video packets plus Totum's processed audio), the page plays it with Shaka (as `tools/cast-bench/standin/` already does),
and the phone stays the remote over a WebSocket the page keeps open. Same Wi-Fi or WireGuard; no Pi in the middle.
To check: HEVC in Edge needs the Windows HEVC extension; AV1 depends on the GPU; pick formats per device as for TVs.
Relates to [seamless-cross-device-playback](seamless-cross-device-playback.md).
