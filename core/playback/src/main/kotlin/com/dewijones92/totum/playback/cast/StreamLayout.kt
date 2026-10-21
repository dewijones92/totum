package com.dewijones92.totum.playback.cast

internal data class ByteSpan(val first: Long, val last: Long) {
    val dash: String get() = "$first-$last"
}

internal enum class Container(val video: String, val audio: String) {
    WEBM("video/webm", "audio/webm"),
    MP4("video/mp4", "audio/mp4"),
}

internal data class StreamLayout(
    val container: Container,
    val codecs: String,
    val init: ByteSpan,
    val index: ByteSpan,
    val width: Int? = null,
    val height: Int? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
) {
    val isVideo: Boolean get() = width != null
    val mimeType: String get() = if (isVideo) container.video else container.audio

    fun describe(): String = "$codecs ${width}x$height $container"
}

internal fun interface RangeReader {
    fun read(span: ByteSpan): ByteArray
}

internal class UnreadableLayout(message: String) : Exception(message)

internal object StreamLayouts {

    fun probe(reader: RangeReader): StreamLayout {
        val head = reader.read(ByteSpan(0, HEAD_BYTES - 1))
        return when {
            head.size >= MAGIC_BYTES && Ebml.id(head, 0).value == Ebml.EBML_HEADER -> WebmLayout.read(head, reader)
            head.size >= BOX_HEADER && Mp4Layout.isMp4(head) -> Mp4Layout.read(head)
            else -> throw UnreadableLayout("neither WebM nor MP4 (first bytes ${head.take(BOX_HEADER).hex()})")
        }
    }

    const val HEAD_BYTES: Long = 64 * 1024
    private const val MAGIC_BYTES = 4
    private const val BOX_HEADER = 8
}

internal fun List<Byte>.hex(): String = joinToString("") { "%02x".format(it) }
