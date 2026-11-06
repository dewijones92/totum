package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.backup.toPlayable
import com.dewijones92.totum.data.download.Adopted
import com.dewijones92.totum.data.download.DefaultDownloadManager
import com.dewijones92.totum.data.home.HomeUploadFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

internal class UploadReceiver(
    private val manager: DefaultDownloadManager,
    private val limitBytes: () -> Long,
    private val freeBytes: () -> Long,
) {
    suspend fun receive(id: String, audioOnly: Boolean, declaredBytes: Long, body: InputStream): Received {
        val framed = HomeUploadFrame.readHeader(body)
            ?: return refuse(BAD_REQUEST, "❌ The upload did not start with the item it carries", id)
        if (framed.item.itemId != id) {
            return refuse(BAD_REQUEST, "❌ The upload carries ${framed.item.itemId}, not $id", id)
        }
        val item = framed.item.toPlayable() ?: return refuse(UNPROCESSABLE, "❌ That item can't be stored here", id)
        val fileBytes = declaredBytes - framed.headerBytes
        val limit = limitBytes()
        if (fileBytes <= 0) return refuse(BAD_REQUEST, "❌ The upload carried no file", id)
        if (fileBytes > limit) {
            val sent = "${fileBytes / MB} MB sent, room for ${limit / MB} MB (${freeBytes() / GB} GB free)"
            return refuse(TOO_LARGE, "❌ Too big for the Pi: $sent", id)
        }
        val target = manager.incomingFile(item.item.id)
        Diag.log(
            "home",
            "\"${item.item.title}\": receiving ${fileBytes / MB} MB from the phone (limit ${limit / MB} MB)"
        )
        val copied = withContext(Dispatchers.IO) { copyCapped(body, target, fileBytes) }
        if (copied != fileBytes) {
            target.delete()
            return refuse(BAD_REQUEST, "❌ The upload stopped after $copied of $fileBytes bytes", id)
        }
        return when (manager.adopt(item, target, audioOnly)) {
            Adopted.STORED -> Received.Stored(fileBytes)
            Adopted.BUSY -> refuse(CONFLICT, "❌ The Pi is already fetching that one", id)
            Adopted.FULLER_COPY_KEPT -> refuse(CONFLICT, "❌ The Pi already has the full copy", id)
        }
    }

    private fun refuse(code: Int, why: String, id: String): Received.Refused {
        Diag.warn("home", "upload of $id refused ($code): $why")
        return Received.Refused(code, why)
    }

    private fun copyCapped(input: InputStream, target: File, bytes: Long): Long {
        var copied = 0L
        target.outputStream().buffered().use { out ->
            val buffer = ByteArray(COPY_BUFFER)
            while (copied < bytes) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), bytes - copied).toInt())
                if (read < 0) break
                out.write(buffer, 0, read)
                copied += read
            }
        }
        return copied
    }

    private companion object {
        const val GB = 1_000_000_000L
        const val MB = 1_000_000L
        const val COPY_BUFFER = 64 * 1024
        const val BAD_REQUEST = 400
        const val CONFLICT = 409
        const val TOO_LARGE = 413
        const val UNPROCESSABLE = 422
    }
}
