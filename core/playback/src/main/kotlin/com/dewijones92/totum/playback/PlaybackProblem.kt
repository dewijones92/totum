package com.dewijones92.totum.playback

public enum class TrackKind { VIDEO, AUDIO }

public sealed interface PlaybackProblem {
    public val errorName: String

    public data class CannotDecode(
        public val track: TrackKind,
        public val format: String,
        override val errorName: String,
    ) : PlaybackProblem

    public data class Failed(override val errorName: String) : PlaybackProblem

    public val isPicture: Boolean get() = this is CannotDecode && track == TrackKind.VIDEO
}

public class ErroredFormat(public val mimeType: String?, public val codecs: String?) {
    public companion object {
        private val IN_MESSAGE = Regex("""Format\([^,]*, [^,]*, [^,]*, ([a-z]+/[^,\s]+), ([^,\s]*),""")

        public fun fromMessage(message: String?): ErroredFormat? {
            val found = message?.let(IN_MESSAGE::find) ?: return null
            val codecs = found.groupValues[2].takeIf { it != "null" && it.isNotBlank() }
            return ErroredFormat(found.groupValues[1], codecs)
        }
    }
}

public fun problemOf(errorName: String, format: ErroredFormat?): PlaybackProblem {
    val mime = format?.mimeType
    val track = when {
        !errorName.startsWith(DECODER_ERRORS) || mime == null -> null
        mime.startsWith("video/") -> TrackKind.VIDEO
        mime.startsWith("audio/") -> TrackKind.AUDIO
        else -> null
    }
    return if (track == null) {
        PlaybackProblem.Failed(errorName)
    } else {
        PlaybackProblem.CannotDecode(track, CodecWords.of(mime, format?.codecs), errorName)
    }
}

private const val DECODER_ERRORS = "ERROR_CODE_DECOD"

public object CodecWords {
    private val NAMES = mapOf(
        "video/x-vnd.on2.vp8" to "VP8",
        "video/dolby-vision" to "Dolby Vision",
        "video/mp4v-es" to "MPEG-4",
        "video/mpeg2" to "MPEG-2",
        "audio/ac3" to "AC-3",
        "audio/eac3" to "E-AC-3",
        "audio/eac3-joc" to "E-AC-3",
        "audio/vnd.dts" to "DTS",
        "audio/vnd.dts.hd" to "DTS",
        "audio/true-hd" to "TrueHD",
        "audio/mp4a-latm" to "AAC",
        "audio/opus" to "Opus",
        "audio/vorbis" to "Vorbis",
        "audio/flac" to "FLAC",
        "audio/mpeg" to "MP3",
    )

    public fun of(mimeType: String?, codecs: String?): String {
        val parts = codecs?.split('.').orEmpty()
        val profile = parts.getOrNull(PROFILE_PART)
        return when {
            mimeType == null -> codecs ?: "an unknown format"
            mimeType == "video/hevc" -> hevc(profile)
            mimeType == "video/avc" -> avc(profile)
            mimeType == "video/av01" -> av1(parts.getOrNull(AV1_DEPTH_PART))
            mimeType == "video/x-vnd.on2.vp9" -> vp9(profile, parts.getOrNull(VP9_DEPTH_PART))
            else -> NAMES[mimeType] ?: codecs ?: mimeType.substringAfter('/').uppercase()
        }
    }

    private fun hevc(profile: String?): String {
        val idc = profile?.trimStart('A', 'B', 'C')?.toIntOrNull()
        return when (idc) {
            HEVC_MAIN_10 -> "HEVC 10-bit"
            HEVC_MAIN, HEVC_MAIN_STILL, null -> "HEVC"
            else -> "HEVC (profile $idc)"
        }
    }

    private fun avc(profile: String?): String {
        val idc = profile?.take(2)?.toIntOrNull(HEX)
        return when (idc) {
            AVC_HIGH_10 -> "H.264 10-bit"
            AVC_HIGH_422 -> "H.264 4:2:2"
            AVC_HIGH_444 -> "H.264 4:4:4"
            else -> "H.264"
        }
    }

    private fun av1(bitDepth: String?): String =
        bitDepth?.toIntOrNull()?.takeIf { it > EIGHT_BIT }?.let { "AV1 $it-bit" } ?: "AV1"

    private fun vp9(profile: String?, bitDepth: String?): String =
        (bitDepth?.toIntOrNull() ?: if (profile?.toIntOrNull() == VP9_PROFILE_2) TEN_BIT else null)
            ?.takeIf { it > EIGHT_BIT }?.let { "VP9 $it-bit" } ?: "VP9"

    private const val PROFILE_PART = 1
    private const val AV1_DEPTH_PART = 3
    private const val VP9_DEPTH_PART = 3
    private const val HEX = 16
    private const val HEVC_MAIN = 1
    private const val HEVC_MAIN_10 = 2
    private const val HEVC_MAIN_STILL = 3
    private const val AVC_HIGH_10 = 0x6E
    private const val AVC_HIGH_422 = 0x7A
    private const val AVC_HIGH_444 = 0xF4
    private const val EIGHT_BIT = 8
    private const val TEN_BIT = 10
    private const val VP9_PROFILE_2 = 2
}
