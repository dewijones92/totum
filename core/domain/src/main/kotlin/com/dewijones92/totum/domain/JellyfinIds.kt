package com.dewijones92.totum.domain

public object JellyfinIds {
    public const val SOURCE: String = "jellyfin"
    public const val PREFIX: String = "$SOURCE:"
}

public val MediaItem.isJellyfin: Boolean get() = id.value.startsWith(JellyfinIds.PREFIX)
