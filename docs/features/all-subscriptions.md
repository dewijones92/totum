---
title: Every subscription in one list, and every row tinted by pillar
kind: feature
status: shipped
area: subscriptions
updated: 2026-09-24
---

# Every subscription in one list, and every row tinted by pillar

Dewi, 2026-09-24: *"a shared place to see subscriptions across my youtube subs and podcast subs … in
one list … sorted by time desc … color tint background based on type"*. His choices when asked:
**latest upload first**, **a Subscriptions entry in Library**, **the tint on every row**, **peach for a
video, lemon for a podcast** (the brand's own hues; cyan stays "played", tangerine stays "active").

## Library → Subscriptions

One list of every followed show (the podcast store) and every YouTube channel (the account's
subscription list, signed in only), ranked by `latestUploadFirst` in `:core:domain`:

- A source's newest upload is the newest DATED item that `isFrom` it — a podcast by `sourceId`, a
  channel by `sourceId` or by the `UC…` id in the item's `sourceUrl` (account feed items arrive under
  `ytfeed:SUBSCRIPTIONS`, not their channel).
- Items come from what is already on the device: stored podcast episodes, and the cached account
  Subscriptions feed (`FeedChoice.Account(SUBSCRIPTIONS).cacheKey()`, the same key the Videos tab
  writes). No network on open. **So a channel with nothing in the last ~45 cached feed items reads
  "No recent upload seen" and sorts below every dated source, by title** — YouTube does not tell a TV
  client when you subscribed, and fetching 1,600 channel pages to find out is not an option.
- Each row: the source's artwork, its name, and `📅 <age> · <latest title>`, tinted by pillar. Tapping
  opens the source page through the shell (`LocalOpenSource`).

Diagnostics: `[subs] all subscriptions: shows=N channels=M cachedVideos=K withADatedUpload=D/T top=[…]`,
logged on change only.

## The pillar tint

`pillarRowTint(pillar)` — `primaryContainer` (peach) for a video, `tertiaryContainer` (lemon) for a
podcast, luminance-aware alphas — is painted by `MediaItemRow` UNDER its existing `tint`, so a played
row is pillar wash + cyan and an unread notification row still stands out (its peach is 35% against
the video wash's 14%). The first lemon alpha (30%) moved the blue channel by 0.21 in the pixels and was
cut to 18%.

## Second review (2026-09-24), fixed

- The ranking ran on the main thread and re-filtered every item per source (≈1,600 channels × a few
  thousand items). Now one grouped pass (`newestBy` source id and `UC…` id), `flowOn(Default)`.
- The cached feed was read once per ACTIVITY (the view model is activity-scoped); now it is re-read
  each time the list is opened (a cold `flow {}` under `WhileSubscribed`). Pinned by a test that fails
  on a read-once version.
- **One function paints a row's background**: `rowTint(pillar, playState, unread)` = pillar wash with at
  most one state wash over it. Unread moved from `primaryContainer` (the same colour as the video
  wash) to `primary` at 15%, and the pixel test now pins cyan over the peach wash, unread standing
  clear of read, and an upper bound on each pillar wash on its own.
- A loading state instead of flashing "No subscriptions yet"; system Back inside Library's sub-screens
  returns to Library instead of leaving the app.

## Found on the way

**Every channel avatar was being dropped.** YouTube sends them protocol-relative (`//yt3.ggpht.com/…`),
which `HttpUrl.parse` rejects, so all 1,600 channels had none — invisible until this list tried to show
them. `SubscriptionsResponseParser` now prefixes `https:`; the reload logs
`account channels=N withAvatar=M`, which read `1600/1600` on the emulator after the fix.

## Tests

`SourceActivityTest` (ranking), `AllSubscriptionsViewModelTest` (both pillars merged, the right cache
key), `AllSubscriptionsListTest` (instrumented: both listed, tap opens), `PlayedRowIsTintedTest`
(pixels: each pillar's wash, and cyan on top, both themes), `SubscriptionsResponseParserTest`
(protocol-relative avatar — red on the old parse).
