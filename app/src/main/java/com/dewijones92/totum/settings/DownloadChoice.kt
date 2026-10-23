package com.dewijones92.totum.settings

sealed interface DownloadChoice {
    val stored: String

    data object Sound : DownloadChoice {
        override val stored: String get() = "sound"
    }

    data class Video(val maxHeight: Int?) : DownloadChoice {
        override val stored: String get() = "video:${maxHeight ?: BEST}"
    }

    companion object {
        private const val BEST = "best"
        private const val VIDEO = "video:"

        val heights: List<Int?> = listOf(null, 1080, 720, 480)

        fun parse(stored: String?): DownloadChoice? = when {
            stored == Sound.stored -> Sound
            stored?.startsWith(VIDEO) == true -> stored.removePrefix(VIDEO).let { height ->
                if (height == BEST) Video(null) else height.toIntOrNull()?.let(::Video)
            }
            else -> null
        }
    }
}
