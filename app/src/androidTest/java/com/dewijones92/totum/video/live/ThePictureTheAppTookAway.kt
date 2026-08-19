package com.dewijones92.totum.video.live

import com.dewijones92.totum.common.Breadcrumbs

/**
 * The route line, when the app decided to play this WITHOUT the picture — the question three live
 * tests have to ask, so it is asked in one place.
 *
 * An audio-only route has no picture, no captions and no quality ladder. So a test that asked for video
 * and then measured one of those reports a defect in the thing it was measuring, when the app had
 * already decided, for its own good reasons, not to send a picture at all.
 *
 * Asks the ROUTE rather than the setting, which matters: `listen=` in the trail is
 * `forceAudio || pictureGivenUpOn || audioPreferred()`, so it goes true for THREE different reasons —
 * the recovery ladder degrading a stream YouTube refused, the picture having been given up on for this
 * item, or the mode being AUDIO (explicitly, or AUTO on a metered connection, which is what
 * `MeteredAudioSwitch` persists). A first attempt at this guard checked only the last of those and did
 * not fire in CI, because the mode genuinely was VIDEO and the ladder had degraded a refused stream.
 * The decision is the honest thing to test, and it is already recorded.
 *
 * Cost: `SubtitlesArriveAndRenderTest` read as "no subtitle track reached the player" twice
 * (2026-08-19), and `PlaysAcrossContentTypesTest` as "no picture" for three items (2026-08-18).
 *
 * Returns the whole route line, so a skip carries the evidence rather than an assertion of it.
 */
internal fun audioOnlyRouteTaken(): String? =
    Breadcrumbs.snapshot()
        .lastOrNull { it.message.startsWith("route ") }
        ?.message
        ?.takeIf { "listen=true" in it }
