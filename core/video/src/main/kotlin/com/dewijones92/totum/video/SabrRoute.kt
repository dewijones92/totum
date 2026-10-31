package com.dewijones92.totum.video

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.PlayableItem
import kotlinx.coroutines.flow.Flow
import java.io.File

fun interface SabrRoute {
    fun bytes(url: HttpUrl, item: PlayableItem, target: File): Flow<DownloadState>?

    companion object {
        val NONE: SabrRoute = SabrRoute { _, _, _ -> null }
    }
}
