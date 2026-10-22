package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.domain.CastScreen
import com.dewijones92.totum.domain.VideoCodecFamily

internal data class CastRung(val url: String, val height: Int, val codec: String?) {
    val label: String get() = "${VideoCodecFamily.of(codec)?.name ?: codec ?: "?"} ${height}p"
}

internal object CastLadder {

    private const val MAX_RUNGS = 3

    fun choose(current: String, offered: List<CastRung>, screen: CastScreen?): List<CastRung> {
        val showable = offered.distinctBy { it.url }.filter { screen?.canDecode(it.codec, it.height) ?: true }
        val playing = showable.firstOrNull { it.url == current }
        val playingFamily = VideoCodecFamily.of(playing?.codec)
        val best = showable.maxWithOrNull(
            compareBy<CastRung> { it.height }.thenBy { VideoCodecFamily.of(it.codec) == playingFamily },
        ) ?: return emptyList()
        val family = showable.filter { VideoCodecFamily.of(it.codec) == VideoCodecFamily.of(best.codec) }
        val flowing = playing?.takeIf { it in family && it != best }
        val below = family.filter { it.height < best.height && it != flowing }.sortedByDescending { it.height }
        return (listOfNotNull(best, flowing) + below).take(MAX_RUNGS).sortedByDescending { it.height }
    }
}
