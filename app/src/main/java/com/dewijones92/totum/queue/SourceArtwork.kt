package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.withArtworkFrom

internal fun PlayableItem.withSourceArtwork(artwork: Map<SourceId, HttpUrl>): PlayableItem {
    if (item.thumbnailUrl != null) return this
    val withArt = item.withArtworkFrom(artwork)
    val outcome = if (withArt.thumbnailUrl != null) "using its source's" else "its source has none either"
    Diag.log("playback", "no artwork of its own for ${withArt.id.value} from ${withArt.sourceId.value}: $outcome")
    return copy(item = withArt)
}
