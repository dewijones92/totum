---
title: Ready for other people: licence and reports
kind: todo
status: built 2026-11-07, Batch A (tests owed)
batch: A (2026-11)
priority: medium
updated: 2026-11-07
---

# Ready for other people: licence and reports

Dewi 2026-11-05: GPL-3.0 (yes), with notices for the bundled ffmpeg, QuickJS, Chaquopy and yt-dlp. Reports reach Dewi's Pi only from phones signed in to his home server (and /ingest checks the device token); everyone else gets "Report on GitHub" (the report copied, the issue form opened, PipePipe style) and an in-app list of their reported issues. The PC-screen announce and every Pi call follow the same sign-in.

## Built 2026-11-07 ([ADR 146](../adr/0146-gpl-3-and-reports-go-to-the-pi-only-when-signed-in.md))

`LICENSE` (GPL-3.0), `THIRD-PARTY-NOTICES.md`; the uploader and the PC-screen announce send to the Pi only when signed
in (and then with the device token); a signed-out phone reports through a two-step "🐙 Report on GitHub" dialog with a
redacted preview, copied and opened in the browser, with a list of what it opened. See
[licence-and-reports](../features/licence-and-reports.md).

## Tests owed

A Compose test of the two-step dialog; the new instrumented uploader case (written, not run); a device check that the
link opens the form pre-filled; the announce stopping on sign-out. Needs Dewi: GitHub issues enabled on the repo; the
server's `/ingest` is unchanged (still anonymous).
