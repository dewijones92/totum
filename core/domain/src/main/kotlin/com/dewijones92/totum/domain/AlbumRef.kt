package com.dewijones92.totum.domain

public data class AlbumRef(public val id: String?, public val title: String)

public fun canonicalItemId(id: String): String =
    id.removePrefix(WATCH_PREFIX).takeIf { id.startsWith(WATCH_PREFIX) && isYouTubeVideoId(it) } ?: id

public fun isYouTubeVideoId(value: String): Boolean = VIDEO_ID.matches(value)

public const val WATCH_PREFIX: String = com.dewijones92.totum.common.YOUTUBE_WATCH_PREFIX
private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
