package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.PlayRoute
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SkipSegment
import com.dewijones92.totum.domain.listensWithThePictureOff
import com.dewijones92.totum.playback.PlaybackController
import com.dewijones92.totum.video.VideoPlaybackLauncher

internal class QueueArming(private val controller: PlaybackController, private val launcher: VideoPlaybackLauncher) {

    suspend fun arm(
        queued: PlayableItem,
        route: PlayRoute,
        skipSegments: List<SkipSegment>,
        sound: ArmedSound,
        allowStream: Boolean,
    ): Boolean {
        val id = queued.item.id.value
        if (!allowStream && !route.isFromDisk()) {
            Diag.log("gapless", "not arming $id: it would stream, and this network is metered")
            return false
        }
        Diag.log("gapless", "arming $id -> ${route.describe()}")
        return when (route) {
            is PlayRoute.File -> {
                controller.armNext(
                    route.playable.item,
                    queued.pillar,
                    skipSegments = skipSegments,
                    localPath = route.path,
                    audioOnly = route.audioOnly,
                )
                true
            }
            is PlayRoute.VideoStream -> launcher.arm(
                route.playable.item,
                route.watchUrl,
                audioOnly = sound.pictureRefused
            )
            is PlayRoute.AudioStream -> {
                val withoutPicture = queued.listensWithThePictureOff(route, sound.listening)
                Diag.log(
                    "gapless",
                    "$id streams directly [listening=${sound.listening} pictureTrackOff=$withoutPicture]"
                )
                controller.armNext(route.playable.item, queued.pillar, audioOnly = withoutPicture)
                true
            }
            is PlayRoute.Refused -> false
        }
    }
}

internal data class ArmedSound(val pictureRefused: Boolean, val listening: Boolean)

internal fun PlayRoute.isFromDisk(): Boolean = this is PlayRoute.VideoFile || this is PlayRoute.AudioFile
