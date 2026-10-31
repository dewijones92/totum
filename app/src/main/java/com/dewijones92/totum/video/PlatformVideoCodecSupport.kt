package com.dewijones92.totum.video

import android.media.MediaCodecList
import com.dewijones92.totum.common.Diag
import android.media.MediaFormat as AndroidMediaFormat

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
