package com.dewijones92.totum.playback.cast

import java.util.Locale
import kotlin.math.ceil

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
        val text = StringBuilder()
        text.append("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-INDEPENDENT-SEGMENTS\n")
        text.append("#EXT-X-TARGETDURATION:${ceil(segmentMs / MS_D).toInt()}\n#EXT-X-MEDIA-SEQUENCE:0\n")
        text.append("#EXT-X-MAP:URI=\"$initUrl\"\n")
        repeat(count) { n ->
            val ms = if (n == count - 1) durationMs - n * segmentMs else segmentMs
            text.append("#EXTINF:${String.format(Locale.ROOT, "%.3f", ms / MS_D)},\n${segmentUrl(n)}\n")
        }
        text.append("#EXT-X-ENDLIST\n")
        return text.toString()
    }

    private const val MS = 1000L
    private const val MS_D = 1000.0
}
