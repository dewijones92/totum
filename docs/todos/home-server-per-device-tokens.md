---
title: A token per device for the home server, none in links
kind: todo
status: in progress
area: torrents/pi/security
priority: high
requested: 2026-10-24
updated: 2026-10-24
---

# A token per device for the home server, none in links

Dewi, 2026-10-24, after pasting a stream link that carried the master token: "What is the best long-term solution?",
then "Can you do layers 1 and 2". Decision and design: [ADR 90](../adr/0090-a-token-per-device-and-none-in-links.md).

- ✅ `server/totum-auth` on the Pi; the totum. gate asks it (old shared token still accepted).
- ✅ The app sends its token only as a header (player, cast, OkHttp); links carry none; sign-in names the device;
  sign out per device.
- ✅ totumauth. sign-in mints device tokens (17:09); an app naming no device still gets the old token meanwhile.
- ⏳ Dewi updates and signs in again on his phone; then `retire-legacy` (the old token stops working everywhere).
- Later, maybe: a list of signed-in devices in Settings with sign-out per device (today: the Pi CLI).
