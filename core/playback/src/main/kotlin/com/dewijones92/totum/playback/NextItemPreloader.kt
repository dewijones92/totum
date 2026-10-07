package com.dewijones92.totum.playback

import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.Vitals

/**
 * Holds the first [BufferBudget.PRELOAD_MS] of what is coming next, so a track change is not a wait.
 *
 * Dewi, 2026-08-02: *"just 30 seconds of future to be loaded right??"* — and Wi-Fi only, which the
 * app decides before it ever nominates anything. Nothing here spends data on its own initiative.
 *
 * `ExoPlayer.PreloadConfiguration` cannot do this job: it preloads the next item in the PLAYER'S
 * PLAYLIST, and the queue plays one item at a time because it owns advancing. This holds sources
 * outside any playlist, which is the shape that fits.
 *
 * Its own class because holding bytes for later is a separate job from playing them, and because
 * that separation is what makes [releaseIfPlaying] obvious — it was missing while this lived
 * inside the session callback, so a preloaded item's bytes were held twice on the same heap.
 */
@UnstableApi
internal class NextItemPreloader(private val manager: DefaultPreloadManager) {

    /** What is currently held, so nominating something else releases it. */
    private var held: MediaItem? = null

    /** Which item it is held FOR — the identity [releaseIfPlaying] matches on, not the URL. */
    private var heldFor: String? = null

    private var heldSince = 0L
    private var handedOver = false
    private var adding = false

    @OptIn(UnstableApi::class)
    fun hold(itemId: String, uri: String, audioUri: String? = null) {
        if (heldFor == itemId && held?.uriOrNull() == uri && held.audioUriOrNull() == audioUri) return
        held?.let { manager.remove(it) }
        // The id rides on the MediaItem so the held copy carries its own identity: the URI cannot,
        // because it is re-signed on every resolve.
        val item = MediaItem.Builder()
            .setMediaId(itemId)
            .setUri(uri)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setExtras(audioUri?.let { Bundle().apply { putString(EXTRA_AUDIO_URL, it) } })
                    .build(),
            )
            .build()
        held = item
        heldFor = itemId
        heldSince = SystemClock.elapsedRealtime()
        handedOver = false
        adding = true
        try {
            manager.add(item, 0)
        } finally {
            adding = false
        }
        manager.invalidate()
        Diag.log(
            "preload",
            "holding the first ${BufferBudget.PRELOAD_MS}ms of $itemId — ${uri.forLog()}" +
                (audioUri?.let { " + audio ${it.forLog()}" } ?: ""),
        )
    }

    @OptIn(UnstableApi::class)
    fun takeFor(mediaItem: MediaItem): MediaSource? {
        if (adding) return null
        val holding = held ?: return null
        if (mediaItem.mediaId != heldFor) return null
        if (mediaItem.uriOrNull() != holding.uriOrNull() || mediaItem.audioUriOrNull() != holding.audioUriOrNull()) {
            Diag.log(
                "preload",
                "held $heldFor but the player asked for a different stream of it, so it loads afresh — held " +
                    "${holding.uriOrNull()?.forLog()}, asked ${mediaItem.uriOrNull()?.forLog()}",
            )
            return null
        }
        val source = manager.getMediaSource(holding) ?: return null
        if (!source.canUpdateMediaItem(mediaItem)) {
            Diag.log("preload", "held $heldFor but its source cannot take the playing item, so it loads afresh")
            return null
        }
        source.updateMediaItem(mediaItem)
        handedOver = true
        Vitals.add("playback.preloadsUsed")
        Diag.log(
            "preload",
            "playing $heldFor from the source held for ${SystemClock.elapsedRealtime() - heldSince}ms " +
                "(its first ${BufferBudget.PRELOAD_MS}ms were loading ahead)",
        )
        return source
    }

    /**
     * Drops the held copy of whatever just started playing.
     *
     * Once an item is playing the player loads it itself, so anything still held for it is a SECOND
     * copy of the same bytes on the same heap. It used to be held until the next nomination — which,
     * for the last item in a queue, never comes. That doubling was live in 0.1.346, the build that
     * died of `OutOfMemoryError` (2026-08-06).
     */
    @OptIn(UnstableApi::class)
    fun releaseIfPlaying(item: MediaItem?) {
        val holding = held ?: return
        val playing = item?.mediaId
        if (playing == null || playing != heldFor) {
            // Said out loud: this is a silent no-op otherwise, and a silent no-op here means the
            // bytes stay held. The instrumented test caught exactly that.
            Diag.log("preload", "still holding $heldFor — what started is ${playing ?: "nothing"}")
            return
        }
        // A wasted nomination, said plainly. The bytes are correctly released either way, but a
        // preload of a stream the player then did not use is data spent for nothing, and the only
        // way to know it is happening in the wild is to count it. Report 0.1.359 had it on every
        // video: itag 18 held, itag 399 played.
        if (!handedOver) {
            Diag.warn(
                "preload",
                "held $playing but the player loaded it afresh, so the preload was wasted — held " +
                    "${holding.uriOrNull()?.forLog()}, playing ${item.uriOrNull()?.forLog()}",
            )
            Vitals.add("playback.preloadsWasted")
        }
        manager.remove(holding)
        held = null
        heldFor = null
        Diag.log(
            "preload",
            if (handedOver) "the player owns $playing's preloaded source now" else "released the held copy of $playing",
        )
    }

    /**
     * Either source, because a MediaItem crossing the session boundary does not always carry its
     * localConfiguration — requestMetadata is what survives.
     */
    private fun MediaItem.uriOrNull(): String? =
        (localConfiguration?.uri ?: requestMetadata.mediaUri)?.toString()

    private fun MediaItem?.audioUriOrNull(): String? = this?.requestMetadata?.extras?.getString(EXTRA_AUDIO_URL)
}
