package com.dewijones92.totum.playback.cast

import androidx.annotation.OptIn
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.playback.EXTRA_START_MS
import com.google.android.gms.cast.MediaQueueItem
import java.util.concurrent.ConcurrentHashMap

@OptIn(markerClass = [UnstableApi::class])
internal class TotumCastConverter(private val streams: CastStreams) : MediaItemConverter {

    private val plain = DefaultMediaItemConverter()
    private val originals = ConcurrentHashMap<String, MediaItem>()

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        originals[mediaItem.mediaId] = mediaItem
        val url = runCatching { streams.urlFor(mediaItem) }
            .onFailure {
                Diag.warn(
                    "cast",
                    "${mediaItem.mediaId}: could not set up a processed stream; given as it is",
                    it
                )
            }
            .getOrNull()
        val sent = if (url != null) {
            mediaItem.buildUpon().setUri(url).setMimeType(MimeTypes.APPLICATION_MPD).build()
        } else {
            mediaItem.takeIf { it.localConfiguration?.mimeType != null }
                ?: mediaItem.buildUpon().setMimeType(MimeTypes.VIDEO_MP4).build()
        }
        val queued = plain.toMediaQueueItem(sent)
        val startMs = mediaItem.requestMetadata.extras?.getLong(EXTRA_START_MS) ?: 0L
        if (startMs <= 0) return queued
        Diag.log("cast", "${mediaItem.mediaId} starts on the TV at ${startMs}ms")
        return MediaQueueItem.Builder(queued).setStartTime(startMs.toDouble() / Http.MILLIS).build()
    }

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        val converted = plain.toMediaItem(mediaQueueItem)
        originals[converted.mediaId]?.let { return it }
        if (converted.mediaId.isNotBlank()) return converted
        val stand = "cast:${mediaQueueItem.media?.contentId?.hashCode() ?: mediaQueueItem.itemId}"
        Diag.warn("cast", "the receiver reported an item Totum did not send (no id); calling it $stand")
        return converted.buildUpon().setMediaId(stand).build()
    }
}
