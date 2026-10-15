---
title: Signed in, the account answers first and yt-dlp is the backup
kind: adr
status: accepted
updated: 2026-10-15
---

# 50. Signed in, the account answers first and yt-dlp is the backup

- Status: Accepted
- Date: 2026-10-15

## Context

With YouTube bot-blocking every anonymous client on the home IP, each blocked play paid for a failed yt-dlp
extraction (web_embedded, then "every client": about 6–8 refused calls) before the signed-in TV client
(ADR 49) answered. "Signed-in first" was tried in July and reverted on report 0.1.312: 15–37 s per resolve,
from QuickJS `n` solves (since replaced by V8, ADR 12: 15–80 ms per solve on the Pixel 7), and most of those
ended in "Source error". That second cause was never found. It may have been these same TV-scale links
being refused (ADR 49), but nobody has shown it.

Two arguments for keeping yt-dlp first turned out to be wrong when measured. The signed-in route is **not**
capped at 1080p (it served 2160p60 HDR for `LXb3EKWsInQ`). And the title is not missing: the TV client's
`/next` carries it (SmartTube makes the same call). Dewi chose signed-in first, with yt-dlp as the
automatic backup.

## Decision

`VideoResolver.extractAndCache`: the store, then SABR if that setting is on, then **`PlayerStreams.signedInFor`**,
built by `fromDirectStreams`, and only then yt-dlp. Signed out, `signedInFor` is null without a network call.
`InnerTubePlayerStreams` remembers an account miss for 2 minutes, so the yt-dlp fallback's own player retry
doesn't ask the account a second time. A TV answer with no title is described by the account's watch page
(`InnerTubeClient.nextAsAccount`, `WatchNextParser`): title, channel and channel id. Length and thumbnail still
come from the player.

Logged: `served by the signed-in account first (…) — yt-dlp not asked`, `described by the account's watch page`;
counted in `resolve.signedInFirst` and `resolve.signedInUnusable`.

## Consequences

- yt-dlp is still the path when signed out, when the account refuses, and for downloads.
- Emulator: two videos served without asking yt-dlp, with titles. Their first resolve, made while the engine was
  still warming up, took 10–11 s (a cold V8 `n` solve). That is the cost yt-dlp also pays. The soak measures it.
- Tests: `VideoResolverFastPathTest` (the account first, yt-dlp not asked; refused, yt-dlp resolves) and
  `SignedInTvPlayerTest` (watch-page details). Both failed against stubs first.
