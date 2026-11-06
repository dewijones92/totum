package com.dewijones92.totum.domain

public fun MediaContentKind.afterSeeing(resolved: MediaContentKind): MediaContentKind = when (this) {
    MediaContentKind.STANDARD -> resolved
    MediaContentKind.LIVE -> if (resolved == MediaContentKind.WAS_LIVE) MediaContentKind.WAS_LIVE else this
    MediaContentKind.WAS_LIVE -> if (resolved == MediaContentKind.LIVE) MediaContentKind.LIVE else this
    MediaContentKind.SHORT, MediaContentKind.MUSIC, MediaContentKind.AUDIOBOOK -> this
}

public val MediaContentKind.canBeLive: Boolean
    get() = when (this) {
        MediaContentKind.STANDARD, MediaContentKind.LIVE, MediaContentKind.WAS_LIVE -> true
        MediaContentKind.SHORT, MediaContentKind.MUSIC, MediaContentKind.AUDIOBOOK -> false
    }
