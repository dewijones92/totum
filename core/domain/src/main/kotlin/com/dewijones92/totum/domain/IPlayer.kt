package com.dewijones92.totum.domain

import com.dewijones92.totum.common.HttpUrl
import java.time.Instant

public object IPlayer {
    public const val APP_PACKAGE: String = "uk.co.bbc.iplayer"
    public const val ID_PREFIX: String = "bbc:"
    private const val EPISODE = "https://www.bbc.co.uk/iplayer/episode/"

    public fun episodeUrl(pid: String): HttpUrl = HttpUrl.of(EPISODE + pid)

    public fun isEpisode(url: HttpUrl): Boolean = url.value.startsWith(EPISODE)

    public fun pidOf(url: HttpUrl): String? =
        url.value.takeIf { isEpisode(url) }?.removePrefix(EPISODE)?.substringBefore('/')?.substringBefore('?')
            ?.ifBlank { null }
}

public val MediaItem.iPlayerUrl: HttpUrl? get() = mediaUrl?.takeIf(IPlayer::isEpisode)

public val MediaItem.opensExternally: Boolean get() = iPlayerUrl != null

public fun MediaItem.isGoneFromIPlayer(now: Instant): Boolean = opensExternally && availableUntil?.isBefore(now) == true
