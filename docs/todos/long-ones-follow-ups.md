---
title: Long ones follow-ups
kind: todo
area: library
priority: medium
status: open 2026-11-20
updated: 2026-11-20
---

# 🧘 Long ones follow-ups

Built in [ADR 185](../adr/0185-long-ones-are-built-from-what-is-cached-and-fill-in-lengths-lazily.md); feature doc
[long-ones](../features/long-ones.md).

- [ ] **Keep the channel table fresh without the Subscriptions page.** Long ones reads each followed channel's newest upload from
  the Room table that only the All subscriptions page refills (6 h, ~13 minutes of trickle for 1,609 channels). A background
  refill needs its own design (a WorkManager window is 10 minutes). Dewi's call.
- [ ] **Two vetoable calls**, both recommendations in the ADR: podcasts are included under the same per-source cap; the page does
  not obey ADR 19's dopamine-fast gate. Either is a one-line change.
- [ ] **Check on the phone:** the first open on a real account (how many asks it makes, the megabytes, how many it takes to
  find 12), and whether the anonymous `player` answers there (the bot check) or `lengthCheck` reports "unknown".
- [ ] **An instrumented test of the tile and page** over a fake container with a stubbed `LongOnes`.
