package com.dewijones92.totum.cli.serve.targets

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.jellyfin.jellyfinId
import com.dewijones92.totum.domain.PlayableItem

internal class TargetStreams(
    private val fromHome: suspend (PlayableItem, Boolean) -> String?,
    private val jellyfin: ((String) -> String)?,
) : TargetSounds {

    override suspend fun urlFor(item: PlayableItem, picture: Boolean): String? {
        val id = item.item.id.jellyfinId() ?: return fromHome(item, picture)
        val state = if (jellyfin == null) "not configured" else "ok"
        Diag.log("targets", "${item.item.id.value}: from Jellyfin directly ($state)")
        return jellyfin?.invoke(id)
    }
}
