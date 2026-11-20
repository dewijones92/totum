package com.dewijones92.totum.domain

import com.dewijones92.totum.common.HttpUrl
import java.time.Instant
import java.util.Base64
import kotlin.time.Duration.Companion.milliseconds

public enum class PhoneMediaKind(public val key: String) {
    VIDEO("video"),
    AUDIO("audio"),
    PHOTO("photo"),
}

public object PhoneMediaIds {
    public const val SOURCE_NAME: String = "phone"
    public const val PREFIX: String = "$SOURCE_NAME:"
    public const val THUMBNAIL_HOST: String = "phone-media.totum.invalid"
    public const val GOOGLE_REF_PREFIX: String = "g-"
    public val SOURCE: SourceId = SourceId(SOURCE_NAME)

    private const val SEPARATOR = '.'
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    public fun keyOf(ref: String, path: String): String =
        ref + SEPARATOR + encoder.encodeToString(path.toByteArray(Charsets.UTF_8))

    public fun refOf(key: String): String = key.substringBefore(SEPARATOR)

    public fun pathOf(key: String): String? {
        val encoded = key.substringAfter(SEPARATOR, missingDelimiterValue = "")
            .takeIf { it.isNotEmpty() } ?: return null
        return runCatching { String(decoder.decode(encoded), Charsets.UTF_8) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    public fun id(kind: PhoneMediaKind, key: String): MediaItemId = MediaItemId("$PREFIX${kind.key}:$key")

    public fun thumbnail(kind: PhoneMediaKind, key: String): HttpUrl =
        HttpUrl.of("https://$THUMBNAIL_HOST/${kind.key}/$key")

    public fun parse(id: String): Pair<PhoneMediaKind, String>? {
        if (!id.startsWith(PREFIX)) return null
        val rest = id.removePrefix(PREFIX)
        val kind = PhoneMediaKind.entries.firstOrNull { rest.startsWith("${it.key}:") } ?: return null
        val key = rest.removePrefix("${kind.key}:").takeIf { it.isNotEmpty() } ?: return null
        return kind to key
    }

    public fun parseThumbnail(url: String): Pair<PhoneMediaKind, String>? {
        val path = url.substringAfter("//$THUMBNAIL_HOST/", missingDelimiterValue = "").substringBefore('?')
        val kind = PhoneMediaKind.entries.firstOrNull { path.startsWith("${it.key}/") } ?: return null
        val key = path.removePrefix("${kind.key}/").takeIf { it.isNotEmpty() } ?: return null
        return kind to key
    }
}

public val MediaItemId.isOnThisPhone: Boolean get() = PhoneMediaIds.parse(value) != null

public val MediaItem.isOnThisPhone: Boolean get() = id.isOnThisPhone

public val MediaItem.phoneMediaKind: PhoneMediaKind? get() = PhoneMediaIds.parse(id.value)?.first

public data class PhoneMediaEntry(
    public val kind: PhoneMediaKind,
    public val key: String,
    public val title: String,
    public val path: String,
    public val uri: String,
    public val mimeType: String? = null,
    public val durationMs: Long? = null,
    public val addedAtSeconds: Long? = null,
    public val takenAtMs: Long? = null,
    public val sizeBytes: Long? = null,
    public val artist: String? = null,
    public val album: String? = null,
    public val width: Int? = null,
    public val height: Int? = null,
    public val folder: String? = null,
    public val contentKind: MediaContentKind = MediaContentKind.STANDARD,
) {
    public val id: MediaItemId get() = PhoneMediaIds.id(kind, key)

    public val ref: String get() = PhoneMediaIds.refOf(key)

    public val takenOrAdded: Instant?
        get() = takenAtMs?.takeIf { it > 0 }?.let(Instant::ofEpochMilli)
            ?: addedAtSeconds?.takeIf { it > 0 }?.let(Instant::ofEpochSecond)

    public val isGoogle: Boolean get() = ref.startsWith(PhoneMediaIds.GOOGLE_REF_PREFIX)
}

public fun PhoneMediaEntry.toPlayableOrNull(): PlayableItem? {
    if (kind == PhoneMediaKind.PHOTO) return null
    val item = MediaItem(
        id = id,
        sourceId = PhoneMediaIds.SOURCE,
        title = title,
        publishedAt = takenOrAdded,
        duration = durationMs?.takeIf { it > 0 }?.milliseconds,
        author = if (kind == PhoneMediaKind.AUDIO) artist else folder,
        description = album?.let { "💿 $it" },
        thumbnailUrl = PhoneMediaIds.thumbnail(kind, key),
        contentKind = contentKind,
    )
    return PlayableItem(item, phoneHandle(kind, path))
}

internal fun MediaItem.phonePlayableOrNull(): PlayableItem? {
    val (kind, key) = PhoneMediaIds.parse(id.value) ?: return null
    if (kind == PhoneMediaKind.PHOTO) return null
    val path = PhoneMediaIds.pathOf(key) ?: return null
    return PlayableItem(this, phoneHandle(kind, path))
}

private fun phoneHandle(kind: PhoneMediaKind, path: String): PlayHandle =
    if (kind == PhoneMediaKind.VIDEO) PlayHandle.LocalVideo(path) else PlayHandle.Podcast(localPath = path)
