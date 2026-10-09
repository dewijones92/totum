package com.dewijones92.totum.playback

import com.dewijones92.totum.domain.MediaContentKind

/** What the person chose for listening: kept for talk, set aside for music. */
internal data class PlaybackChoices(val speed: Float, val skipSilence: Boolean)

/** How one item plays. [keepsChanges] false means a change made while it plays is for it alone. */
internal data class ItemTuning(val speed: Float, val skipSilence: Boolean, val keepsChanges: Boolean)

internal fun tuningFor(kind: MediaContentKind, choices: PlaybackChoices): ItemTuning = when (kind) {
    MediaContentKind.MUSIC -> ItemTuning(speed = 1f, skipSilence = false, keepsChanges = false)
    MediaContentKind.STANDARD, MediaContentKind.LIVE, MediaContentKind.SHORT ->
        ItemTuning(choices.speed, choices.skipSilence, keepsChanges = true)
}
