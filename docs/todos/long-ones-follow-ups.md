---
title: Long ones follow-ups
kind: todo
area: library
priority: medium
status: open 2026-11-20
updated: 2026-11-27
---

# 🧘 Long ones follow-ups

Built in [ADR 185](../adr/0185-long-ones-are-built-from-what-is-cached-and-fill-in-lengths-lazily.md); feature doc
[long-ones](../features/long-ones.md).

- [ ] **Keep the channel table fresh without the Subscriptions page.** Long ones reads each followed channel's newest upload from
  the Room table that only the All subscriptions page refills (6 h, ~13 minutes of trickle for 1,609 channels). A background
  refill needs its own design (a WorkManager window is 10 minutes). Dewi's call.
- [ ] **Two vetoable calls**, both recommendations in the ADR: podcasts are included under the same per-source cap; the page does
  not obey ADR 19's dopamine-fast gate. Either is a one-line change.
- [ ] **Check on the phone:** the emulator on the real account answered every ask (32 in 11.5 s); the phone's home IP is the one
  YouTube bot-blocked in October, so see whether `lengthCheck` reports "unknown" there (`[longones]` lines, `polite.botRefusals`).
- [ ] **An instrumented test of the tile and page** over a fake container with a stubbed `LongOnes`.
- [x] **No ended live streams, nothing over 4 hours unless a podcast** (Dewi, 2026-11-27, after a 9-hour ATP practice stream
  appeared): done, see the 2026-11-27 amendment of ADR 185. Check on the phone that the ATP stream is gone from the page and that
  `[longones]` lines show `liveStreams=` and `tooLong=` moving.
