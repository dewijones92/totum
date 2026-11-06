package com.dewijones92.totum.video

import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.innertube.player.PlayerDetails
import com.dewijones92.totum.innertube.player.StreamingData

internal fun contentKindOf(details: PlayerDetails?, streaming: StreamingData): MediaContentKind {
    val liveNow = details?.isLive == true && (streaming.liveManifestUrl != null || streaming.hasLiveSegments)
    return when {
        liveNow -> MediaContentKind.LIVE
        details?.isLive == true -> MediaContentKind.WAS_LIVE
        else -> MediaContentKind.STANDARD
    }
}
