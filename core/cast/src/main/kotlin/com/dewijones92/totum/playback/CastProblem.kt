package com.dewijones92.totum.playback

import com.dewijones92.totum.domain.VideoCodecFamily

public enum class CastProblem { NO_ADDRESS, TV_NOT_FETCHING, VPN_IN_THE_WAY, SOUND_UNDECODABLE }

public enum class CastStage { PREPARING, REPACKAGING, TV_STARTING }

public data class CastCodecStep(val from: VideoCodecFamily, val to: VideoCodecFamily?) {
    public fun encode(): String = "${from.name}>${to?.name.orEmpty()}"

    public companion object {
        public fun decode(text: String?): CastCodecStep? {
            val (from, to) = text?.split('>', limit = 2)?.takeIf { it.size == 2 } ?: return null
            val first = VideoCodecFamily.entries.firstOrNull { it.name == from } ?: return null
            return CastCodecStep(first, VideoCodecFamily.entries.firstOrNull { it.name == to })
        }
    }
}
