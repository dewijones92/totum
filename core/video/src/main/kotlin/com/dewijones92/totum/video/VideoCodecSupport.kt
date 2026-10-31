package com.dewijones92.totum.video

import com.dewijones92.totum.domain.CastScreen

/**
 * Whether this device can actually decode a given video stream.
 *
 * This exists because of a real failure: above 1080p, YouTube publishes only
 * video-only VP9/AV1 streams, and the quality ladder used to offer whichever
 * format happened to come first at each height. On a device without a decoder for
 * that codec (or without one that reaches those dimensions), selecting the quality
 * simply failed to play. Offering a quality the device cannot decode is the bug;
 * this is how the ladder finds out.
 */
public fun interface VideoCodecSupport {

    /** True if a decoder exists for [codec] at [width] x [height]. */
    public fun canDecode(codec: String?, width: Int?, height: Int?): Boolean

    /**
     * Whether that decoder is hardware. Defaults to false, which keeps the conservative
     * codec order for anything that cannot answer — a *software* AV1 decode is far worse
     * than hardware AVC, so preferring the efficient codec is only right when the silicon
     * is doing the work.
     */
    public fun isHardware(codec: String?, width: Int?, height: Int?): Boolean = false

    public val screen: String get() = "phone"

    public companion object {
        /** Assumes everything decodes — for tests and previews. */
        public val Permissive: VideoCodecSupport = VideoCodecSupport { _, _, _ -> true }
    }
}

/**
 * Preference between codecs decodable at the same height.
 *
 * **Hardware first, then fewest bytes.** This used to prefer AVC unconditionally, on the
 * reasoning that it is the most likely to be hardware-accelerated. That cost real
 * playback: for one 1080p video the AVC stream is 1328 kbps against AV1's 853 — 36% more
 * bytes for the same picture — and with YouTube throttling the connection to near
 * playback rate, those bytes were the difference between playing and stalling.
 *
 * When the device decodes it in hardware, the efficient codec wins. When it does not, the
 * old order stands, because software AV1 is worse than hardware AVC in every way.
 */
internal fun String?.codecPreference(hardware: Boolean = false): Int {
    val codec = this?.lowercase() ?: return UNKNOWN
    val rank = when {
        codec.startsWith("av01") || codec.startsWith("av1") -> if (hardware) FIRST else LAST
        codec.startsWith("vp9") || codec.startsWith("vp09") -> SECOND
        codec.startsWith("hev") || codec.startsWith("h265") -> THIRD
        codec.startsWith("avc") || codec.startsWith("h264") -> if (hardware) LAST else FIRST
        else -> return UNKNOWN
    }
    // Anything in hardware beats anything that is not, whatever the codec.
    return if (hardware) rank else SOFTWARE_PENALTY + rank
}

private const val FIRST = 0
private const val SECOND = 1
private const val THIRD = 2
private const val LAST = 3

/** Anything in hardware beats anything that is not, whatever the codec. */
private const val SOFTWARE_PENALTY = 10
private const val UNKNOWN = Int.MAX_VALUE

public class CastAwareCodecSupport(
    private val phone: VideoCodecSupport,
    private val tv: () -> CastScreen?,
) : VideoCodecSupport {
    override fun canDecode(codec: String?, width: Int?, height: Int?): Boolean =
        tv()?.canDecode(codec, height) ?: phone.canDecode(codec, width, height)

    override fun isHardware(codec: String?, width: Int?, height: Int?): Boolean =
        tv()?.canDecode(codec, height) ?: phone.isHardware(codec, width, height)

    override val screen: String get() = tv()?.let { "tv:${it.model}" } ?: phone.screen
}
