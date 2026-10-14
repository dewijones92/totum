package com.dewijones92.totum.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.music.AlbumYears

val LocalAlbumYears = compositionLocalOf<AlbumYears?> { null }

@Composable
fun rowFacts(item: MediaItem, pillar: MediaKind): List<String> {
    val albumId = item.album?.id?.takeIf { item.contentKind == MediaContentKind.MUSIC }
    val years = LocalAlbumYears.current
    val year = if (albumId != null && years != null) {
        val known by years.known.collectAsStateWithLifecycle()
        LaunchedEffect(albumId) { years.want(albumId) }
        known[albumId]?.ifEmpty { null }
    } else {
        null
    }
    return mediaItemFacts(item, pillar, LocalNow.current, albumYear = year)
}
