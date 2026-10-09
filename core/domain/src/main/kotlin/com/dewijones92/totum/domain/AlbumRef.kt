package com.dewijones92.totum.domain

public data class AlbumRef(public val id: String?, public val title: String)

public fun canonicalItemId(id: String): String =
    id.removePrefix(WATCH_PREFIX).takeIf { id.startsWith(WATCH_PREFIX) && it.length == VIDEO_ID_LENGTH } ?: id

public const val WATCH_PREFIX: String = "https://www.youtube.com/watch?v="
private const val VIDEO_ID_LENGTH = 11
