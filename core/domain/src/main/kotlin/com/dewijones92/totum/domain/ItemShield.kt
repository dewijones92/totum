package com.dewijones92.totum.domain

import com.dewijones92.totum.common.HttpUrl
import kotlin.time.Duration

public data class ItemFace(
    val title: String,
    val thumbnailUrl: HttpUrl?,
    val duration: Duration?,
    val blurThumbnail: Boolean = false,
    val shielded: Boolean = false,
) {
    public val artworkUrl: HttpUrl? get() = thumbnailUrl.takeUnless { shielded }
}

public interface ItemShield {
    public fun face(item: MediaItem): ItemFace
}

public object NoShield : ItemShield {
    override fun face(item: MediaItem): ItemFace = ItemFace(item.title, item.thumbnailUrl, item.duration)
}

public fun ItemShield.titleOf(item: MediaItem): String = face(item).title

public fun ItemShield.titleOf(id: MediaItemId, title: String, author: String?): String = titleOf(
    MediaItem(
        id = id,
        sourceId = SourceId("recorded"),
        title = title,
        publishedAt = null,
        duration = null,
        author = author
    ),
)

public object ItemShields : ItemShield {
    @Volatile
    private var delegate: ItemShield = NoShield

    public fun use(shield: ItemShield) {
        delegate = shield
    }

    override fun face(item: MediaItem): ItemFace = delegate.face(item)
}
