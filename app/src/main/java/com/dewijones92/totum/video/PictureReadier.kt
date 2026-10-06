package com.dewijones92.totum.video

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId

internal class PictureReadier(
    private val metered: () -> Boolean,
    private val prefetch: suspend (HttpUrl, SourceId) -> VideoResolver.Resolved?,
) {
    suspend fun ready(item: PlayableItem) {
        if (item.pillar != MediaKind.VIDEO) return
        val watchUrl = item.fetchUrl ?: return
        val id = item.item.id.value
        if (metered()) {
            Diag.log("resolve", "not readying the picture of $id while its audio copy plays: the network is metered")
            return
        }
        Diag.log("resolve", "readying the picture of $id while its audio copy plays, so Watch is quick")
        val resolved = prefetch(watchUrl, item.item.sourceId)
        Diag.log("resolve", "picture of $id ${if (resolved != null) "ready" else "could not be readied"}")
    }
}
