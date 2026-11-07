---
title: A ❤️ Sponsor button
kind: todo
status: built 2026-11-07, Batch A (tests owed); hidden until Dewi sets the link
batch: A (2026-11)
priority: low
updated: 2026-11-07
---

# A ❤️ Sponsor button

Dewi 2026-11-05: "sponsor up to you" -> GitHub Sponsors (FUNDING.yml and an in-app link), Ko-fi until Sponsors is approved. Dewi signs up himself (bank details).

## Built 2026-11-07 ([ADR 147](../adr/0147-the-sponsor-link-is-one-build-value-and-hidden-when-empty.md))

A Settings row, drawn only when `BuildConfig.SPONSOR_URL` (`totum.sponsorUrl` in `gradle.properties`) is a usable
https link; no URL is invented. Needs Dewi: the Ko-fi or GitHub Sponsors page address, and `.github/FUNDING.yml` once
the account exists. See [sponsor-button](../features/sponsor-button.md).

## Tests owed

A Compose test of the row (absent with an empty value, present with one, the tap).
