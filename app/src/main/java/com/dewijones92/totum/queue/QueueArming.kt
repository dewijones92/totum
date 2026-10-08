package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.PlayRoute
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SkipSegment
import com.dewijones92.totum.playback.PlaybackController
import com.dewijones92.totum.video.VideoPlaybackLauncher

internal class QueueArming(private val controller: PlaybackController, private val launcher: VideoPlaybackLauncher) {

    suspend fun arm(
        queued: PlayableItem,
        route: PlayRoute,
        skipSegments: List<SkipSegment>,
        pictureRefused: Boolean,
        allowStream: Boolean,
    ): Boolean {
        val id = queued.item.id.value
        if (!allowStream && !route.isFromDisk()) {
            Diag.log("gapless", "not arming $id: it would stream, and this network is metered")
            return false
        }
        Diag.log("gapless", "arming $id -> ${route.describe()}")
        return when (route) {
            is PlayRoute.VideoFile -> {
                controller.armNext(route.playable.item, skipSegments = skipSegments, localPath = route.path)
                true
            }
            is PlayRoute.AudioFile -> {
                controller.armNext(
                    route.playable.item,
                    queued.pillar,
                    skipSegments = skipSegments,
                    localPath = route.path
                )
                true
            }
            is PlayRoute.VideoStream -> launcher.arm(route.playable.item, route.watchUrl, audioOnly = pictureRefused)
            is PlayRoute.AudioStream -> {
                controller.armNext(route.playable.item, queued.pillar)
                true
            }
            is PlayRoute.Refused -> false
        }
    }
}

internal fun PlayRoute.isFromDisk(): Boolean = this is PlayRoute.VideoFile || this is PlayRoute.AudioFile
