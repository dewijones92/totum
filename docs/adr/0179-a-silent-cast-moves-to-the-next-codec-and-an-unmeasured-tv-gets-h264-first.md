---
title: A TV that goes quiet is moved to the next codec, an unmeasured model gets H.264 first, and the receiver's own words are logged
kind: adr
status: accepted
updated: 2026-11-20
---

# 179. A TV that goes quiet is moved to the next codec, an unmeasured model gets H.264 first, and the receiver's own words are logged

- Status: Accepted (nothing here has been run against a real generic Chromecast)
- Date: 2026-11-20
- Amends: [ADR 68](0068-cast-a-stream-the-phone-serves.md) (unknown devices are no longer VP9 first),
  [ADR 122](0122-a-cast-sends-the-best-rung-and-steps-down-itself.md) (the codec order, and a second way to step down)

## Context

Dewi's reports of 14 to 17 November (v0.1.927):

- **Video casts to "Kitchen TV" and "Living Room TV" go silent.** Both are generic Chromecasts (model string
  "Chromecast"); audio-only casts to them work. The TV fetches `manifest.mpd`, then the VP9 WebM video's init and index,
  then never asks for anything more: no `audio/init.mp4`, no segment, and no media status. `CastScreen.of` knows only the
  Chromecast Ultra and the LG; any other model was "VP9 + H.264, 1080p, assumed", and `CastLadder` puts VP9 above H.264
  at equal height, so the TV was sent VP9 and, being a plain Chromecast, did not decode it. Nothing in the logs said why:
  `RelayLog` wrote a line only when a request finished, so a hung request left no trace, and the receiver's own status and
  error were never read.
- **A cast that "did nothing"** (report 20261114T200559): a Play Services Dynamite module failure left a selected route with
  no session callback at all, and `CastContext.getSharedInstance` failing in the playback service was swallowed by an
  `getOrNull() ?: return`.
- **A false "can't play" toast.** `NoTracksWatch` told the user a Spinal Tap MKV served by the Pi (`/fetch/files?id=`) had
  its track list at the end, while the file was playing (the position went from 5.2 s to 7.7 s). The controller's
  `currentTracks` was empty; the file was fine.
- **DNS flapping made a download 29 s slow.** The signed-in TV client failed with `UnknownHostException`, the failure was
  swallowed (`runCatching { … }.getOrNull()`), and yt-dlp then ran through every client, each failing in turn. The log then
  said "genuinely unavailable (private, removed, geo-blocked or members-only)" about a video that was perfectly fine.

## Decision

- **A model `CastScreen.of` does not recognise is sent H.264 first.** `CastScreen` gains an `order` (the codec
  preference), and `of` gives an unrecognised model `AVC, HEVC, VP9, AV1`. `CastScreen.preference` reads it, so
  `CastLadder`, `CastRungs` and the phone's own ladder (`CastAwareCodecSupport`) all agree. The order only decides ties at
  equal height; a taller VP9 still beats a shorter H.264 (an unmeasured screen is capped at 1080p, so in practice the tie
  is the usual case). The LG, the Chromecast Ultra and a PC screen (which reports its own codecs, `known = false` but not
  "unrecognised") keep VP9 or AV1 first.
- **A start watchdog.** `TvStartWatch` arms when the TV is answered its first request for the video, and is satisfied by
  any request for sound (`audio/…`); if eight seconds pass with none, the TV has taken the picture and gone quiet. For a
  ladder rung whose codec has an alternative offered, `CastStreamServer.stepDownCodec` sets that codec aside for the model,
  picks the next best rung that is not that codec, and asks the service to re-send the item at its position through the
  same path as the stall step-down (`CastStepDownWatch.tvDidNotStart`, which checks the TV is still on that item). The
  strip says "📺 <TV> didn't start VP9, trying H.264" (`CastCodecStep`, carried in the session extras next to
  `castDroppedFrom`). At most twice per item; never when no other codec is offered or every codec the model is thought to
  take has been tried (so two refusals cannot ping-pong); never where nothing can re-send (the Pi).
- **The refusal is remembered per model, but only once it is believed.** `CodecRefusals` holds a refusal as *tentative*
  for the rest of the cast; it becomes *confirmed*, and is saved (`PrefsRefusalStore`), only when the TV then asks for
  sound from the session sent with the other codec. A slow Wi-Fi that tripped the watchdog once therefore does not take a
  codec away from a model for ever. Ending the cast drops the tentative ones. `CastScreen.without` applies the
  refusals and never leaves a screen with no codec.
- **The receiver's own words are logged.** `CastReceiverWatch` registers a `RemoteMediaClient.Callback` for each Cast
  session and `ReceiverLog` writes the player state and idle reason when they change (an idle with reason ERROR is a
  warning), every `MediaError` (type, detailed code, reason, request id, custom data) and the first requests sent to the
  receiver. The load itself is made inside Media3's `RemoteCastPlayer`, whose `PendingResult` is not exposed, so the load's
  outcome is read from the status changes (`loading`, then `idle because of an ERROR`) and `MediaError`, not from a
  result callback.
- **A request that begins is logged, and one that never ends is visible.** `RelayLog` says "request begun #n" for the
  first three requests, and `CastStreamServer` writes "N request(s) still in flight, the oldest for Xms" every 10 s while any
  video relay request is open (and when the start watch fires), so a hung relay is no longer silent.
- **A route selected with no session callback in five seconds is reported.** `SessionStartWatch` is armed by
  `MediaRouter.Callback.onRouteSelected` for a Cast route and cleared by any `SessionManagerListener` callback. When it
  fires, `CastTarget` logs the cast state and the Play Services version (`PlayServices.describe`) and shows
  "⚠️ Cast didn't start on <TV>" with a Retry that selects the route again. A failing `CastContext.getSharedInstance` in
  the playback service, the app and the Cast button now logs its cause and the Play Services version.
- **`NoTracksWatch` does not speak while the file is playing.** At the recheck it compares the position with the one at
  the first suspicion; if the controller says `isPlaying` and the position moved by 500 ms or more, nothing is said. It logs
  `isPlaying`, `playWhenReady`, the position, the track count, whether `COMMAND_GET_TRACKS` is available and the available
  commands as the controller sees them, and the service's player logs its own track list on every change
  (`service tracks on …`), so the next report shows whether the controller or the player was wrong.
- **A DNS failure waits for the network; it is not a verdict on the video.** `InnerTubeResponse.Failure` and
  `PlayerResult.Failure` carry `unreachable` (an `UnknownHostException`, `NoRouteToHostException` or `ConnectException`
  anywhere in the cause chain), `InnerTubePlayerStreams.signedInFor` rethrows it as `NetworkUnreachable`, and
  `VideoResolver` then waits (up to 30 s, `awaitOnline`), pauses 2 s for DNS to settle and asks the account once more. If it
  is still unreachable the resolve ends there: yt-dlp is not run through every client. A resolve begun while the phone is
  offline waits the same way before asking anyone. A yt-dlp `Failure.Network` is logged as "the network failed during
  extraction … which says nothing about the video", and "genuinely unavailable" is kept for failures that were not the network.

## Consequences

- A plain Chromecast of a model nobody has measured plays H.264 first. If it was in fact able to decode VP9 there is no
  loss at 1080p. HEVC, which is not in an unmeasured model's codecs, is unaffected.
- A watchdog that trips for a reason other than the codec (a TV that is simply asleep) costs one re-send with the next codec
  and a strip line; it is not remembered unless that second codec then plays.
- The 8 s wait, the 10 s heartbeat, the 5 s route wait, the 30 s network wait and the two-step cap are guesses fixed by
  this ADR; nothing has measured them on a real generic Chromecast.
- A cast to the Pi's targets is not watched (the Pi has no player to re-send with): `onTvNotStarting` is null there and
  the start watch is not armed.
- The controller-side no-tracks check cannot tell a file with no tracks that plays silently from a file the controller
  merely cannot see tracks for; both are now treated as playing. The service-side track log is there to tell them apart.

## Not verified

- A real generic Chromecast (nobody had one free, and the brief forbids casting to the real TVs): that H.264 first makes
  the two TVs play, that the watchdog's 8 s is right and that the strip line reads as intended.
- The Play Services failure itself: the retry path was written from the report and the API, not reproduced.
- The receiver log on a real receiver: the SDK constants were read from the 22.3.1 jar; the lines have not been seen from a TV.
