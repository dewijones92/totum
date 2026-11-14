---
title: A crash screen for everyone, reports to the Pi as a setting, and a state dump with every report
kind: adr
status: accepted
updated: 2026-11-14
---

# 159. A crash screen for everyone, reports to the Pi as a setting, and a state dump with every report

- Status: Accepted
- Date: 2026-11-13
- Builds on: [ADR 146](0146-gpl-3-and-reports-go-to-the-pi-only-when-signed-in.md) (the GitHub flow and the sign-in
  rule), [ADR 90](0090-a-token-per-device-and-none-in-links.md) (device tokens),
  [ADR 53](0053-google-drive-backup.md) (the backup, and its restore merge),
  [ADR 140](0140-ask-answers-about-your-own-app-with-jq-only.md) (the Ask snapshot and the one redactor)

## Context

Dewi, 2026-11-13: a crash screen like PipePipe's for everyone; reports to the Pi optional; every diagnostics report to
bring the app's whole state, so a problem can be reproduced with his queue and settings rather than described; a button
to send that state on its own; and a way to load a dump into the emulator. He delegated the decisions to the
coordinator and agreed. A crash used to be invisible on the phone: the handler wrote a report and the process died.

## Decision

- **A crash screen at the next launch.** `CrashReporter`'s handler still writes the report first, and now also a
  `CrashRecord` (`files/last-crash.json`: time, thread, exception, message, the whole stack trace with its causes, the
  report's file name). `MainActivity` starts `CrashActivity` on a cold start when a record exists (never in a test run).
  It shows "❌ Totum crashed", when, the message and the stack trace, and: **🐙 Report on GitHub**, **📋 Copy**,
  **🏠 Send to my Pi** (signed-in phones only) and **Continue to Totum**. Continue and Back clear the record, so the screen
  is shown once. Not a separate process: a crash activity in `:crash` would run the whole `Application` again, and "next
  launch" was in the brief.
- **One GitHub flow.** The screen reuses ADR 146's preview dialog (`GitHubPreviewDialog`, now `internal`) and the
  one redactor; there is no second copy. `GitHubReport.draft` takes an optional `trace`: the copied body keeps the **whole**
  stack trace under a "Stack trace" heading, and the link keeps what fits under 7,000 characters (activity lines go first,
  then the trace is cut at a line, then the note). When the link's trace is cut the dialog says so, because the copied
  body is the one to paste. Copy redacts too: a message can carry a token.
- **Sending to the Pi is a setting.** "🏠 Send diagnostics and crash reports to my home server", on by default, shown
  when signed in. `DiagnosticsUploader.upload(becauseSomeoneAsked = false)`, the launch-time upload, posts nothing when it
  is off and says so in the log. A person tapping send (Settings' hand-send, the crash screen's button, "Send my state")
  is not automatic and goes. With it off, Settings' report row and the tile offer GitHub, as for a signed-out phone.
- **A state dump with every crash or diagnostics report.** `StateDump` (`:core:data`) gathers nothing of its own. It takes
  the Ask snapshot's inputs (`AskStateSource.inputs()`, formerly private `gather()`), puts the live queue back into the
  backup (so the backup section restores), adds every SharedPreferences file (settings, Exsurge, daily alarms, pins,
  trail and the rest) and runs the lot through `StateScrub` and the one `ReportRedactor`:
  `{format, kind, takenAtMs, appVersion, installId, backup, state, preferences}`, gzipped (about 94 KB for 800 KB on the
  emulator). The `state` section is the Ask snapshot itself: queue, history, playlists, groups, subscriptions, downloads
  (the list, not the files), progress, listens and other stats, the trail, settings and the event log. A dump follows a
  report only after the report was accepted, once per upload run, and a dump that cannot be gathered never stops a
  report being sent or deleted. *(Amended 2026-11-14: the dump is now linked to its report and the two stay pending
  together; see "Amendment" below.)*
- **Credentials never go.** `youtube_account*`, `totum_visitor`, `totum_screen` (the PC screen's access key, added
  2026-11-14: it was in every dump), `totum_drive_backup`, `totum_test_holds` and
  `totum_diagnostics` are left out whole, and every string value in them is added to the redactor's secrets, so the same
  token cannot come back through an event line. Keys that name a secret (token, secret, password, cookie, refresh, api
  key…) are dropped everywhere. `StateDumpTest` seeds fake home-server, Prowlarr, YouTube access and refresh tokens, the PC screen key and a
  cookie in the preferences and in event text and asserts none appears, nor the home server's host; and on 2026-11-13 a
  real dump built by the emulator (signed in to YouTube and to the home server) was checked against the emulator's own
  four secret values: all absent.
- **"📤 Send my state to Claude"** is a Settings → Diagnostics row, shown only when signed in, that posts the same dump
  with no report. It runs as a tracked task (the progress strip) and ends with a ✅ or an honest ❌.
- **The server keeps five** *(amended 2026-11-14: five is now only for a dump with no report id; a report's own dump is kept
  while the report is unconsidered)*. `POST /ingest/state` (`tools/crashlog-server/app/states.py`, which imports no web framework)
  refuses anything not gzip, over 32 MB packed or 256 MB unpacked, or not JSON of kind `totum-state-dump`; keeps the
  dump under `/data/states/<time>-<install>-<version>.json.gz`, and deletes all but the newest five. Reports are untouched.
  `GET /api/states` lists them and `GET /state/latest` downloads the newest.
- **Loading one.** `tools/emulator/load-state.sh [dump]` fetches the newest dump from the Pi by read-only ssh (or takes a
  file), refuses any device that is not `totum-api35`, and broadcasts to the debug-only `StateDebugReceiver`, which
  reads the backup section and calls `BackupService.restore`, the same merge Drive restore uses but with the queue. It
  switches queue auto-download off on the emulator, prints what the app logged it restored, restarts the app so the queue
  is read, and checks the YouTube sign-in file is present and non-empty before and after. A restore carries no sign-in, so it
  cannot change it. Stats, the trail, downloads and Exsurge state are in the dump for reading but are not restored.
  `DEBUG_CRASH` shows the crash screen and `DEBUG_DUMP_STATE` writes the phone-shaped dump to `files/` for inspection.

The allow-list, the five, "once per upload run", the 32 MB cap, Continue clearing the record and the auto-download switch-off
are the builder's calls, open to change.

## Amendment, 2026-11-14: a dump belongs to its report

Dewi: *"I want app state to be auto sent with every diags and only gets deleted once the diags is considered."* The newest
five were a guess at how long a dump stays useful; what actually decides it is whether anyone has looked at the report it
came with. A dump that outlived a ✅ report was clutter, and one that was pushed out by five newer ones before anyone
read its report was the state of exactly the problem still to be diagnosed.

- **Linked by the report id, in a header.** The sink already answers `POST /ingest` with `{"id": …}`. The phone posts the dump
  to `/ingest/state` with `X-Totum-Report-Id: <id>` (`?report=<id>` is accepted too, for curl). Not a combined upload: that
  needs a new route and nginx body limit, and the two routes already exist with the right caps. A header rather than a path
  suffix because it survives any nginx `location` match (`= /ingest/state` or a prefix) with no change.
- **The report and its state stay pending together on the phone.** When the sink accepts a crash or diagnostics report the
  phone writes `files/diagnostics/<report>.json.receipt` (the id and the number of state attempts) and keeps the report file
  until the state settles. A later upload run sends **only the state**, linked to the same id, and never posts the report
  again; once the state is accepted both files are deleted. A dump that cannot be gathered counts as a failed attempt. Up to
  `MAX_STATE_ATTEMPTS` (5) runs; a refusal a retry cannot fix (HTTP 4xx other than 408 and 429, including 413) or a dump
  over 31 MB (under the 32 MB nginx and server cap) is **not retried**. Both give up loudly in the log and delete the
  pending files, because the report was delivered. The dump is gathered once per run and posted once per report. Every
  outcome is a `diagnostics` line naming the report id and the HTTP code. A receipt the phone cannot read is treated as
  "accepted, id unknown", so the report is never posted twice.
- **The server keeps a dump while its report is unconsidered.** A linked dump is `/data/states/linked/<report id>.json.gz`, one
  per report (a second upload for the same report answers 200 and stores nothing). "Considered" is the one definition behind
  the ✅ in `triage.sh considered` (every finding judged, or the report itself for one triaged before findings existed), so a
  finding left in `new` keeps the dump. The dump is deleted the moment the report becomes considered (finding added or
  updated, report triaged, all reports of one exception triaged), when its report is pruned (checked after every
  ingest) and at start-up, each deletion logged as `dewidebug state: deleted linked dump <name> (<bytes>): <why> [<trigger>]`.
  A dump for a report that is already considered is answered 202 and discarded; for a report the server never had, 404.
- **A safety cap.** Linked dumps are bounded by `CRASHLOG_MAX_UNCONSIDERED_STATE_MB` (default 1024). Past it the oldest is
  dropped, never the newest, with a `BUDGET EXCEEDED` line naming the dump whose report is still unconsidered.
- **Older phones and files.** An upload with no report id (an older phone, or "Send my state") is kept apart as before, newest
  five. Dumps already on the Pi's disk are exactly that kind, so they are left as they are and still listed.
- **Reading them.** `triage.sh considered` marks a report that still has its dump with 📦; `triage.sh state <report id>` (or
  `GET /api/report/<id>/state`) fetches it, and says whether the report is considered when there is none; `load-state.sh
  <report id>` loads it into the emulator; `triage.sh show <report id>` prints the reporter's note and key facts, saves the
  report's screenshots (`considered` marks them 📷 N and a note 📝) and says whether the dump is held; `/api/states` lists both kinds; `/state/latest` is the newest of either.
- **Deploy order.** Crashlog server first, then the app: a new phone against the old server sends the header, which the old
  server ignores, so the dump is kept as an unlinked one; an old phone against the new server is the unlinked case. Nothing
  in nginx changes (the header is forwarded by default and the 32 MB `/ingest/state` limit already covers the size).

## Consequences

- A crash is no longer silent on the phone, and a stranger's crash can be reported on GitHub with its whole trace.
- **`/ingest/state` is as unauthenticated as `/ingest`.** The brief assumed the server checks the device token; the crashlog
  sink does not (ADR 146). A state dump is far more personal than a crash report, so it should get nginx's `auth_request`
  against `totum-auth` (ADR 90) before the app is released widely. Until then the app sends the token and the server
  ignores it, and a stranger could post junk dumps (bounded: five kept, 32 MB each).
- A subscription's feed address keeps its path in the dump (identifier mode, so the backup restores), the same limit ADR
  140 states for the Ask snapshot; the dump stays on Dewi's Pi.
- Building a dump takes about 11 s on the emulator (the Ask snapshot over 2,200 listens); it is off the main thread and
  runs after the report is sent.
- The home-server setting is not in the Drive backup: it is device-shaped.

## Tests

`StateDumpTest` (core/data, 6), `GitHubReportTest` (5 new: the trace in body and link, cut from the link only, events go
first, redacted, absent when none), `DiagnosticsUploaderTest` (the setting off posts nothing and keeps the file, a person's send bypasses it, signed out,
test run, non-fatal reports bring no dump, send-my-state results; since 2026-11-14 also: the state carries its report's id,
a failed or ungathered state keeps the report and its receipt pending together, the retry sends only the state with the same
id, nothing is sent twice, two reports in one run each get their own state from one dump, a 413 or 400 is not retried and is
logged with its code, five failures give up loudly, a dump over the limit is not posted, a reply with no id sends an unlinked
state, an unreadable receipt never reposts the report), `ReportReceiptTest`, `ReportSinkTest`, `CrashRecordsTest` (7), and the server's `test_states.py` (12,
20 since the amendment) and `test_state_endpoint.py` (5, in a `python:3.14-slim` container with the pinned requirements and httpx:
30 tests, all green; the amendment adds `test_report_states.py` (21) and 8 linked-dump tests in `test_states.py` and `test_report_brief.py` (12: screenshots, note, brief), 71 in all, run
in a venv with the pinned requirements and httpx). On the emulator: a real uncaught exception via `DEBUG_CRASH` wrote the record and the next launch showed the
screen (screenshot checked, Continue cleared it); the GitHub preview shows the trace; a real dump was built and checked for
tokens; `load-state.sh` loaded a modified dump (a playlist the emulator lacked, a three-item queue, a changed setting),
a fresh dump read back all three, the original was loaded again, and the sign-in file was present before and after each.

## Tests still to write

A Compose test of the crash screen's buttons; an instrumented test that the real handler writes the record; nginx
`auth_request` for `/ingest/state` (in `dot-files`, the Pi's to deploy).
