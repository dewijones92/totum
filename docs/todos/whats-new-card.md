---
title: What's new after an update
kind: todo
status: built; tests written in part
batch: A (2026-11)
priority: medium
updated: 2026-11-14
---

# What's new after an update

Dewi 2026-11-06. A one-time card after an update listing the release's changes in plain words. `LatestRelease` reads
no `body` today; needs a last-shown-version pref and the INSTALLED version's notes.

Whole-batch review: built with the in-app updater on the extended `Release`.

Built 2026-11-06 with the updater (ADR 130). CI now writes a `## What's new` section of commit subjects into each release; the card needs a release built after that change. Shows the installed release only.

## Tests written 2026-11-14

`WhatsNewTest` (`whatsNewVerdict`, `whatsNewLines` with trailers, prefixes, headings and a 1,000-commit release; `GitHubLatestRelease` reading the asset, the notes and `byTag`). Still owed: the card itself.
