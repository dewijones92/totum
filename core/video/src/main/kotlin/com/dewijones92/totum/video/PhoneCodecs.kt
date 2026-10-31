package com.dewijones92.totum.video

import com.dewijones92.totum.data.home.PhoneDecoder

class PhoneCodecs(private val decoders: List<PhoneDecoder>) : VideoCodecSupport {

    override fun canDecode(codec: String?, width: Int?, height: Int?): Boolean {
        if (decoders.isEmpty()) return true
        val family = familyOf(codec) ?: return true
        return decoders.any { it.codec == family && (height ?: 0) <= it.maxHeight }
    }

    override fun isHardware(codec: String?, width: Int?, height: Int?): Boolean {
        val family = familyOf(codec) ?: return false
        return decoders.any { it.codec == family && it.hardware && (height ?: 0) <= it.maxHeight }
    }

    override val screen: String
        get() = "the phone that asked (${decoders.joinToString { "${it.codec}≤${it.maxHeight}p" }})"

    companion object {
        val FAMILIES: List<VideoCodecFamily> =
            listOf(VideoCodecFamily.AVC, VideoCodecFamily.HEVC, VideoCodecFamily.VP9, VideoCodecFamily.AV1)

        fun familyOf(codec: String?): String? = VideoCodecFamily.of(codec)?.name?.lowercase()
    }
}
