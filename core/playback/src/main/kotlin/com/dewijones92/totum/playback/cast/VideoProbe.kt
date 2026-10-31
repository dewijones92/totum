package com.dewijones92.totum.playback.cast

internal sealed interface FoundVideo {
    data class Remuxed(val video: RemuxedVideo) : FoundVideo
    data class Fragmented(val layout: StreamLayout) : FoundVideo
    data class NoPicture(val why: String) : FoundVideo
}

internal object VideoProbe {

    fun of(read: RangeReader, label: String): FoundVideo {
        val head = read.read(ByteSpan(0, StreamLayouts.HEAD_BYTES - 1))
        return when {
            Ebml.isEbml(head) ->
                MkvVideo.probe(head, read)?.let(FoundVideo::Remuxed)
                    ?: FoundVideo.NoPicture("an MKV with no video track")
            Mp4Layout.isMp4(head) -> when (val choice = Mp4Video.choose(head, read, label)) {
                is Mp4Video.Companion.Choice.Fragmented -> FoundVideo.Fragmented(choice.layout)
                is Mp4Video.Companion.Choice.Remuxed -> FoundVideo.Remuxed(choice.video)
                Mp4Video.Companion.Choice.NoVideo -> FoundVideo.NoPicture("an MP4 with no video track")
            }
            else -> FoundVideo.NoPicture("audio only, or not a WebM, MP4 or MKV")
        }
    }
}
