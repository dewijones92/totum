---
title: The signed-in TV client's /player is refused
status: blocked-upstream
updated: 2026-08-18
---

# The signed-in TV client's `/player` is refused

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

## Where to look next

Not more permutations of the above; that seam is exhausted. Candidates, cheapest first:

1. **Account state.** This Google account may never have been used on a real TV, or needs to accept
   something. Try a second account, or sign in on an actual TV/Cobalt device once.
2. **Scope breadth.** SmartTube requests more than one scope (`youtube.force-ssl`,
   `youtube-paid-content`, and historically `gdata.youtube.com`). We request one. Cheap to widen in
   `YouTubeTvClient.SCOPE` — but note it invalidates existing tokens, so everyone re-logs in.
3. **Read SmartTube's live behaviour rather than its source.** Its repo search was unavailable; the
   decisive evidence would be a packet capture of a working TV `/player` to diff against ours.

## Meanwhile

`preferAccount` asks the account first and **falls through to anonymous** when the account path fails,
so this costs one wasted request per resolve and breaks nothing. The gap is quality and
age-restriction, not playback.
