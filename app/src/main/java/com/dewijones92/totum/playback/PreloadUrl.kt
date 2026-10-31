package com.dewijones92.totum.playback

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem

internal fun PlayableItem.preloadableUrl(): HttpUrl? = when (val handle = handle) {
    is PlayHandle.Podcast -> if (handle.localPath != null) {
        null
    } else {
        (handle.audioUrl?.takeUnless { it.isPlaylist() } ?: item.mediaUrl)?.takeUnless { it.isPlaylist() }
    }
    is PlayHandle.LocalVideo -> null
    is PlayHandle.Video -> null
}

private fun HttpUrl.isPlaylist(): Boolean = value.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
