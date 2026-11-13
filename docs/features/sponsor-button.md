---
title: A ❤️ Sponsor button
kind: feature
area: publishing
status: live 2026-11-13, Batch B (the link is set to https://ko-fi.com/totum)
updated: 2026-11-13
---

# ❤️ Sponsor Totum

Decision: [ADR 147](../adr/0147-the-sponsor-link-is-one-build-value-and-hidden-when-empty.md). Backlog item:
[todos/sponsor-button](../todos/sponsor-button.md).

## What a person sees

At the end of Settings, a row **❤️ Sponsor Totum** ("Totum is free and open source. This opens the sponsor page in
your browser."). Tapping it opens the sponsor page in the browser. With no usable link in the build the row is not
there at all, and nothing says why on screen (the build now carries the link, so it is drawn).

## Setting the link (Dewi)

One value: `totum.sponsorUrl` in `gradle.properties`, **`https://ko-fi.com/totum` since 2026-11-13** (the GitHub
Sponsors page replaces it once that is approved). CI release builds read `gradle.properties`, so committing the line is
enough; the environment variable `TOTUM_SPONSOR_URL` works
too when the property is blank. It must be an `https://` link with no `user@` part, or the button stays hidden.

The repository's own Sponsor button comes from `.github/FUNDING.yml`, created 2026-11-13 with `ko_fi: totum`. Add a
`github: <username>` line only once GitHub Sponsors is approved: it rejects a file naming an account that is not enrolled.

## Diagnostics

`sponsor` lines: `sponsor button shown for <host>` or `sponsor button hidden: no usable https link in this build (N
chars configured)` each time Settings opens, and `sponsor page opened (<host>)` on a tap.

## Tests

`SponsorLinkTest` covers the validation; the instrumented `SponsorRowTest` (compiled, not run) covers the row absent with an
empty or non-https value and present with an https one. Owed: the tap opening the page, which needs a device.
