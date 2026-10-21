package com.dewijones92.totum.playback.cast

internal sealed interface CastAudio {
    data class Passthrough(val layout: StreamLayout, val url: String) : CastAudio

    data class Processed(
        val initUrl: String,
        val mediaTemplate: String,
        val sampleRate: Int,
        val channels: Int,
        val codecs: String,
        val segmentSamples: Long,
    ) : CastAudio
}

internal data class CastVideo(val layout: StreamLayout, val url: String)

internal data class CastSubtitle(val language: String, val url: String, val label: String)

internal object DashManifest {

    fun build(
        durationMs: Long,
        video: CastVideo?,
        audio: CastAudio?,
        subtitles: List<CastSubtitle> = emptyList(),
    ): String {
        require(video != null || audio != null) { "a manifest needs a video or an audio track" }
        val sets = buildList {
            video?.let { add(videoSet(it)) }
            audio?.let { add(audioSet(it)) }
            subtitles.forEachIndexed { i, sub -> add(subtitleSet(i, sub)) }
        }
        val mpd = "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" profiles=\"$PROFILES\" type=\"static\" " +
            "mediaPresentationDuration=\"${duration(durationMs)}\" minBufferTime=\"PT2S\">"
        val period = "<Period id=\"0\" start=\"PT0S\">"
        return listOf(XML_HEAD, mpd, period, sets.joinToString("\n"), "</Period>", "</MPD>", "").joinToString("\n")
    }

    private fun videoSet(video: CastVideo): String {
        val l = video.layout
        return """<AdaptationSet id="1" contentType="video" mimeType="${l.mimeType}" subsegmentAlignment="true" startWithSAP="1">
<Representation id="video" codecs="${l.codecs}" bandwidth="$VIDEO_BANDWIDTH" width="${l.width}" height="${l.height}">
<BaseURL>${xml(video.url)}</BaseURL>
<SegmentBase indexRange="${l.index.dash}"><Initialization range="${l.init.dash}"/></SegmentBase>
</Representation>
</AdaptationSet>"""
    }

    private fun audioSet(audio: CastAudio): String = when (audio) {
        is CastAudio.Passthrough -> {
            val l = audio.layout
            """<AdaptationSet id="2" contentType="audio" mimeType="${l.mimeType}" subsegmentAlignment="true">
<Representation id="audio" codecs="${l.codecs}" bandwidth="$AUDIO_BANDWIDTH" audioSamplingRate="${l.sampleRate ?: DEFAULT_RATE}">
<BaseURL>${xml(audio.url)}</BaseURL>
<SegmentBase indexRange="${l.index.dash}"><Initialization range="${l.init.dash}"/></SegmentBase>
</Representation>
</AdaptationSet>"""
        }
        is CastAudio.Processed -> {
            val template = "<SegmentTemplate timescale=\"${audio.sampleRate}\" duration=\"${audio.segmentSamples}\" " +
                "initialization=\"${xml(audio.initUrl)}\" media=\"${xml(audio.mediaTemplate)}\" startNumber=\"0\"/>"
            """<AdaptationSet id="2" contentType="audio" mimeType="audio/mp4" segmentAlignment="true" startWithSAP="1">
<Representation id="audio" codecs="${audio.codecs}" bandwidth="$AUDIO_BANDWIDTH" audioSamplingRate="${audio.sampleRate}">
<AudioChannelConfiguration schemeIdUri="$CHANNEL_SCHEME" value="${audio.channels}"/>
$template
</Representation>
</AdaptationSet>"""
        }
    }

    private fun subtitleSet(index: Int, sub: CastSubtitle): String =
        """<AdaptationSet id="${TEXT_SET_BASE + index}" contentType="text" mimeType="text/vtt" lang="${xml(
            sub.language
        )}">
<Label>${xml(sub.label)}</Label>
<Representation id="text$index" bandwidth="256"><BaseURL>${xml(sub.url)}</BaseURL></Representation>
</AdaptationSet>"""

    fun duration(ms: Long): String = "PT%d.%03dS".format(ms / Http.MILLIS, ms % Http.MILLIS)

    private fun xml(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")

    private const val XML_HEAD = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
    private const val PROFILES = "urn:mpeg:dash:profile:isoff-on-demand:2011,urn:mpeg:dash:profile:isoff-live:2011"
    private const val CHANNEL_SCHEME = "urn:mpeg:dash:23003:3:audio_channel_configuration:2011"
    private const val TEXT_SET_BASE = 10
    private const val VIDEO_BANDWIDTH = 4_000_000
    private const val AUDIO_BANDWIDTH = 160_000
    private const val DEFAULT_RATE = 48_000
}
