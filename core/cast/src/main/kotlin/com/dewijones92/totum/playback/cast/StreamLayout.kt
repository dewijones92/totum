package com.dewijones92.totum.playback.cast

public data class ByteSpan(val first: Long, val last: Long) {
    val dash: String get() = "$first-$last"
}

public enum class Container(public val video: String, public val audio: String) {
    WEBM("video/webm", "audio/webm"),
    MP4("video/mp4", "audio/mp4"),
}

public data class StreamLayout(
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

    public fun describe(): String = "$codecs ${width}x$height $container"
}

public fun interface RangeReader {
    public fun read(span: ByteSpan): ByteArray
}

public class UnreadableLayout(message: String, cause: Throwable? = null) : Exception(message, cause)

public object StreamLayouts {

    public fun probe(reader: RangeReader): StreamLayout {
        val head = reader.read(ByteSpan(0, HEAD_BYTES - 1))
        return when {
            head.size >= BOX_HEADER && Mp4Layout.isMp4(head) -> Mp4Layout.read(head)
            head.size >= MAGIC_BYTES && head[0] != 0.toByte() && Ebml.id(head, 0).value == Ebml.EBML_HEADER ->
                WebmLayout.read(head, reader)
            else -> throw UnreadableLayout("neither WebM nor MP4 (first bytes ${head.take(BOX_HEADER).hex()})")
        }
    }

    internal const val HEAD_BYTES: Long = 64 * 1024
    private const val MAGIC_BYTES = 4
    private const val BOX_HEADER = 8
}

public fun List<Byte>.hex(): String = joinToString("") { "%02x".format(it) }

public fun ByteArray.contains(needle: ByteArray): Boolean =
    (0..size - needle.size).any { i -> needle.indices.all { this[i + it] == needle[it] } }
