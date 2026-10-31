package com.dewijones92.totum.ui.common

import androidx.annotation.StringRes
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.offersDownloadChoice
import com.dewijones92.totum.domain.pillar
import com.dewijones92.totum.settings.DownloadChoice

@StringRes
internal fun downloadLabel(item: MediaItem, choice: DownloadChoice?, pillar: MediaKind = item.pillar): Int = when {
    !item.offersDownloadChoice(pillar) -> R.string.download_the_file
    item.contentKind == MediaContentKind.MUSIC -> R.string.download_the_song
    choice == DownloadChoice.Sound -> R.string.download_the_sound
    choice is DownloadChoice.Video -> R.string.download_the_video
    else -> R.string.download_choose
}
