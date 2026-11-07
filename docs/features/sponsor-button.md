---
title: A ❤️ Sponsor button
kind: feature
area: publishing
status: built 2026-11-07, Batch A (tests owed); hidden until Dewi sets the link
updated: 2026-11-07
---

# ❤️ Sponsor Totum

Decision: [ADR 147](../adr/0147-the-sponsor-link-is-one-build-value-and-hidden-when-empty.md). Backlog item:
[todos/sponsor-button](../todos/sponsor-button.md).

## What a person sees

At the end of Settings, a row **❤️ Sponsor Totum** ("Totum is free and open source. This opens the sponsor page in
your browser."). Tapping it opens the sponsor page in the browser. With no usable link in the build the row is not
there at all, and nothing says why on screen.

## Setting the link (Dewi)

One value: `totum.sponsorUrl` in `gradle.properties` (an empty line is already there), for example
`totum.sponsorUrl=https://ko-fi.com/<your-name>` now, and the GitHub Sponsors page once it is approved. CI release
builds read `gradle.properties`, so committing the line is enough; the environment variable `TOTUM_SPONSOR_URL` works
too when the property is blank. It must be an `https://` link with no `user@` part, or the button stays hidden.

For the repository's own Sponsor button, add `.github/FUNDING.yml` after the account exists, with the line
`github: <your-github-username>` once Sponsors is approved and `ko_fi: <your-ko-fi-name>` for Ko-fi. Nothing was added
here because the account names are not known and Sponsors rejects a file naming an account that is not enrolled.

## Diagnostics

`sponsor` lines: `sponsor button shown for <host>` or `sponsor button hidden: no usable https link in this build (N
chars configured)` each time Settings opens, and `sponsor page opened (<host>)` on a tap.

## Tests owed

A Compose test of the row (absent with an empty value, present with one, tap opens it). `SponsorLinkTest` covers the
validation.
