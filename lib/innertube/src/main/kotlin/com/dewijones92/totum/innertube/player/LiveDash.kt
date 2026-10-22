package com.dewijones92.totum.innertube.player

public data class LiveHead(val sequence: Long, val streamMs: Long)

public object LiveDash {

    public fun head(header: (String) -> String?): LiveHead? {
        val sequence = (header("X-Head-Seqnum") ?: header("X-Sequence-Num"))?.toLongOrNull() ?: return null
        val streamMs = header("X-Head-Time-Millis")?.toLongOrNull()
            ?: header("X-Head-Time-Sec")?.toLongOrNull()?.times(MILLIS)
            ?: return null
        return LiveHead(sequence, streamMs)
    }

    public fun headProbeFormat(formats: List<PlayableFormat>): PlayableFormat? =
        formats.filter { it.url != null && it.mimeType?.startsWith("audio/") == true }.minByOrNull { it.bitrate ?: 0 }

    public fun manifest(formats: List<PlayableFormat>, head: LiveHead, behind: Int = BEHIND): String? {
        val playable = formats.filter { it.url != null && it.mimeType != null }
        val video = playable.filter { it.mimeType!!.startsWith("video/") }
        val audio = playable.filter { it.mimeType!!.startsWith("audio/") }.maxByOrNull { it.bitrate ?: 0 }
        if (video.isEmpty() && audio == null) return null
        val segmentUs = segmentUs(head, playable) ?: return null
        val first = (head.sequence - behind).coerceAtLeast(0)
        val count = SPAN_US / segmentUs
        val byContainer = video.groupBy { it.mimeType!!.substringBefore(';').trim() }.values
        val sets = buildList {
            byContainer.forEachIndexed { i, sameContainer -> add(set(1 + i, "video", sameContainer, segmentUs, first)) }
            audio?.let { add(set(AUDIO_SET, "audio", listOf(it), segmentUs, first)) }
        }
        return """<?xml version="1.0" encoding="utf-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-live:2011" type="static" mediaPresentationDuration="PT${count * segmentUs / MICROS}S" minBufferTime="PT2S">
<Period id="0" start="PT0S">
${sets.joinToString("\n")}
</Period>
</MPD>
"""
    }

    private fun segmentUs(head: LiveHead, formats: List<PlayableFormat>): Long? =
        (head.streamMs * MILLIS / head.sequence).takeIf { head.sequence > 0 && it > 0 }
            ?: formats.firstNotNullOfOrNull { it.targetDurationSec }?.let { it * MICROS }

    private fun set(id: Int, type: String, formats: List<PlayableFormat>, segmentUs: Long, first: Long): String {
        val mime = formats.first().mimeType!!.substringBefore(';').trim()
        val representations = formats.joinToString("\n") { representation(it, segmentUs, first) }
        return """<AdaptationSet id="$id" contentType="$type" mimeType="$mime">
$representations
</AdaptationSet>"""
    }

    private fun representation(format: PlayableFormat, segmentUs: Long, first: Long): String = buildString {
        val url = format.url!!.value
        append("<Representation id=\"${format.itag}\" bandwidth=\"${format.bitrate ?: 0}\"")
        CODECS.find(format.mimeType!!)?.groupValues?.get(1)?.let { append(" codecs=\"$it\"") }
        format.width?.let { append(" width=\"$it\"") }
        format.height?.let { append(" height=\"$it\"") }
        append(">\n<SegmentTemplate timescale=\"$MICROS\" duration=\"$segmentUs\" startNumber=\"$first\"")
        append(" presentationTimeOffset=\"${first * segmentUs}\" media=\"${xml("$url&sq=")}${'$'}Number${'$'}\">")
        format.initRange?.let { append("<Initialization sourceURL=\"${xml(url)}\" range=\"${it.first}-${it.last}\"/>") }
        append("</SegmentTemplate>\n</Representation>")
    }

    private fun xml(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")

    private val CODECS = Regex("""codecs="([^"]+)"""")
    private const val MICROS = 1_000_000L
    private const val MILLIS = 1000L
    private const val BEHIND = 3
    private const val AUDIO_SET = 9
    private const val SPAN_US = 12L * 60 * 60 * MICROS
}
