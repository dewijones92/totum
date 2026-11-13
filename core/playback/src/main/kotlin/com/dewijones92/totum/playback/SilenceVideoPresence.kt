package com.dewijones92.totum.playback

import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener

@UnstableApi
internal class SilenceVideoPresence : AnalyticsListener {

    @Volatile
    var selected: Boolean = false
        private set

    @Volatile
    var droppedFrames: Long = 0L
        private set

    @Volatile
    var cutOwedUs: Long = 0L
        private set

    fun cutOwed(us: Long) {
        cutOwedUs = us
    }

    override fun onTracksChanged(eventTime: AnalyticsListener.EventTime, tracks: Tracks) {
        selected = tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        this.droppedFrames += droppedFrames
    }
}
