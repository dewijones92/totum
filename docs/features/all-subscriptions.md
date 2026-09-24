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
- Items come from stored podcast episodes, the cached account Subscriptions feed
  (`FeedChoice.Account(SUBSCRIPTIONS).cacheKey()`, the key the Videos tab writes), and **each channel's
  own public RSS feed** (below). Newest dated item wins per source.

## Every channel's latest upload (added 2026-09-24, Dewi: "look in to it and test in emulator")

The cached account feed only covers ~45 recent videos, so ~1,560 of 1,600 channels read "no recent
upload". Options measured before building:

| Option | Verdict |
|---|---|
| Page the account Subscriptions feed further | Chronological — only ever finds channels that posted recently; cost grows with how far back you go |
| InnerTube browse per channel | Signed-in, heavy JSON, and a resolve per channel |
| **Public per-channel RSS** `youtube.com/feeds/videos.xml?channel_id=UC…` | **Chosen.** No sign-in, ~5–7 KB gzip, ~45 ms, latest 15 uploads with exact `published` times, title, thumbnail. 304/304 requests at concurrency 8 returned 200 in 4.7 s |

`ChannelLatestUploads` (`:core:data`) checks every channel not checked in the last **6 hours**, 6 at a
time, upserting into `channel_latest_uploads` (Room, v24) in batches of 50 so the list fills as it goes.
A failed fetch or an error page keeps what was known; a channel with no uploads is recorded as
checked. Only one check runs at a time. The list starts a check when opened; **pull to refresh forces
one**. Progress shows as "Checking channels… N of M".

Measured on `totum-api35` (1,600 channels): **41 s, 0 failures, 1,565 with an upload, 35 that have never
uploaded**; reopening within 6 h reports `0 due of 1600`. Network ≈ 1,600 × 6 KB ≈ 10 MB per full check.
The v23→v24 migration was verified by rolling a live DB back to v23 and relaunching.

Diagnostics: `[subs] channel check: N due of M …`, `channel check done: Done(due, skippedFresh,
withUpload, neverUploaded, failed, decodedChars, elapsedMs)` (`decodedChars` is the text parsed, not
bytes on the wire), and up to three `channel check failed <id>: <why>` lines.
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
