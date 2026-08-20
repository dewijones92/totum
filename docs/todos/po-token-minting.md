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
| WEB + visitorData | `visitorData` | `streamer_context.po_token` | 0KB † | 0KB † |
| ANDROID | `videoId` | `po_token` **+ `client_info` (ANDROID)** | 956KB | 956KB |
| WEB + visitorData | `visitorData` | `po_token` **+ `client_info` (WEB)** | 0KB † | 0KB † |

† **Void.** Both WEB rows were HTTP 403 caused by an undeciphered `n` on our own endpoint URL, not by
anything about the token. Re-run them once the WEB path can actually reach a server.

The last two add `streamer_context.client_info` (field 19.1), read from
`LuanRT/googlevideo`'s `streamer_context.proto` rather than guessed. It changes nothing on either
endpoint, so candidate 1 below is now **ruled out** as well.

The visitorData-on-ANDROID row is **not** evidence about the binding: nothing sent a visitorData with
that request, so the token was bound to a session YouTube never associated with it. It is listed
because it was run, not because it means anything.

## Where this actually stands

Six combinations measured. **Not one moved the number.** Two separate things are going on and they
should not be conflated again:

- **The ANDROID endpoint serves 956KB whatever we send it.** Token or no token, either binding,
  in the request or on the URL, with or without `client_info`. Six of the rows above say the same
  thing, which is strong evidence that *whatever* is capping this is not the thing we are varying.
- **The WEB endpoint served nothing because of OUR bug, now found and fixed.** Instrumenting the
  probe to print the HTTP status and the endpoint's own query turned "0KB served" into
  `has n=true has pot=false` and **HTTP 403, zero-byte body**. `withSolvedN` walked `formats` and
  left `serverAbrStreamingUrl` exactly as it arrived — harmless on ANDROID, whose URLs carry no `n`,
  and fatal on WEB, whose endpoint does. So **every WEB row above is void**: those requests never
  reached a server willing to look at a token, and the token was never what was being measured.
  Fixed, with `TheSabrEndpointNeedsItsNSolvedTest`.

  Worth naming the instrument failure too, because it cost hours: the probe's transport threw the
  status code away, and a zero-byte body with an unchecked status is indistinguishable from a stream
  that served nothing. Two different situations, one reading.

**The leading hypothesis is now that attestation is per-client.** NewPipe returns `null` from
`getAndroidClientPoToken` outright — it does not attest the Android client at all, and every token
minted here is minted as WEB. If that is right, an ANDROID SABR endpoint cannot be unlocked by a
WEB-minted token no matter where the token is placed, and the ANDROID rows were never going to move.
Testing it means either attesting AS the Android client (a different handshake, and no reference here
does it) or getting the WEB path to serve at all.

**Do not re-run the rows in the table.** They are done, and the point of writing them down is that
the next attempt starts from row seven.

## The honest summary for anyone picking this up

Minting works and is proven. The request can carry everything the schema allows. Neither has yet
produced a single extra byte of media. The wall has not been lifted, and calling the fallback to
extraction a workaround is correct — it is what makes the app usable, not what makes SABR work.
