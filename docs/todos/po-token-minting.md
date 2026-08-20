---
title: PO token minting works; it has not yet lifted the ceiling
status: open — minting proven, four placements measured, none lifted the wall
updated: 2026-08-20
---

# Minting a PO token: proven. Lifting the ceiling with it: not yet.

## What works, verified 2026-08-20

The whole attestation handshake, end to end, from this laptop:

```
POST https://www.youtube.com/api/jnn/v1/Create        ["O43z0dpjhgX20SCx4KAo"]
  -> HTTP 200, 106KB
descramble: base64-decode, then add 97 to every byte      -> JSON
  messageId=bfkj  globalName=trayride  program=10211  interpreter=62903 chars
run the interpreter, snapshot the VM                     -> botguardResponse, 989 chars
POST .../GenerateIT   ["O43z0dpjhgX20SCx4KAo", <response>]
  -> integrity token, 80 chars, ttl 43200s (12 hours)
webPoSignalOutput[0](integrityToken)(identifier)          -> Uint8Array
  -> PO token, 116-160 chars depending on the identifier
```

`tools/potoken/mint.mjs` does this and prints a token. The API key is
`AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw` and the endpoint is on `www.youtube.com`, **not**
`jnn-pa.googleapis.com` — a first attempt against the latter with a different key returned
`API_KEY_INVALID`, which is a useful negative to not repeat.

BotGuard needs browser globals, so this runs in a real browser. QuickJS solving `n` is no evidence it
can run this.

## What the app can now do

- `VideoPlaybackAbrRequest` carries a token as `streamer_context.po_token` (field 19, subfield 2), and
  sends **nothing** when there is none — an empty context is a change to the request that currently
  works, and `sabr.malformed_config` is indistinguishable from the wall.
- `SabrSession` carries the token for a whole conversation, because it is checked against the session
  the endpoint was issued to; `SabrStream` puts it in every request.
- `InnerTubeClient.playerAsWeb` makes a WEB player request. It needs **four** things together or
  answers `UNPLAYABLE: Video unavailable` — each found by removing it: `visitorData`, a
  `signatureTimestamp`, a videoId-bound token in `serviceIntegrityDimensions`, and the WEB client
  version. visitorData alone is not enough, nor is it with the timestamp.
- `DoesAPoTokenLiftTheCeilingTest` measures the ceiling with and without a token in one run, from two
  separate sessions so the first cannot spend the trial window the second is measuring. It skips
  unless `-DpoToken` is supplied, so it never runs in CI.

## What has been measured, and did NOT lift the ceiling

All against `uSMGENDH_QI`, both arms in one run:

| endpoint | binding | where the token went | without | with |
|---|---|---|---|---|
| ANDROID | `videoId` | `streamer_context.po_token` | 956KB | 956KB |
| ANDROID | `visitorData` | `streamer_context.po_token` | 956KB | 956KB |
| ANDROID | `videoId` | `pot=` on the URL | 956KB | 956KB |
| WEB + visitorData | `visitorData` | `streamer_context.po_token` | 0KB | 0KB |

The visitorData-on-ANDROID row is **not** evidence about the binding: nothing sent a visitorData with
that request, so the token was bound to a session YouTube never associated with it. It is listed
because it was run, not because it means anything.

## The two candidates left, in order

1. **`streamer_context.client_info` (19.1) is absent.** LuanRT's SABR client throws without it. The
   WEB endpoint answering 0KB in BOTH arms is what a rejected request looks like, and it is the one
   row where the request shape changed. Add 19.1 before anything else.
2. **A WEB-minted token may be worthless to an ANDROID session.** NewPipe returns `null` from
   `getAndroidClientPoToken` outright — it does not attest the Android client at all. If attestation
   is per-client, an ANDROID SABR endpoint needs an ANDROID-attested token, which is a different
   handshake rather than a different binding. This would explain every ANDROID row above.

Do not re-run the four rows in the table. They are done.
