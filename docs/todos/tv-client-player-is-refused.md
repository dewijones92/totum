---
title: The signed-in TV client's /player is refused
status: fixed 2026-09-06 — the TV client wants the signature timestamp on the TV scale (20697001, not 20697)
updated: 2026-09-06
---

# The signed-in TV client's `/player` is refused

> ✅ **FIXED 2026-09-06 — and the diagnosis below was wrong.** It was never attestation. Since about
> 2026-08-18 a TV client must declare `signatureTimestamp` with a `001` suffix — `20697001` where every
> player script (web *and* `tv-player-ias.js`) still says `20697` — and the web-scale number is answered
> `UNPLAYABLE "The page needs to be reloaded"`. Found by installing SmartTube on `totum-api35`, decrypting
> its traffic with mitmproxy (system CA, `tools/emulator/mitm-capture.sh`), and varying **one field at a
> time** against its captured request as the control:
>
> | variant (one axis from SmartTube's captured request) | answer |
> |---|---|
> | SmartTube verbatim (control) | OK, 22 formats, SABR, tracking URLs |
> | Totum verbatim (old version, 2 headers) | UNPLAYABLE "The page needs to be reloaded" |
> | Totum body with **`signatureTimestamp: 20697001`** and nothing else changed | **OK, 26 formats, tracking URLs** |
> | SmartTube body with `signatureTimestamp: 20697` and nothing else changed | UNPLAYABLE |
> | SmartTube minus visitorData + `X-Goog-Visitor-Id` / minus cpn / minus User-Agent / minus Referer / minus `X-Youtube-Client-*` / minus client UA fields / with our 2024 client version | all still OK |
> | `signatureTimestamp` ∈ {20697000, 20697002, 20698001, 19000001, 99999999} | OK — any number on the TV scale |
> | `signatureTimestamp` ∈ {20696, 20697, 20698, 100000} | UNPLAYABLE — any number on the web scale |
>
> SmartTube's own source says so (`QueryBuilder.kt`: *"Web and TV timestamps now differs. TV one should
> have 001 suffix … wrong timestamp format yield 'page should be reloaded' error"*). The table below
> did try `signatureTimestamp` — one value, 20677, on the wrong scale — and ruled it out; a field that
> is *present and wrong* looks exactly like a field that does not matter. Fix: `SignatureTimestamp`
> value class (`web` / `tv`), taken by type on every TV player call (`PlayerTimestampScaleTest`).
> Proven on device: four held outbox rows drained and appeared at the top of the account's history.
> The `browse`-works-`player`-fails narrowing below was real but pointed the wrong way: `browse`
> sends no timestamp, so it could never have caught this.

`TVHTML5` returns **`UNPLAYABLE — "The page needs to be reloaded."`** for every request we can
construct, with a valid signed-in token. This blocks two things:

* **The SmartTube-equivalent quality route.** SmartTube is a signed-in TV client playing over SABR;
  the TV response is where its 4K60 comes from. Without it we cap at SABR's 1080p30.
* **Age-restricted videos.** `playerDowngradedTv` exists for exactly this and cannot work today.

The code comments claim this worked on 2026-07-31/08-01 ("27 formats, none with a url"). It does not
now. **Treat those comments as dated, not current.**

## What has been ruled out (2026-08-18)

Recorded so nobody repeats it — this is over twenty live probes.

| Variable | Values tried | Result |
|---|---|---|
| Client version | `7.20240401.10.00` (ours), `7.20260114.12.00` (**yt-dlp's current `tv`**), `5.20260707` (ours), `5.20260114` (**yt-dlp's `tv_downgraded`**), `7.20260805.10.00`, `7.20260701.13.00`, `7.20250219.16.00` | all identical |
| User-Agent | short `Cobalt/Version`, full `Cobalt/25.lts.30.1034943` | no change |
| `visitorData` | absent, present in context, plus `X-Goog-Visitor-Id` | no change |
| Headers | `Origin`, `Referer: /tv`, `X-Goog-AuthUser` | no change |
| `playbackContext` | present with sts, present without sts, omitted entirely | no change |
| `signatureTimestamp` | 20677 (agreed by our own `HttpSignatureTimestampSource`) | no change |
| Video | 4K CC film **and** the 97-minute VOD we play daily | both → it is the CLIENT, not the video |
| Caller | hand-rolled request **and** our own `InnerTubeClient` | both → not our request construction |

**The token is not the problem.** `oauth2.googleapis.com/tokeninfo` says: `aud`/`azp` = SmartTube's TV
client id, `scope` = `https://www.googleapis.com/auth/youtube`, ~20 hours of life left.

**The requests are well-formed.** Adding `params: "8AEB"` changed the answer to
`ERROR — "This video is unavailable"`, so YouTube is genuinely evaluating them rather than rejecting
them as malformed.

**Anonymous is a different refusal**: `LOGIN_REQUIRED — "Sign in to confirm you're not a bot"`. So the
token IS read and does change the outcome — it moves us from one refusal to another.

## The decisive narrowing: it is `/player` ONLY

The same token, the same `TVHTML5` client, the same Cobalt user agent, two different endpoints:

| Endpoint | Response |
|---|---|
| `browse` (`browseId: FEsubscriptions`) | **1,563,757 bytes of real data** |
| `player` (`videoId`) | **2,781 bytes — the `UNPLAYABLE` refusal** |

And the app agrees on the device: the signed-in subscriptions feed loads and threads Shorts into it
(`[feed] showing 45 cached items`, then `videos=67`, `[shorts] threading 22 Short(s)`).

**So the token, the scope, the client version, the user agent and the headers are all ACCEPTED by
InnerTube.** They cannot be the cause, because `browse` succeeds with every one of them identical. The
refusal is specific to `/player`.

That is the same shape as the rest of this document: `/player` is the endpoint that hands out stream
URLs, and it is the one YouTube now gates behind attestation. A TV client without a PO token gets a
`browse` that works and a `/player` that refuses. This is very probably not a bug in our TV client at
all — it is the attestation wall arriving by a second route.

## Where to look next

The config seam is exhausted, and so is the auth seam — `browse` proves both. Do NOT spend more time on
client versions, user agents, scopes or account state; those are ruled out by the table above.

What is left:

1. **A PO token / attestation for `/player`.** The real fix, and the same blocker as the main
   attestation todo. Everything else here is a workaround.
2. **A packet capture of SmartTube playing a video**, to see what its `/player` carries that ours does
   not. That is the only remaining source of ground truth, since its repo search was unavailable and
   its source-level constants (taken from the yt-dlp wheel) are already matched exactly.
3. **Accept it and lean on SABR**, which is what the rescue ladder now does. SABR is YouTube offering
   the bytes through the door it has left open.

## Meanwhile

`preferAccount` asks the account first and **falls through to anonymous** when the account path fails,
so this costs one wasted request per resolve and breaks nothing. The gap is quality and
age-restriction, not playback.
