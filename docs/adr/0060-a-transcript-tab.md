---
title: A transcript tab, for videos and podcasts
kind: adr
status: accepted
updated: 2026-10-15
---

# 60. A transcript tab, for videos and podcasts

- Status: Accepted
- Date: 2026-10-15

## Context

From the product review of 2026-10-15, Dewi chose a transcript in its own player tab that scrolls with playback,
highlights the line being spoken, seeks when a line is tapped, has a search box, and shows only when a transcript
exists. For YouTube he chose human captions in his language first, falling back to auto-generated ones; podcasts use
the feed's `podcast:transcript`.

## Decision

- `:core:data/transcript`: `TranscriptParser` (VTT, SRT and the Podcasting 2.0 JSON format; tags stripped; YouTube's
  rolling auto-captions collapsed so a line is not repeated), `pickTranscriptTrack` (human in the language, then auto
  in it, then any human; TTML is not read), and `TranscriptLoader`, one entry for both pillars: a video reads its
  caption track; an episode reads the transcript link from its feed (`ParsedEpisode.transcripts`), matched by the
  episode's id (guid, else enclosure), with the feed cached for 30 minutes. No database change.
- The player's tabs become Up next · Related · Comments · Transcript · Details (a podcast: Up next · Transcript ·
  Details), the Transcript tab present only when `rememberHasTranscript` finds one (`playerTabsFor`).
- The tab: the source line ("From English (auto-generated)"), a search box with "n of m" and ↑/↓, and the lines in a
  fixed-height list: the playing line highlighted and kept in view; dragging the list stops following until "↓ Follow
  along"; tapping a line seeks to it.

## Consequences

- Opening the full player on a podcast fetches its feed (once per 30 minutes per feed) to know whether a transcript
  exists, on any network.
- Logs (`transcript`): availability per item, what was loaded from where and how many lines, failures, a tapped line,
  scrolling away.
- Rolling-caption collapsing applies to auto-generated YouTube tracks only; human captions and podcast transcripts keep
  every line as written. A podcast's VTT is preferred over SRT over JSON. Parsing runs off the main thread; the feed
  cache keeps only each episode's transcript links (20 feeds), and the last 10 loaded transcripts are reused. Each
  availability decision is logged with its reason, item and feed.
- Tests: `TranscriptParserTest`, `TranscriptLoaderTest`, `RssParserTest`, `PlayerTabsForTest`. Verified on totum-api35
  for a captioned video (60 lines, a tapped line seeks and is highlighted). Not yet seen on a device: a podcast
  transcript.
