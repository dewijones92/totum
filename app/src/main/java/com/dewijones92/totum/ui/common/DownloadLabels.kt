package com.dewijones92.totum.ui.common

import androidx.annotation.StringRes
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.offersListenAndWatch
import com.dewijones92.totum.domain.pillar

@StringRes
internal fun downloadLabel(item: MediaItem, audioOnly: Boolean, pillar: MediaKind = item.pillar): Int = when {
    item.contentKind == MediaContentKind.MUSIC -> R.string.download_the_song
    !item.offersListenAndWatch(pillar) -> R.string.download_the_file
    audioOnly -> R.string.download_the_sound
    else -> R.string.download_the_video
}
