package com.dewijones92.totum.domain

public enum class VideoCodecFamily {
    AV1,
    VP9,
    HEVC,
    AVC,
    ;

    public companion object {
        public fun of(codec: String?): VideoCodecFamily? {
            val c = codec?.lowercase()?.trim() ?: return null
            return when {
                c.startsWith("av01") || c == "av1" -> AV1
                c.startsWith("vp09") || c == "vp9" -> VP9
                c.startsWith("hev1") || c.startsWith("hvc1") || c == "hevc" -> HEVC
                c.startsWith("avc1") || c.startsWith("avc3") || c == "h264" -> AVC
                else -> null
            }
        }
    }
}

public data class CastScreen(
    val model: String,
    val codecs: Set<VideoCodecFamily>,
    val maxHeight: Int,
    val known: Boolean,
) {
    public fun canDecode(codec: String?, height: Int?): Boolean {
        val family = VideoCodecFamily.of(codec) ?: return true
        return family in codecs && (height == null || height <= maxHeight)
    }

    public fun bestOf(offered: List<Pair<String?, Int>>): Int =
        offered.filter { (codec, height) -> canDecode(codec, height) }.maxOfOrNull { it.second } ?: maxHeight

    public val label: String
        get() = "$model (${codecs.joinToString("/")} up to ${maxHeight}p${if (known) "" else ", assumed"})"

    public companion object {
        private const val UHD = 2160
        private const val FULL_HD = 1080
        private const val UNKNOWN = "unknown Cast device"
        private val PLAIN = setOf(VideoCodecFamily.VP9, VideoCodecFamily.AVC)
        private val MEASURED = PLAIN + VideoCodecFamily.HEVC
        private val LG_2020_ON = Regex("""^(OLED|QNED|NANO)\d{2}[A-Z]\d""", RegexOption.IGNORE_CASE)

        public fun of(modelName: String?): CastScreen {
            val model = modelName?.trim().orEmpty()
            val lower = model.lowercase()
            return when {
                lower.contains("chromecast ultra") -> CastScreen(model, MEASURED, FULL_HD, known = true)
                lower.contains("webos") || LG_2020_ON.containsMatchIn(model) -> CastScreen(
                    model,
                    MEASURED,
                    UHD,
                    known = true
                )
                else -> CastScreen(model.ifEmpty { UNKNOWN }, PLAIN, FULL_HD, known = false)
            }
        }
    }
}
