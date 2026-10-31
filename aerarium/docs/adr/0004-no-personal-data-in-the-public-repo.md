---
title: No personal data in the public repo, enforced by a guard
status: accepted
date: 2026-10-31
updated: 2026-10-31
---

# 0004 · No personal data in the public repo

## Context

The Totum repo is public. Dewi chose to keep the code there but keep data, credentials and even the list of
providers he uses out of git.

## Decision

- Banks are chosen at runtime from the aggregator's list; manual accounts are typed into the app. The code
  names only the connectors it implements (Enable Banking, Monzo, Trading 212).
- Tests and e2e use synthetic data only.
- `tools/privacy-guard.mjs` fails on sort codes, IBANs, Luhn-valid card numbers, UK mobile numbers, number
  plates, non-example email addresses, and any word in a denylist kept outside the repo
  (`~/.local/share/finance-hub/denylist.txt`, or `AERARIUM_DENYLIST`). It runs in three modes: pre-commit scans exactly what
  is staged (any path in the repo); pre-push scans the added lines and commit messages of every pushed range; CI and
  `pnpm privacy` scan the whole `aerarium/` tree. Lockfiles and binaries are skipped in diffs. CI has no denylist,
  so there it checks patterns only. If the guard itself crashes, the hook warns and lets the commit through, so it
  can never wedge Totum work; only a finding blocks.
- When it reports a denylisted word it names the denylist line, not the word, so CI logs never echo it.

## Consequences

A provider-specific bug is described generically in code and docs ("a bank that pages transactions
backwards"), never by name.
