---
title: A token per device for the home server, none in links
kind: todo
status: built — all of layers 1 and 2 shipped; the old shared token was retired 2026-11-06 (per scrub-old-token-from-links, not re-checked on the Pi from this doc); only an optional device list in Settings is open
area: torrents/pi/security
priority: high
requested: 2026-10-24
updated: 2026-11-14
---

# A token per device for the home server, none in links

Dewi, 2026-10-24, after pasting a stream link that carried the master token: "What is the best long-term solution?",
then "Can you do layers 1 and 2". Decision and design: [ADR 90](../adr/0090-a-token-per-device-and-none-in-links.md).

- ✅ `server/totum-auth` on the Pi; the totum. gate asks it (old shared token still accepted).
- ✅ The app sends its token only as a header (player, cast, OkHttp); links carry none; sign-in names the device;
  sign out per device.
- ✅ totumauth. sign-in mints device tokens (17:09); an app naming no device still gets the old token meanwhile.
- ✅ `server.py retire-legacy` was run on 2026-11-06 (header and link both answered 401 afterwards; the phones were unaffected because they send device tokens), recorded in [scrub-old-token-from-links](scrub-old-token-from-links.md). This line read ⏳ "in progress" until 2026-11-14. [ADR 90](../adr/0090-a-token-per-device-and-none-in-links.md)'s "To finish: … retire the old shared token" has not been updated and is stale on the same point.
- ✅ 2026-11-09 (`c9284279`): the token is judged on every hop of a redirect and never leaves the home host (ADR 90 amended).
- Still open, optional, not built (checked: no such Settings row in `strings.xml`): a list of signed-in devices in Settings with sign-out per device (today: the Pi CLI).
