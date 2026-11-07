---
title: A daily 🤖 For you feed
kind: todo
status: built 2026-11-07, Batch A (tests written 2026-11-07; device checks owed)
batch: A (2026-11)
priority: medium
updated: 2026-11-07
---

# A daily 🤖 For you feed

Dewi 2026-11-05: each morning Haiku picks about 10 videos and podcasts from outside his subscriptions, from what he watches, stars and likes (a JSON schema answer); the app's own searches find each, shown as a "🤖 For you" group in Library. Dry runs on real taste before it ships (CLAUDE.md). Reuse ✨ Ask's taste and the AI service.

Whole-batch review: one morning job with the morning digest; move `tasteOf` from `ui/ask` to `core/data` and add stars and likes to it; the call goes through the shared AI job.

## Built 2026-11-07

Built with the morning digest as one morning job ([feature](../features/morning-job.md)): a Pi task `for-you` (no tools)
picks up to 6 videos and 4 podcast shows from Dewi's taste (now including starred and liked titles), the app's own
search finds each one (a video outside the channels he follows; a show's newest episode), and the "🤖 For you" playlist in
Library is replaced each morning at about 07:30 (Settings → Morning, off by default, with a "Run the morning job now"
button). `tasteOf` moved to `:core:data`. Dry-run on a made-up household's taste, twice (the second with the first day's
picks listed).

Decisions, dry runs and limits: [ADR 143](../adr/0143-for-you-and-the-morning-digest-are-one-morning-job.md); the feature doc: [morning-job.md](../features/morning-job.md).

## Tests

Exist (JVM): `MorningRunTest` (finding, replacing, a failure's reason, nothing found); `MorningRulesTest` (`tidyForYou`:
a followed channel or show by name or by containing it, owned or picked lately, named twice, kinds that are not a video or
podcast, no title, the caps of six and four, extras cleaned; replacing a playlist with the same items changes nothing and
with fewer drops the rest); `ForYouPlaylistTest` (the new list is filled before the old is cleared; seen failing when the
order is reversed, so it can fail); `FindPickTest` (a followed channel's hit skipped by name ignoring case and punctuation,
members-only, short and live hits skipped, no pick when nothing is suitable, a show's newest episode and a followed show
skipped, a feed that will not load, other kinds never searched). `findPick` now takes `AskCopies` and a
`PodcastRepository` instead of the whole container so it can run with fakes.

Still owed, with the reason:

- `appTaste` (the taste the job sends), including the liked page failing: it reads `AppContainer` and a process-wide cache of
  the liked titles, so it needs the same narrowing `findPick` got, or a device.
- Compose/device: Settings → Morning, "Run the morning job now" fills the playlist and shows in the strip; the playlist in
  Library.
