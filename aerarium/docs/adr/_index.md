---
title: Aerarium architecture decision records
kind: index
updated: 2026-10-31
---

# Architecture decision records

One file per decision: `NNNN-short-title.md` with status, date, context, decision and consequences. Create,
update or supersede the relevant ADR in the same change as the code; a superseded ADR keeps its file with
`status: superseded by NNNN`.

| #   | Decision                                                                                                    | Status   |
| --- | ----------------------------------------------------------------------------------------------------------- | -------- |
| 1   | [A second app in the Totum repo, fully separate](0001-a-second-app-in-the-totum-repo.md)                    | Accepted |
| 2   | [Read-only access everywhere](0002-read-only-everywhere.md)                                                 | Accepted |
| 3   | [Stack: TypeScript like per-chordas, SQLite built into Node](0003-stack.md)                                 | Accepted |
| 4   | [No personal data in the public repo, enforced by a guard](0004-no-personal-data-in-the-public-repo.md)     | Accepted |
| 5   | [Owner only, behind the Google gate](0005-owner-only-behind-the-gate.md)                                    | Accepted |
| 6   | [A health dashboard that shouts](0006-a-health-dashboard-that-shouts.md)                                    | Accepted |
| 7   | [Tokens encrypted in the database, keys kept out of backups](0007-secrets-encrypted-key-outside-backups.md) | Accepted |
| 8   | [A weekly money report instead of a monthly digest](0008-a-weekly-money-report.md)                          | Accepted |
| 9   | [A narrow mail importer for documents that arrive nowhere else](0009-a-narrow-mail-importer.md)             | Accepted |
| 10  | [Open Banking through Enable Banking, own accounts only](0010-open-banking-through-enable-banking.md)       | Accepted |
