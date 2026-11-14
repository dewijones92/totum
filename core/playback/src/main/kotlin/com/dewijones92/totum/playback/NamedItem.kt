package com.dewijones92.totum.playback

import androidx.media3.common.MediaItem

internal fun namedItemId(receiverItem: MediaItem?, sentItem: MediaItem?): String? {
    receiverItem ?: return null
    return receiverItem.mediaId.ifBlank { null } ?: sentItem?.mediaId?.ifBlank { null }
}
