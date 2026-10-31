---
title: Every state string starts with an emoji, and nothing spoken carries one
kind: adr
status: accepted
updated: 2026-10-31
---

# 43. Every state string starts with an emoji, and nothing spoken carries one

- Status: Accepted
- Date: 2026-10-14

## Context

Dewi, 2026-10-14: *"wherever there is a string val for a state in the app … I want to either prefix or postfix
it with emoji"*. A read-only scan found 356 state strings: 146 statuses, 57 setting values, 101 empty or error
states, and 52 notification texts; 20 already had an emoji and 35 were spoken or used for accessibility.

## Decision

- **Prefix**, in all four categories (Dewi's choices), including notifications and Quick Settings tiles.
- **The emoji lives in the display string itself** (`res/values/*.xml`, and the two tiles' Kotlin literals):
  the one place a state's display text is defined. A `StateEmoji` object prefixed at every call site was the
  first idea; with about 300 call sites it would have been a second place to forget.
- **Nothing spoken or used as a description carries one** (Dewi: never spoken): strings used as a
  `contentDescription`, `stateDescription` or click label, and the alarm's spoken line, are left plain. Strings
  whose call site already adds an emoji (`FactEmoji`, the album kind) or sits beside an icon saying the same
  thing (the out-of-date banner) are left alone, so nothing doubles up.
- **One vocabulary**: ✅ done or on, ❌ failed, ⚠️ partly, ⏳ working or waiting, 📭 empty, 🔒 signed out,
  📡 network, ⚪ off, 🟢 on, ⬇️ downloading, 😴 sleeping or snoozed, 🔔 ringing, 😢 Exsurge off, plus a
  fitting one where a state has its own meaning (🔁 repeat, 🎚️ Even out, 🐘 largest).

## Consequences

- 248 strings changed; tests that matched the old text exactly were updated (32 device tests green).
- A new state string should start with one of the emoji above; review catches it, no check enforces it.
- TalkBack reads a visible emoji by name; Dewi does not use TalkBack, and descriptions stay plain.

## Amended 2026-10-31 (consistency audit): one word and one emoji per state

- **Failure:** 📡 when something could not be *reached* (a network failure), ❌ when it failed otherwise, ⚠️ when it
  partly worked. Always "Couldn't …", never "Could not …".
- **Empty:** 📭, never 🤷.
- **Downloaded** is "📥 On the phone" everywhere (filters, facts, section chips, album and episode counts); it had six
  names and three glyphs (✅ On the phone, 💾 Saved offline, ✅ Ready to play offline, 📥, 💾 …on this phone).
  **Downloading** is ⬇️ (the In progress chip, counts and the episode picker had ⏳); ⏳ stays for waiting.
- **Members only** is 💎 (the details sheet and facts card said 🔒, which means signed out elsewhere).
- **Retry** is "Try again" ("Try again now", "🔁 Try all again (N)"); "Retry now" and "Retry all" are gone.
- **Clear all** is "🧹 Clear all" everywhere, and wraps (the queue's had `softWrap = false`).
- **Listen and Watch** (ADR 70's names): "🎧 Listen mode on", "📺 Watch mode on", "🎧 Listen (sound only)".
- **Subscribe** is "➕ Subscribe"; unsubscribing always asks "Unsubscribe from …?" (Music said "Stop following").
- The queue's "not playable offline" line carries its own ☁️ in the string; its ⚠️ was added in code.
- ⏱️ is for length ("Longest first" had ⏳).
- Duplicate group strings (`groups_new`/`group_new`, …) are one key each.
- **Kept, deliberately:** row tags are upper-case badges (🔴 LIVE, 📱 SHORT, 🎧 BOOK) while facts are words
  (🔴 Live, 🎧 Book chapter), as YouTube draws them; "Show more", "Show all N" and "⬇️ Show N more" are different
  affordances (more of a list, the whole list, the next page of results).

