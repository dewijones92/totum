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
