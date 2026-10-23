---
title: Watch from the phone on the PC display (a browser receiver page)
kind: todo
status: built 2026-10-23
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

Design settled 2026-10-23 (Dewi): **the Pi bookmark finds the phone**. A secure Pi page cannot play the phone's plain-http
stream (mixed content), so the bookmark (crashlog server, Google-gated) looks up the phone's latest address and key and
redirects to a receiver page served by the phone itself; video goes phone → PC directly. The phone announces through
the existing open `/ingest`, so no nginx change.

Built 2026-10-23: [ADR 81](../adr/0081-the-pc-screen-is-a-page-the-phone-serves.md). Verified with the emulator serving
and a headless Edge playing (1280×720, pause/play from the phone, back to the phone); the Pi bookmark is checked once
the server is deployed.
