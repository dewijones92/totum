package com.dewijones92.totum.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.dewijones92.totum.common.Diag

@UnstableApi
internal class SeekLoadTrail : AnalyticsListener {

    private var seekedAtMs = Long.MIN_VALUE
    private var lines = 0

    override fun onPositionDiscontinuity(
        eventTime: AnalyticsListener.EventTime,
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason != Player.DISCONTINUITY_REASON_SEEK) return
        seekedAtMs = eventTime.realtimeMs
        lines = 0
        Diag.log(
            TAG,
            "seek ${oldPosition.positionMs}ms -> ${newPosition.positionMs}ms: the loads of the next " +
                "${WINDOW_MS / MS_PER_S} s follow (none means it played from the buffer)",
        )
    }

    override fun onLoadStarted(e: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData) =
        note(e, "started", info, data)

    override fun onLoadCanceled(e: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData) =
        note(e, "CANCELLED (its buffer is thrown away)", info, data)

    override fun onLoadCompleted(e: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData) =
        note(e, "completed ${info.bytesLoaded} bytes in ${info.loadDurationMs}ms", info, data)

    private fun note(e: AnalyticsListener.EventTime, what: String, info: LoadEventInfo, data: MediaLoadData) {
        val since = e.realtimeMs - seekedAtMs
        if (seekedAtMs == Long.MIN_VALUE || since > WINDOW_MS || lines >= MOST_LINES) return
        lines++
        Diag.log(
            TAG,
            "${since}ms after the seek: load $what, track type ${data.trackType}, media " +
                "${data.mediaStartTimeMs}..${data.mediaEndTimeMs}ms, from byte ${info.dataSpec.position} of " +
                "${info.uri.host}${info.uri.path?.substringAfterLast('/')?.let { "/$it" }.orEmpty()}",
        )
    }

    private companion object {
        const val TAG = "seek"
        const val WINDOW_MS = 10_000L
        const val MS_PER_S = 1_000L
        const val MOST_LINES = 12
    }
}
