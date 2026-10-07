---
title: The "ask YouTube directly" fallback serves TV URLs that 403
status: open — found 2026-10-07 in report 20261007T092041 (0.1.575); not investigated
updated: 2026-10-07
---

# The "ask YouTube directly" fallback serves TV URLs that 403

When yt-dlp offers a single 360p stream, `VideoResolver.betterQualities` asks the signed-in TV client
for the ladder (`resolved as the signed-in TV client`, `solved 2/2 n parameter(s)`). On 0.1.575 that
produced 6 qualities to 1080p 19 times, and 15 of them ended in a fatal `HTTP 403 from client TVHTML5`
with ~6 h of lease left, still refused 5–7 s after issue (so not the fresh-URL window, ADR 13).

Untested hypotheses: the `n` is solved against the web player (`1b3be681`) rather than the TV client's
player; or TV URLs need something the app does not send. The path had not run in any earlier report,
so there is no baseline. ADR 11's 2026-10-07 amendments should keep it from being reached in the case
seen; when it is reached and fails, recovery falls back to a copy on disk or the sound.
