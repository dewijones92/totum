package com.dewijones92.totum.video

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.home.PhoneDecoder
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
    fun snapshot(): List<PhoneDecoder> = PhoneCodecs.FAMILIES.mapNotNull { family ->
        val codec = family.name.lowercase()
        val probe = PROBE_CODECS.getValue(family)
        val height = PROBE_HEIGHTS.firstOrNull { canDecode(probe, it * WIDE / TALL, it) } ?: return@mapNotNull null
        PhoneDecoder(codec, height, isHardware(probe, height * WIDE / TALL, height))
    }.also { Diag.log("codec", "decoders told to home: ${it.joinToString { d -> "${d.codec}≤${d.maxHeight}p" }}") }

    private fun String?.toMimeType(): String? = when (VideoCodecFamily.of(this)) {
        VideoCodecFamily.AVC -> AndroidMediaFormat.MIMETYPE_VIDEO_AVC
        VideoCodecFamily.HEVC -> AndroidMediaFormat.MIMETYPE_VIDEO_HEVC
        VideoCodecFamily.VP9 -> AndroidMediaFormat.MIMETYPE_VIDEO_VP9
        VideoCodecFamily.VP8 -> AndroidMediaFormat.MIMETYPE_VIDEO_VP8
        VideoCodecFamily.AV1 -> AndroidMediaFormat.MIMETYPE_VIDEO_AV1
        null -> null
    }
}

fun decodesTenBitHevc(): Boolean = runCatching {
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { !it.isEncoder && AndroidMediaFormat.MIMETYPE_VIDEO_HEVC in it.supportedTypes }
        .any { info ->
            info.getCapabilitiesForType(AndroidMediaFormat.MIMETYPE_VIDEO_HEVC).profileLevels
                .any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 }
        }
}.getOrDefault(false).also { Diag.log("codec", "10-bit HEVC decoder on this phone: $it") }

private val PROBE_HEIGHTS = listOf(2160, 1440, 1080, 720, 480)
private const val WIDE = 16
private const val TALL = 9
private val PROBE_CODECS = mapOf(
    VideoCodecFamily.AVC to "avc1",
    VideoCodecFamily.HEVC to "hev1",
    VideoCodecFamily.VP9 to "vp09",
    VideoCodecFamily.AV1 to "av01",
)
