package com.dewijones92.totum.playback

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ForwardingTimeline
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.WrappingMediaSource

@UnstableApi
internal class StartsAtSource(source: MediaSource, private val startUs: Long) : WrappingMediaSource(source) {

    override fun onChildSourceInfoRefreshed(newTimeline: Timeline) {
        refreshSourceInfo(StartsAt(newTimeline, startUs))
    }

    private class StartsAt(timeline: Timeline, private val startUs: Long) : ForwardingTimeline(timeline) {
        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
            super.getWindow(windowIndex, window, defaultPositionProjectionUs)
            val durationUs = window.durationUs
            if (durationUs == C.TIME_UNSET || startUs < durationUs) window.defaultPositionUs = startUs
            return window
        }
    }
}
