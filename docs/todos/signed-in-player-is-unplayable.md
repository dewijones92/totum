---
title: The signed-in TV /player answers UNPLAYABLE, so the age-restricted fallback is dead
status: fixed 2026-09-06 — it WAS the stamp: TV clients need 20697001, the scripts say 20697
severity: high
updated: 2026-09-06
---

# The signed-in TV `/player` answers UNPLAYABLE

> ✅ **FIXED 2026-09-06.** "The next experiment" below was the right one and the stamp was the cause: a
> TV client must send the signature timestamp with a `001` suffix (`20697001`), and the plain script value
> that works for WEB is refused. One-axis measurement, SmartTube capture and fix are in
> [tv-client-player-is-refused.md](tv-client-player-is-refused.md). The **"NOT OUR BUG"** section below
> was wrong in its conclusion — yt-dlp's `tv` client fails for the same reason (it sends the web scale),
> which made two tools agree on a shared mistake rather than confirm a wall. Age-restricted fallback
> re-measured the same day: downgraded TV + TV-scale stamp → `rwcfPqbAx-0` OK, 7 formats, 7 plain URLs.

Measured on `totum-api35`, signed in, 2026-08-20 17:30, one video, one axis at a time
(`SignedInVersusAnonymousPlayerTest`, video `uSMGENDH_QI` — NASA, public domain):

| arm | result |
|---|---|
| anonymous | `ok=true` 134 formats, 20 with direct URLs, offered 1080p, `sabr=true config=true playableSomehow=true`, deep fetch at 8,000,000B returned **HTTP 206 (102400B)** |
| signed-in TV | `UNPLAYABLE: The page needs to be reloaded.` |
| signed-in downgraded TV | `UNPLAYABLE: The page needs to be reloaded.` |

## What it breaks

`AppContainer.accountPlayer` (around line 963) is the **age-restricted fallback**: it runs only
when the anonymous attempt already failed, trying `playerDowngradedTv` and then `playerAsAccount`.
Both arms are the ones measured above, so on this device that fallback cannot currently succeed for
any video, and an age-restricted video has no path left. Ordinary playback is unaffected — it never
reaches this code.

## What it does NOT mean

Signing in does not cost us SABR. The anonymous response carries `serverAbrStreamingUrl` and the
ustreamer config, so the SABR path is fully available without an account, and browse is fine signed
in (the subs feed pulled 1596 subscriptions in the same run).

It is also **not** established that YouTube is refusing signed-in access on purpose.
`"The page needs to be reloaded"` is the response associated with a rejected client context or a bad
`signatureTimestamp`, and ours is the one thing in that request the anonymous call does not send.
Two consecutive runs two minutes apart read **20684** then **20681**, which is worth explaining
before anything is built on top of it.

## ✅ NOT OUR BUG — confirmed against yt-dlp (2026-08-20)

The TV client is refused for **yt-dlp too**, from this same address, with the same words:

```
$ yt-dlp --extractor-args "youtube:player_client=tv" ...
ERROR: [youtube] uSMGENDH_QI: The page needs to be reloaded.
```

And it is specific to that client. On the same video, same minute, same connection:

| client | result |
|---|---|
| `tv` | **ERROR: The page needs to be reloaded** |
| `tv_simply` | only images — SABR-only, URLs stripped |
| `web_safari` | only images — SABR-only |
| `android_vr` | real formats with URLs |
| `default` | real formats with URLs |

Our TV client identity was also a year stale (`7.20250101.10.00` against yt-dlp's
`7.20260114.12.00`) and correcting it changed nothing, so the version was not the cause either.

**So the age-restricted fallback is not broken by anything in this repo.** `AppContainer.accountPlayer`
asks correctly and the TV family is being refused generally. Nothing in our code will fix that, and the
right response is to stop treating it as a defect here: the fallback should say so plainly when it is
refused, rather than looking like our failure.

This also closes the SABR line of enquiry. SmartTube streams whole videos over SABR *as a TV client*,
and a TV client is exactly what cannot currently be reached from here — which is consistent with
everything measured in
[sabr-stops-at-one-megabyte.md](sabr-stops-at-one-megabyte.md): eighteen request variations, SmartTube's
own request replicated field for field, and a hard sixty-second ceiling that none of it moved.

## The next experiment

Two axes, one at a time, against the anonymous control that is known to work:
1. signed-in TV with `signatureTimestamp = 0` — does the stamp cause it?
2. anonymous TV *with* our stamp — does the stamp break even an unauthenticated request?

If (2) fails, the stamp is ours and wrong, and this is a one-line fix rather than a policy wall.
Do not permute auth and stamp together: this investigation has already produced two confidently
wrong theories that way.

## Instrument note

The first run of this test printed `deepFetch=no streams` and nothing else, which three different
situations produce — a stated refusal, a SABR-only response, and a transport failure. It was one
sentence away from being reported as "signing in returns no streams", when the SABR-only case would
have meant close to the opposite. The `why=` column now carries the parsed result, the format count
and the SABR availability, so the line can be re-judged from a log alone.
