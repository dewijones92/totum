package com.dewijones92.totum.pins

import com.dewijones92.totum.backup.toBackup
import com.dewijones92.totum.backup.toPlayable
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.backup.BackupItem
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.canonicalItemId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
sealed interface Pin {
    val key: String
    val title: String
    val artUrl: String?
    val mayShowPicture: Boolean
        get() = when (this) {
            is Item -> item.contentKind != MediaContentKind.MUSIC.name
            is Album, is Artist, is Radio, is ArtistSongs -> false
            else -> true
        }

    @Serializable
    @SerialName("item")
    data class Item(val item: BackupItem) : Pin {
        override val key: String get() = "$ITEM_PREFIX${canonicalItemId(item.itemId)}"
        override val title: String get() = item.title
        override val artUrl: String? get() = item.thumbnailUrl
        fun playable(): PlayableItem? = item.toPlayable()
    }

    @Serializable
    @SerialName("album")
    data class Album(
        val browseId: String,
        override val title: String,
        override val artUrl: String?,
        val shuffled: Boolean = false,
    ) : Pin {
        override val key: String get() = if (shuffled) "album-shuffled:$browseId" else "album:$browseId"
    }

    @Serializable
    @SerialName("playlist")
    data class Playlist(
        val browseId: String,
        override val title: String,
        override val artUrl: String?,
        val music: Boolean,
    ) : Pin {
        override val key: String get() = "playlist:$browseId"
    }

    @Serializable
    @SerialName("artist")
    data class Artist(
        val browseId: String?,
        val name: String,
        override val artUrl: String?,
        val radioPlaylistId: String?,
    ) : Pin {
        override val key: String get() = "artist:${browseId ?: name}"
        override val title: String get() = name
    }

    @Serializable
    @SerialName("radio")
    data class Radio(
        val videoId: String?,
        val playlistId: String,
        override val title: String,
        override val artUrl: String?,
        val params: String? = null,
    ) : Pin {
        override val key: String get() = "radio:$playlistId"
    }

    @Serializable
    @SerialName("show")
    data class Show(
        val sourceId: String,
        val feedUrl: String,
        override val title: String,
        override val artUrl: String?,
    ) : Pin {
        override val key: String get() = "show:$sourceId"
    }

    @Serializable
    @SerialName("shuffle-all")
    data class ShuffleAllOf(
        val target: String,
        override val title: String,
        override val artUrl: String?,
        val sources: List<PinnedSource>,
        val includeShorts: Boolean,
    ) : Pin {
        override val key: String get() = "shuffle:$target"
    }

    @Serializable
    @SerialName("artist-songs")
    data class ArtistSongs(
        val listingBrowseId: String,
        val listingParams: String?,
        val artist: String,
        override val artUrl: String?,
        val artistId: String? = null,
    ) : Pin {
        override val key: String get() = "artist-songs:$listingBrowseId"
        override val title: String get() = "$artist · All songs"
    }

    companion object {
        fun of(item: PlayableItem): Pin = Item(item.toBackup())

        fun of(show: MediaSource.PodcastFeed): Pin =
            Show(show.id.value, show.feedUrl.value, show.title, show.artworkUrl?.value)
    }
}

@Serializable
data class PinnedSource(val feed: Boolean, val id: String, val title: String, val url: String, val artUrl: String?) {
    fun toSource(): MediaSource? {
        val link = HttpUrl.parse(url) ?: return null
        val art = artUrl?.let(HttpUrl::parse)
        return if (feed) {
            MediaSource.PodcastFeed(SourceId(id), title, link, artworkUrl = art)
        } else {
            MediaSource.VideoChannel(SourceId(id), title, link, art)
        }
    }

    companion object {
        fun of(source: MediaSource): PinnedSource = when (source) {
            is MediaSource.VideoChannel ->
                PinnedSource(false, source.id.value, source.title, source.channelUrl.value, source.artworkUrl?.value)
            is MediaSource.PodcastFeed ->
                PinnedSource(true, source.id.value, source.title, source.feedUrl.value, source.artworkUrl?.value)
        }
    }
}

internal object PinCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "kind"
    }
    private val list = ListSerializer(Pin.serializer())

    fun encode(pins: List<Pin>): String = json.encodeToString(list, pins)

    fun decode(text: String): List<Pin> = json.decodeFromString(list, text)
}

private const val ITEM_PREFIX = "item:"

/** A key as a pin now writes it, so an icon made before v26 still finds its pin. */
fun canonicalPinKey(key: String): String =
    if (key.startsWith(ITEM_PREFIX)) ITEM_PREFIX + canonicalItemId(key.removePrefix(ITEM_PREFIX)) else key
