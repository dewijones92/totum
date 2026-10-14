package com.dewijones92.totum.playback

import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.dewijones92.totum.common.Diag

@UnstableApi
internal class SubtitleTrail : AnalyticsListener {

    private var selected: String? = null
    private var cueShownFor: String? = null

    override fun onTracksChanged(eventTime: AnalyticsListener.EventTime, tracks: Tracks) {
        val text = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val chosen = text.firstNotNullOfOrNull { group ->
            (0 until group.length).firstOrNull {
                group.isTrackSelected(
                    it
                )
            }?.let { group.getTrackFormat(it).language ?: "unnamed" }
        }
        val now = "${chosen ?: "none"} of ${text.size} offered"
        if (now == selected) return
        selected = now
        cueShownFor = null
        Diag.log(TAG, "text track selected: $now")
    }

    override fun onLoadCompleted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
    ) {
        if (mediaLoadData.trackType != C.TRACK_TYPE_TEXT && "/api/timedtext" !in loadEventInfo.uri.toString()) return
        Diag.log(
            TAG,
            "loaded ${captionLanguage(loadEventInfo.uri.toString())} captions: ${loadEventInfo.bytesLoaded} bytes " +
                "in ${loadEventInfo.loadDurationMs}ms",
        )
    }

    override fun onCues(eventTime: AnalyticsListener.EventTime, cueGroup: CueGroup) {
        val first = cueGroup.cues.firstOrNull()?.text?.toString() ?: return
        if (cueShownFor == selected) return
        cueShownFor = selected
        Diag.log(TAG, "first caption on screen at ${eventTime.eventPlaybackPositionMs}ms (${first.length} chars)")
    }

    private companion object {
        const val TAG = "subtitles"
    }
}
