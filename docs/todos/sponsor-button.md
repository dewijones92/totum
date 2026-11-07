---
title: A ❤️ Sponsor button
kind: todo
status: built 2026-11-07, Batch A (tests written 2026-11-07; device checks owed)
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

## Tests

Exist (JVM): `SponsorLinkTest` (an empty value, a non-https link or a bare host hides the row; an https link is used as given).

Instrumented, compiled and not run: `SponsorRowTest` (no row for an empty or non-https value; the title and summary shown for
an https one).

Still owed, with the reason: the tap opening the page (`rememberLinkOpener` launches a browser, which a Compose test cannot
observe without a device).
