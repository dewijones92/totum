package com.dewijones92.totum.domain

import com.dewijones92.totum.common.HttpUrl

public enum class Picture { YES, NO, UNKNOWN }

public val MediaItem.picture: Picture
    get() = when {
        pillar == MediaKind.VIDEO -> Picture.YES
        else -> mediaUrl?.let(::pictureAt) ?: Picture.UNKNOWN
    }

public val PlayableItem.picture: Picture
    get() = when (handle) {
        is PlayHandle.Video, is PlayHandle.LocalVideo -> Picture.YES
        is PlayHandle.Podcast -> item.picture
    }

public val PlayableItem.offersListenAndWatch: Boolean
    get() = item.offersListenAndWatch(pillar) || picture == Picture.YES

public val PlayableItem.pictureForSwitching: Picture
    get() = if (offersListenAndWatch) Picture.YES else picture

public fun MediaItem.offersListenAndWatch(pillar: MediaKind = this.pillar): Boolean =
    pillar == MediaKind.VIDEO || picture == Picture.YES

public fun pictureAt(url: HttpUrl): Picture = pictureAt(url.value)

public fun pictureAt(address: String): Picture {
    val extension = address.substringBefore('?').substringBefore('#').substringAfterLast('/')
        .substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return when (extension) {
        in VIDEO_EXTENSIONS -> Picture.YES
        in AUDIO_EXTENSIONS -> Picture.NO
        else -> Picture.UNKNOWN
    }
}

public val VIDEO_EXTENSIONS: Set<String> = setOf("mkv", "mp4", "m4v", "mov", "webm", "avi", "ts", "3gp")
public val AUDIO_EXTENSIONS: Set<String> = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac")
