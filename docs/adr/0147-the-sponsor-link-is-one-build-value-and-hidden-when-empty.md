---
title: The sponsor link is one build value, and the button is hidden while it is empty
kind: adr
status: accepted
updated: 2026-11-07
---

# 147. The sponsor link is one build value, and the button is hidden while it is empty

- Status: Accepted
- Date: 2026-11-07

## Context

Dewi, 2026-11-05: "sponsor up to you". The choice was GitHub Sponsors, with Ko-fi until Sponsors is approved, and Dewi
signs up himself (it needs bank details). The page addresses are his to supply. A button that links to a placeholder,
or to somebody else's page, would be worse than none.

## Decision

- One value, `BuildConfig.SPONSOR_URL`, from the Gradle property `totum.sponsorUrl` (`gradle.properties` carries it
  empty) or the `TOTUM_SPONSOR_URL` environment variable (when the property is blank), read in `app/build.gradle.kts`
  beside `HOME_SERVER`. Changing the link changes one line and nothing else.
- `SponsorLink.of` (`:lib:common`) accepts it only as a plain `https://` link with a host and no user-info, so a typo
  or a hostile value hides the button instead of opening something else.
- Settings shows **❤️ Sponsor Totum** at the end of the page only while the value is usable; it opens the page in the
  browser. Empty or unusable, the row is not drawn and one `sponsor` log line says why, with how many characters were
  configured (never the value of anything secret: the link is public).
- The row is not in the Settings search registry, as the registry's own rule allows for a row that is not always on
  screen ([settings-search](../features/settings-search.md)).
- `.github/FUNDING.yml` is not created yet. It needs Dewi's account names and Sponsors rejects a file naming an
  account that is not enrolled; the page lists what to add.

The choice of GitHub Sponsors with Ko-fi meanwhile is Dewi's; the build value, the validation, the placement and the
hidden-while-empty rule are the builder's calls.

## Consequences

- Nothing about sponsoring is visible in a build until Dewi sets the value; no URL is invented anywhere.
- The link is in the public repo's build configuration once set, which is fine: it is a public page.

## Tests

`SponsorLinkTest`.

## Tests still to write

A Compose test that the row is absent with an empty value and present, tapping to open the link, with one set.
