package com.dewijones92.totum.video

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.playback.sabrStreamFor
import com.dewijones92.totum.sabr.SabrStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

object AndroidSabrRoute : SabrRoute {

    @OptIn(UnstableApi::class)
    override fun bytes(url: HttpUrl, item: PlayableItem, target: File): Flow<DownloadState>? =
        sabrStreamFor(url.value)?.let { sabrBytes(it, item, target) }

    /**
     * Copies a SABR stream to [target].
     *
     * Progress is reported by bytes written against the format's own length when it has one, so a
     * long fetch is not a spinner. The stream ends by returning nothing, exactly as it does for the
     * player.
     */
    @OptIn(UnstableApi::class)
    private fun sabrBytes(stream: SabrStream, item: PlayableItem, target: File): Flow<DownloadState> = flow {
        val total = stream.contentLength
        Diag.log(
            "download",
            "fetching \"${item.item.title}\" over SABR (${total ?: -1} bytes expected)",
        )
        emit(DownloadState.Downloading(0, total))
        var written = 0L
        target.parentFile?.mkdirs()
        target.outputStream().use { out ->
            while (true) {
                val chunk = stream.read(written)
                if (chunk.isEmpty()) break
                out.write(chunk)
                written += chunk.size
                emit(DownloadState.Downloading(written, total))
            }
        }
        // A file that exists is not a file that plays. An empty fetch, or one that stopped far
        // short of the length the format itself declared, is a FAILURE — recorded as one, so the
        // item is retried rather than sitting in Library as a download that plays nothing.
        val short = total != null && written < total * ENOUGH_PERCENT / PERCENT
        if (written == 0L || short) {
            target.delete()
            emit(
                DownloadState.Failed(
                    "the signed-in path returned ${written}B of ${total ?: -1}B",
                ),
            )
            return@flow
        }
        Diag.log("download", "SABR fetch of \"${item.item.title}\" finished at $written bytes")
        emit(DownloadState.Downloaded(target.absolutePath, audioOnly = true))
    }

    /**
     * How much of a declared length counts as the whole thing.
     *
     * Not 100%: SABR serves what it decides to and a final run can end a hair short, so an
     * exact test would fail good downloads. Low enough that a truncated one cannot pass.
     */
    private const val ENOUGH_PERCENT = 95
    private const val PERCENT = 100
}
