package com.dewijones92.totum.domain

public enum class VideoCodecFamily(public val prefixes: List<String>) {
    AVC(listOf("avc", "h264")),
    HEVC(listOf("hev", "hvc", "h265")),
    VP9(listOf("vp9", "vp09")),
    VP8(listOf("vp8", "vp08")),
    AV1(listOf("av01", "av1")),
    ;

    public companion object {
        public fun of(codec: String?): VideoCodecFamily? {
            val c = codec?.lowercase()?.trim() ?: return null
            return entries.firstOrNull { family -> family.prefixes.any(c::startsWith) }
        }
    }
}

public data class CastScreen(
    val model: String,
    val codecs: Set<VideoCodecFamily>,
    val maxHeight: Int,
    val known: Boolean,
    val codecHeights: Map<VideoCodecFamily, Int> = emptyMap(),
) {
    public fun maxHeightOf(family: VideoCodecFamily): Int = minOf(maxHeight, codecHeights[family] ?: maxHeight)

    public fun canDecode(codec: String?, height: Int?): Boolean {
        val family = VideoCodecFamily.of(codec)?.takeIf { it in PREFERRED } ?: return true
        return family in codecs && (height == null || height <= maxHeightOf(family))
    }

    public fun preference(codec: String?): Int = rank(codec)

    public fun bestOf(offered: List<Pair<String?, Int>>): Int =
        offered.filter { (codec, height) -> canDecode(codec, height) }.maxOfOrNull { it.second } ?: maxHeight

    public val label: String
        get() {
            val shown = codecs.sortedBy { PREFERRED.indexOf(it) }.groupBy(::maxHeightOf).entries
                .sortedByDescending { it.key }
                .joinToString(", ") { (height, families) -> "${families.joinToString("/")} up to ${height}p" }
            return "$model ($shown${if (known) "" else ", assumed"})"
        }

    public companion object {
        private const val UHD = 2160
        private const val FULL_HD = 1080
        private const val UNKNOWN = "unknown Cast device"
        private val PREFERRED = listOf(
            VideoCodecFamily.AV1,
            VideoCodecFamily.VP9,
            VideoCodecFamily.HEVC,
            VideoCodecFamily.AVC,
        )
        private val PLAIN = setOf(VideoCodecFamily.VP9, VideoCodecFamily.AVC)
        private val MEASURED = PLAIN + VideoCodecFamily.HEVC
        private val LG_2020_ON = Regex("""^(OLED|QNED|NANO)\d{2}[A-Z]\d""", RegexOption.IGNORE_CASE)

        public fun rank(codec: String?): Int =
            VideoCodecFamily.of(codec)?.let { PREFERRED.indexOf(it) }?.takeIf { it >= 0 } ?: Int.MAX_VALUE

        public fun of(modelName: String?): CastScreen {
            val model = modelName?.trim().orEmpty()
            val lower = model.lowercase()
            return when {
                lower.contains("chromecast ultra") -> CastScreen(model, MEASURED, FULL_HD, known = true)
                lower.contains("webos") || LG_2020_ON.containsMatchIn(model) -> CastScreen(
                    model,
                    MEASURED,
                    UHD,
                    known = true,
                    codecHeights = mapOf(VideoCodecFamily.AVC to FULL_HD),
                )
                else -> CastScreen(model.ifEmpty { UNKNOWN }, PLAIN, FULL_HD, known = false)
            }
        }
    }
}
