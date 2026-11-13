package com.dewijones92.totum.domain

public object JellyfinIds {
    public const val SOURCE: String = "jellyfin"
    public const val PREFIX: String = "$SOURCE:"
}

public val MediaItem.isJellyfin: Boolean get() = id.value.startsWith(JellyfinIds.PREFIX)

public val MediaItem.jellyfinEpisode: EpisodeCode? get() = if (isJellyfin && author != null) episodeCodeOf(title) else null

public val MediaItem.isJellyfinFilm: Boolean get() = isJellyfin && author == null
