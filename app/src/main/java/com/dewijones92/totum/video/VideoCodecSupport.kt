package com.dewijones92.totum.video

import android.media.MediaCodecList
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.CastScreen
import android.media.MediaFormat as AndroidMediaFormat

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
 * [VideoCodecSupport] backed by the platform's decoder list. Answers per codec and
 * per size, because plenty of devices decode a codec at 1080p but not at 2160p.
 *
 * Unknown codecs are allowed through: refusing what we can't identify would hide
 * playable streams, and playback failure is recoverable while a missing quality is
 * invisible.
 */
public class PlatformVideoCodecSupport : VideoCodecSupport {

    private val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS)

    override fun canDecode(codec: String?, width: Int?, height: Int?): Boolean {
        val mime = codec.toMimeType() ?: return true
        val format = AndroidMediaFormat().apply {
            setString(AndroidMediaFormat.KEY_MIME, mime)
            if (width != null && height != null) {
                setInteger(AndroidMediaFormat.KEY_WIDTH, width)
                setInteger(AndroidMediaFormat.KEY_HEIGHT, height)
            }
        }
        // findDecoderForFormat honours the size keys, so this answers "at this
        // resolution" rather than merely "this codec exists".
        val decodable = runCatching { decoders.findDecoderForFormat(format) != null }.getOrDefault(true)
        // Only rejections are logged: they are rare, and they are exactly what you
        // want to see when a quality you expected isn't on offer.
        if (!decodable) {
            Diag.log("codec", "no decoder for $codec at ${width}x$height — quality withheld")
        }
        return decodable
    }

    override fun isHardware(codec: String?, width: Int?, height: Int?): Boolean {
        val name = decoderName(codec, width, height) ?: return false
        return runCatching {
            decoders.codecInfos.firstOrNull { it.name == name }?.isHardwareAccelerated == true
        }.getOrDefault(false)
    }

    private fun decoderName(codec: String?, width: Int?, height: Int?): String? {
        val mime = codec.toMimeType() ?: return null
        val format = AndroidMediaFormat().apply {
            setString(AndroidMediaFormat.KEY_MIME, mime)
            if (width != null && height != null) {
                setInteger(AndroidMediaFormat.KEY_WIDTH, width)
                setInteger(AndroidMediaFormat.KEY_HEIGHT, height)
            }
        }
        return runCatching { decoders.findDecoderForFormat(format) }.getOrNull()
    }

    /** yt-dlp's codec string → the Android MIME type; null when unrecognised. */
    private fun String?.toMimeType(): String? {
        val codec = this?.lowercase() ?: return null
        return when {
            codec.startsWith("avc") || codec.startsWith("h264") -> AndroidMediaFormat.MIMETYPE_VIDEO_AVC
            codec.startsWith("hev") || codec.startsWith("h265") -> AndroidMediaFormat.MIMETYPE_VIDEO_HEVC
            codec.startsWith("vp9") || codec.startsWith("vp09") -> AndroidMediaFormat.MIMETYPE_VIDEO_VP9
            codec.startsWith("vp8") || codec.startsWith("vp08") -> AndroidMediaFormat.MIMETYPE_VIDEO_VP8
            codec.startsWith("av01") || codec.startsWith("av1") -> AndroidMediaFormat.MIMETYPE_VIDEO_AV1
            else -> null
        }
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
