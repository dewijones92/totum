---
title: Text wraps — nothing in the app ends in three dots
kind: feature
status: shipped
area: ui
updated: 2026-09-21
---

# Text wraps, everywhere

**Ask (Dewi, 2026-09-21):** *"dont use ... please in the app - just wrap the text"*.

So no title, channel name, show name, playlist name, screen heading, sheet title or failure
reason is cut short anywhere. A row grows to fit what it has to say: a four-line episode title
is a four-line row.

This **reverses two earlier decisions**, both recorded here because the reasoning behind them
was sound and is now overruled by his preference:

- *"Titles cap at two lines. Long podcast titles were running to five, which made every row a
  paragraph."* ([row-status.md](row-status.md), 2026-07-25.) A screenful now holds fewer rows
  when the titles are long, which is the trade he chose knowing it.
- *"Each line still caps at one line, so an ellipsis can only ever shorten a long channel
  name."* ([upload-dates.md](upload-dates.md), 2026-08-15.) It can no longer shorten anything.

**25 `TextOverflow.Ellipsis` sites and 23 `maxLines` caps went, across 16 files** — 48 lines.
(This said "40 sites across 15 files" until the review of it counted: 40 was the number my sweep
script dropped, which excluded four files edited by hand. `TextOverflow.Ellipsis` now appears
nowhere in `app/src/main`.)

## The four places a cap survives, and why

A cap that clips is still not wrapping, so each remaining one is declared, with its reason, in
`tools/ci/preflight.py`:

| Where | Cap | Why it is not truncation |
|---|---|---|
| `MediaThumbnail` duration chip | 1 | The content is a clock (`1:02:45`) and can never need a second line |
| `CollapsingTitle` | 1 | A fixed string resource in a header whose height is animated, so it cannot grow |
| `SettingsScreen` diagnostics note | 6 | `minLines`/`maxLines` on a text **input**, which scrolls rather than truncating |
| `FullPlayer` description | 4 collapsed | Collapses behind an explicit "Show more"; **clips** rather than dotting |

The description is the only judgement call: it still hides most of a long show-notes block until
you tap, but the overflow is `TextOverflow.Clip`, so nothing ends in dots and the affordance that
says there is more is a labelled control rather than a punctuation mark.

## What is NOT in scope

Thirteen string resources use `…` as UI convention — "Loading comments…", "Choose a file…",
"Checking…". Those are progress and opens-a-dialog idioms rather than truncated content, so they
stay. Say the word if you want them gone too.

## Round one of the gauntlet found three holes in the guard

All three are closed, with `tools/ci/preflight_text_test.py` pinning each:

- **A string literal could blind it.** The stripper removed comments before strings, so the MIME
  literal `"*/*"` (ImportExportScreen has two) opened a fake block comment and a `"https://…"`
  literal ate the rest of its line. Probed: a snippet holding a real cap AND a real ellipsis next
  to a `"*/*"` came back clean. Strings are now removed first.
- **The allowance was a COUNT.** Deleting a declared cap and adding an undeclared one elsewhere in
  the same file kept the count at 1 and passed. The exact cap expression is declared now — which
  also exposed that the table claimed `maxLines = 1` for the diagnostics note, whose real cap is 6.
  A declared cap that no longer exists now fails too, since a stale allowance is how the next one
  gets in.
- **It banned one spelling.** `TextOverflow.StartEllipsis` and `MiddleEllipsis` ship in this Compose
  BOM and both render dots; `import …TextOverflow.Companion.Ellipsis` makes the bare name work. All
  are caught now, and the scan covers every module rather than `app/` alone.

## Round two found the hardening's own two holes

- **The fix had re-created the bug it fixed, the other way round.** Stripping strings before
  comments means a `"""` inside a `//` comment opens a fake raw string that swallows the file to
  the next `"""` — probed, and the stripper returned the **empty string** for a file holding a real
  cap and a real ellipsis. Neither order can be right, so there is no order any more: comments,
  strings and char literals are matched in **one alternation**, and whichever starts first consumes
  the other, which is how a tokenizer does it and has no ordering question to get wrong.
- **`TextOverflow.Companion.Ellipsis` still passed**, because `\w*` cannot cross a dot — while the
  test asserting "every spelling this Compose version ships" listed four and not that one.

Round three then attacked the one-pass version with 17 probes — escaped quotes, raw strings holding
`//` and `/*`, a `//` inside a raw string inside a comment, unterminated tokens at EOF, backtick
names, a cap split across lines — and it held. The only miss was a backslash-newline inside a
non-raw string, which Kotlin does not compile. It also re-ran the corpus: across all 778 scanned
files the raw-vs-stripped difference is still exactly the four intended KDoc mentions, and no file
changes its line count, so nothing is joined or shifted.

## Guarded by preflight, not by a test

`tools/ci/preflight.py` fails on any `TextOverflow.Ellipsis` in `app/src/main`, and on any
`maxLines` outside the table above. It runs on every `git push` via the hook.

It is **not** a Compose test on purpose: a truncated `Text` still reports its *whole* string to
the semantics tree, so no assertion about text can tell a wrapped title from an ellipsised one.
A grep can. Both halves of the check were mutation-proven when written — re-adding an ellipsis in
`SourceHeader`, and a `maxLines` in `QueueScreen`, each failed it, and the file's own KDoc about
the caps that went does **not** (comments are stripped before matching, or the record of why they
went would trip the check on them).
