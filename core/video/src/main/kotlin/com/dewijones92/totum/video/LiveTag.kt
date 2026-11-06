package com.dewijones92.totum.video

import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.canBeLive
import java.time.Instant

sealed interface LiveTag {
    data object Live : LiveTag

    data class StartsSoon(val startsAt: Instant?) : LiveTag

    data object WasLive : LiveTag
}

fun liveTagOf(kind: MediaContentKind, state: LiveState?): LiveTag? {
    if (!kind.canBeLive) return null
    return when (state?.phase) {
        LivePhase.UPCOMING -> LiveTag.StartsSoon(state.startsAt)
        LivePhase.LIVE -> LiveTag.Live
        LivePhase.ENDED -> LiveTag.WasLive
        null -> when (kind) {
            MediaContentKind.LIVE -> LiveTag.Live
            MediaContentKind.WAS_LIVE -> LiveTag.WasLive
            else -> null
        }
    }
}
