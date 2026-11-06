package com.dewijones92.totum.playback.cast

import java.util.concurrent.ConcurrentHashMap

public class TvVideos(private val tvs: CastTvPort) {
    private val refusals = ConcurrentHashMap<String, String>()
    private val sentHeights = ConcurrentHashMap<String, Int>()
    private val fetchingHeights = ConcurrentHashMap<String, Int>()

    public fun sent(mediaId: String, height: Int?) {
        refusals.remove(mediaId)
        height?.let { sentHeights[mediaId] = it }
        publish()
    }

    public fun fetching(mediaId: String, height: Int) {
        fetchingHeights[mediaId] = height
        publish()
    }

    public fun refused(mediaId: String, what: String) {
        refusals[mediaId] = what
        sentHeights.remove(mediaId)
        publish()
    }

    public fun forget(mediaId: String) {
        val hadRefusal = refusals.remove(mediaId) != null
        val hadFetch = fetchingHeights.remove(mediaId) != null
        if (sentHeights.remove(mediaId) != null || hadRefusal || hadFetch) publish()
    }

    public fun clear() {
        refusals.clear()
        sentHeights.clear()
        fetchingHeights.clear()
        publish()
    }

    private fun publish() = tvs.videos(refusals.toMap(), sentHeights.toMap(), fetchingHeights.toMap())
}
