package com.dewijones92.totum.pins

import com.dewijones92.totum.backup.toBackup
import com.dewijones92.totum.backup.toPlayable
import com.dewijones92.totum.data.backup.BackupItem
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.PlayableItem
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
    data class Album(val browseId: String, override val title: String, override val artUrl: String?) : Pin {
        override val key: String get() = "album:$browseId"
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

    companion object {
        fun of(item: PlayableItem): Pin = Item(item.toBackup())

        fun of(show: MediaSource.PodcastFeed): Pin =
            Show(show.id.value, show.feedUrl.value, show.title, show.artworkUrl?.value)
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
