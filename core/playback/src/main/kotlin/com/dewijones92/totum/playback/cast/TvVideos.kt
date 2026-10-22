package com.dewijones92.totum.playback.cast

import java.util.concurrent.ConcurrentHashMap

internal class TvVideos(private val tvs: CastTvPort) {
    private val refusals = ConcurrentHashMap<String, String>()
    private val sentHeights = ConcurrentHashMap<String, Int>()

    fun sent(mediaId: String, height: Int?) {
        refusals.remove(mediaId)
        height?.let { sentHeights[mediaId] = it }
        publish()
    }

    fun refused(mediaId: String, what: String) {
        refusals[mediaId] = what
        sentHeights.remove(mediaId)
        publish()
    }

    fun forget(mediaId: String) {
        val hadRefusal = refusals.remove(mediaId) != null
        if (sentHeights.remove(mediaId) != null || hadRefusal) publish()
    }

    fun clear() {
        refusals.clear()
        sentHeights.clear()
        publish()
    }

    private fun publish() = tvs.videos(refusals.toMap(), sentHeights.toMap())
}
