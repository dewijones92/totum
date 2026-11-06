package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.domain.QualityLabel
import java.util.concurrent.ConcurrentHashMap

public class TvVideos(private val tvs: CastTvPort) {
    private val refusals = ConcurrentHashMap<String, String>()
    private val sent = ConcurrentHashMap<String, QualityLabel>()
    private val fetchingHeights = ConcurrentHashMap<String, Int>()
    private val droppedFrom = ConcurrentHashMap<String, Int>()

    public fun sent(mediaId: String, label: QualityLabel?) {
        refusals.remove(mediaId)
        label?.let { sent[mediaId] = it }
        publish()
    }

    public fun fetching(mediaId: String, height: Int) {
        fetchingHeights[mediaId] = height
        publish()
    }

    public fun refused(mediaId: String, what: String) {
        refusals[mediaId] = what
        sent.remove(mediaId)
        publish()
    }

    public fun steppedDown(mediaId: String, fromHeight: Int) {
        droppedFrom[mediaId] = fromHeight
        publish()
    }

    public fun forget(mediaId: String) {
        val removed = listOf(
            refusals.remove(mediaId),
            fetchingHeights.remove(mediaId),
            droppedFrom.remove(mediaId),
            sent.remove(mediaId),
        )
        if (removed.any { it != null }) publish()
    }

    public fun clear() {
        refusals.clear()
        sent.clear()
        fetchingHeights.clear()
        droppedFrom.clear()
        publish()
    }

    private fun publish() = tvs.videos(refusals.toMap(), sent.toMap(), fetchingHeights.toMap(), droppedFrom.toMap())
}
