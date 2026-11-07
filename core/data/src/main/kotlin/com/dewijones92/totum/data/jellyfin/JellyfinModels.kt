package com.dewijones92.totum.data.jellyfin

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

@Serializable
public enum class JellyfinKind(public val playable: Boolean, public val emoji: String) {
    MOVIE(true, "🎬"),
    SERIES(false, "📺"),
    SEASON(false, "📁"),
    EPISODE(true, "🎞️"),
    OTHER(false, "📁"),
}

@Serializable
public data class JellyfinItem(
    val id: String,
    val name: String,
    val kind: JellyfinKind,
    val overview: String? = null,
    val year: Int? = null,
    val runtimeMs: Long? = null,
    val seriesName: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val container: String? = null,
) {
    public val title: String
        get() = if (kind == JellyfinKind.EPISODE && episodeNumber != null) {
            val season = seasonNumber?.let { "S%02d".format(Locale.ROOT, it) }.orEmpty()
            "${season}E${"%02d".format(Locale.ROOT, episodeNumber)} · $name"
        } else {
            name
        }

    public val facts: List<String>
        get() = listOfNotNull(
            year?.toString(),
            runtimeMs?.let { "${it / MS_PER_MINUTE} min" },
            seriesName?.takeIf { kind == JellyfinKind.EPISODE },
        )
}

@Serializable
public data class JellyfinListing(val parent: String? = null, val title: String, val items: List<JellyfinItem>)

public const val JELLYFIN_SOURCE: String = "jellyfin"

public fun MediaItemId.jellyfinId(): String? =
    value.removePrefix("$JELLYFIN_SOURCE:").takeIf { value.startsWith("$JELLYFIN_SOURCE:") && it.isNotBlank() }

public fun JellyfinItem.streamPath(): String {
    val extension = container?.substringBefore(',')?.ifBlank { null } ?: "mkv"
    return "jellyfin/stream/$id.$extension"
}

public fun JellyfinItem.toPlayable(streamUrl: HttpUrl): PlayableItem = PlayableItem(
    MediaItem(
        id = MediaItemId("$JELLYFIN_SOURCE:$id"),
        sourceId = SourceId("$JELLYFIN_SOURCE:${seriesName ?: "films"}"),
        title = title,
        publishedAt = null,
        duration = runtimeMs?.takeIf { it > 0 }?.milliseconds,
        author = seriesName,
        description = overview,
        mediaUrl = streamUrl,
    ),
    PlayHandle.Podcast(),
)

private const val MS_PER_MINUTE = 60_000L
