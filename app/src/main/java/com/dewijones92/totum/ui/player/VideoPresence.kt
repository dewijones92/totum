package com.dewijones92.totum.ui.player

import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.playback.PlaybackState

/**
 * Whether there is a picture to be fullscreen ABOUT — the one question the player has to keep
 * answering while the queue moves from item to item.
 *
 * It is genuinely hard, because "no video track" is what the player reports for three different
 * situations and only one of them means "this is an audio item":
 *
 * | Situation | `hasVideo` | `isBuffering` | `isPlaying` |
 * |---|---|---|---|
 * | A video, playing | true | false | true |
 * | The gap between items — a yt-dlp resolve, 3-11s | false | true | false |
 * | **A stream that failed and is being re-resolved** | false | **false** | **false** |
 * | A podcast, playing | false | false | true |
 *
 * The third row is the bug. `!hasVideo && !isBuffering` was taken to mean "settled on audio",
 * and an expired stream on an auto-advance produces exactly that: report 0.1.374 has the item
 * ending at 18:52:31, the next one failing `ERROR_CODE_IO_BAD_HTTP_STATUS … Expired` at
 * 18:52:35.6, the player going idle — and fullscreen dropping 1.1 seconds later, while the
 * re-resolve that would have brought the picture back was still running. It succeeded at
 * 18:52:39 and played on, windowed.
 *
 * So [WAITING] means only that nothing has settled yet, [SETTLED_ON_AUDIO] means the player is
 * demonstrably playing sound with no picture, and the pair are asymmetric on purpose: staying in
 * fullscreen a second too long is a black frame that fixes itself, while leaving wrongly is a
 * rotation, a resize, and losing your place in what you were watching.
 */
internal enum class VideoPresence {
    /** A video track is there, whatever else is going on. */
    SHOWING_VIDEO,

    /** No video track yet, and no evidence there will not be one. Fullscreen holds. */
    WAITING,

    /** No picture, and the player is demonstrably getting on with the sound. Fullscreen ends. */
    SETTLED_ON_AUDIO,
    ;

    /** The one line a report needs to re-judge this decision months later. */
    fun describe(state: PlaybackState): String =
        "$name (hasVideo=${state.hasVideo} buffering=${state.isBuffering} " +
            "playing=${state.isPlaying} kind=${state.kind} item=${state.itemId.value})"
}

/**
 * A podcast can never grow a picture, so it settles even while paused; anything else has to be
 * seen playing. That asymmetry is what keeps a stumbling video in fullscreen while still letting
 * a paused episode out of it.
 */
internal fun PlaybackState.videoPresence(): VideoPresence = when {
    hasVideo -> VideoPresence.SHOWING_VIDEO
    isBuffering -> VideoPresence.WAITING
    kind == MediaKind.PODCAST -> VideoPresence.SETTLED_ON_AUDIO
    isPlaying -> VideoPresence.SETTLED_ON_AUDIO
    else -> VideoPresence.WAITING
}
