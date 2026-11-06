---
title: A move to the Pi sends the file, and every download says what moves where
kind: adr
status: accepted
updated: 2026-11-06
---

# 126. A move to the Pi sends the file, and every download says what moves where

- Status: Accepted
- Date: 2026-11-06
- Amends: [ADR 110](0110-downloads-at-home-the-pi-fetches-with-the-apps-own-code.md) (how a phone copy gets to the Pi;
  the poll interval; the wording of its states)

## Context

Dewi, 2026-11-01 and 2026-11-05: "Downloading at home" and "Delete" are ambiguous; say what is downloading and from
where to where. Report 20261102T132125: "Move to Pi" did not move the phone's file at all. It asked the Pi to download
the item again from the web, then waited two 20 s polls before the app noticed. Both problems sit on seams that had
grown several copies: six places worded a download's state, and the two Pi callers (`HomeJobsClient`, `HttpAskService`)
each carried their own authed-call shell.

## Decision

- **One Pi call shell.** `HomePiCall` (`core/data/home`) owns the signed-in check, the token header, IO and HTTP error
  handling and the Diag lines; `HomeJobsClient` and `HttpAskService` both use it, and so will speakers, Jellyfin and
  the PC screen.
- **One state, one mapper.** A download is a `Transfer` (from, to, phase, percent, reason) in `:core:domain`, with
  places Web, Phone, Pi; `percentOf` is the one percentage. `transferText` in `ui/common` words it for every surface:
  `⬇️ Web → 📱 Phone · 45%`, `⬇️ Web → 🏠 Pi · 45%`, `📥 🏠 Pi → 📱 Phone · 45%`, `📤 📱 Phone → 🏠 Pi · 45%`, `⏳ … ·
  waiting`, `❌ … · failed: why`; at rest `✅ On 🏠 Pi · …` and `✅ On 📱 Phone and 🏠 Pi`. Rows, the Library card,
  torrent chips, the strip, every notification and the home notices use it; a copy that comes from the Pi says so.
  Every delete says where from: Delete from phone / from Pi / from both.
- **A move sends the file.** `PUT /files?id=…&audioOnly=…` on the Pi. The body is one line of JSON (the item) and then
  the file's bytes, so the metadata and the file arrive together and a half-sent upload registers nothing. The Pi
  requires a `Content-Length`, refuses one above its limit (free space less the 20 GB floor, at most 64 GB, announced
  to the phone as `uploadLimitBytes` in the job list so the phone can refuse before sending), streams exactly that many
  bytes to a temp file beside the downloads (`*.incoming.part`, cleared at start-up), renames it into place and records
  it as a finished download, so jobs, serving, filing and deletion treat it like any other. It declines when the Pi is
  already fetching that item or already has the full copy and the file is sound only. The phone shows a
  `📤 📱 Phone → 🏠 Pi` strip row with a real percentage and an ongoing notification, stops sending when the strip row is
  cancelled, and for a move deletes its copy only after the Pi's job list shows the file. The web re-download remains
  only when the phone has no file to send.
- **Poll every 2 s while a job is busy** (was 20 s), and the strip row observes the known state rather than sleeping
  on its own timer.

## Consequences

- The Pi's nginx must let a large body through: its `/fetch/` location needs `client_max_body_size` raised (default 1 MB)
  and `proxy_request_buffering off`, as `proxy_buffering off` already is for downloads. Until then an upload ends with the
  phone saying the Pi did not answer.
- A refusal that comes before the body is read (too big, no length) may reach the phone as "didn't answer" rather than
  its reason; the `uploadLimitBytes` pre-check avoids the common case.
- An uploaded torrent or video lands where Pi downloads land, so it is filed into Jellyfin like one (ADR 111).
- Every Pi-bound call that grows from here goes through `HomePiCall`.
