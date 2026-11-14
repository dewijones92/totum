package com.dewijones92.totum.playback.cast

import java.util.Locale
import kotlin.math.ceil

internal class HlsSegment(val url: String, val seconds: Double, val bytes: ByteSpan? = null)

internal class HlsPicture(
    val codecs: String,
    val width: Int,
    val height: Int,
    val bandwidth: Int,
    val playlist: String,
)

internal object HlsPlaylist {

    fun sound(
        durationMs: Long,
        segmentSamples: Long,
        sampleRate: Int,
        initUrl: String,
        segmentUrl: (Int) -> String,
    ): String? {
        if (durationMs <= 0 || segmentSamples <= 0 || sampleRate <= 0) return null
        val segmentMs = segmentSamples * MS / sampleRate
        val count = ceil(durationMs.toDouble() / segmentMs).toInt()
        val segments = List(count) { n ->
            val ms = if (n == count - 1) durationMs - n * segmentMs else segmentMs
            HlsSegment(segmentUrl(n), ms / MS_D)
        }
        return media(initUrl, null, segments, ceil(segmentMs / MS_D).toInt())
    }

    fun media(initUrl: String, initBytes: ByteSpan?, segments: List<HlsSegment>, targetSeconds: Int? = null): String {
        val longest = targetSeconds ?: ceil(segments.maxOfOrNull { it.seconds } ?: 1.0).toInt()
        val text = StringBuilder()
        text.append("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-INDEPENDENT-SEGMENTS\n")
        text.append("#EXT-X-TARGETDURATION:$longest\n#EXT-X-MEDIA-SEQUENCE:0\n")
        text.append("#EXT-X-MAP:URI=\"$initUrl\"${initBytes?.let { ",BYTERANGE=\"${range(it)}\"" }.orEmpty()}\n")
        segments.forEach { segment ->
            text.append("#EXTINF:${String.format(Locale.ROOT, "%.3f", segment.seconds)},\n")
            segment.bytes?.let { text.append("#EXT-X-BYTERANGE:${range(it)}\n") }
            text.append("${segment.url}\n")
        }
        text.append("#EXT-X-ENDLIST\n")
        return text.toString()
    }

    fun master(picture: HlsPicture, sound: CastAudio.Processed, videoUrl: String, soundUrl: String): String =
        listOf(
            "#EXTM3U",
            "#EXT-X-VERSION:7",
            "#EXT-X-INDEPENDENT-SEGMENTS",
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"$SOUND_GROUP\",NAME=\"Totum\",DEFAULT=YES,AUTOSELECT=YES," +
                "CHANNELS=\"${sound.channels}\",URI=\"$soundUrl\"",
            "#EXT-X-STREAM-INF:BANDWIDTH=${picture.bandwidth + DashManifest.AUDIO_BANDWIDTH}," +
                "CODECS=\"${picture.codecs},${sound.codecs}\",RESOLUTION=${picture.width}x${picture.height}," +
                "AUDIO=\"$SOUND_GROUP\"",
            videoUrl,
            "",
        ).joinToString("\n")

    private fun range(span: ByteSpan): String = "${span.last - span.first + 1}@${span.first}"

    private const val MS = 1000L
    private const val MS_D = 1000.0
    private const val SOUND_GROUP = "sound"
}
