package com.dewijones92.totum.data.home

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.ask.AskJson
import com.dewijones92.totum.data.backup.BackupItem
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

public object HomeUploadFrame {
    public const val MAX_HEADER_BYTES: Int = 64 * 1024
    private const val NEWLINE = '\n'.code

    public fun header(item: BackupItem): ByteArray =
        (AskJson.encodeToString(BackupItem.serializer(), item) + "\n").toByteArray(Charsets.UTF_8)

    public fun readHeader(input: InputStream): FramedItem? {
        val line = ByteArrayOutputStream()
        while (line.size() <= MAX_HEADER_BYTES) {
            val next = input.read()
            if (next < 0) return null
            if (next == NEWLINE) {
                val item = runCatching {
                    AskJson.decodeFromString(BackupItem.serializer(), line.toString(Charsets.UTF_8))
                }.onFailure { Diag.warn("home", "an upload's first line is not an item", it) }.getOrNull()
                    ?: return null
                return FramedItem(item, line.size() + 1L)
            }
            line.write(next)
        }
        return null
    }
}

public data class FramedItem(val item: BackupItem, val headerBytes: Long)

public class HomeUploadBody(
    private val item: BackupItem,
    private val file: File,
    private val stillWanted: () -> Boolean,
    private val onSent: (sent: Long, total: Long) -> Unit,
) : RequestBody() {
    private val header = HomeUploadFrame.header(item)

    override fun contentType(): okhttp3.MediaType = "application/octet-stream".toMediaType()

    override fun contentLength(): Long = header.size + file.length()

    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        sink.write(header)
        var sent = header.size.toLong()
        file.source().use { source ->
            while (true) {
                if (!stillWanted()) throw IOException("upload of \"${item.title}\" was cancelled")
                val read = source.read(sink.buffer, CHUNK_BYTES)
                if (read < 0) break
                sink.emitCompleteSegments()
                sent += read
                onSent(sent, total)
            }
        }
    }

    private companion object {
        const val CHUNK_BYTES = 64L * 1024
    }
}
