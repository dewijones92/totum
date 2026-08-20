---
title: The signed-in TV /player answers UNPLAYABLE, so the age-restricted fallback is dead
status: open
severity: high
updated: 2026-08-20
---

# The signed-in TV `/player` answers UNPLAYABLE

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
