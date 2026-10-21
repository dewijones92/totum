package com.dewijones92.totum.playback.cast

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat

internal object SilentAac {

    fun frame(format: AacFormat): ByteArray {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val config = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, format.sampleRate, format.channels)
        config.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        config.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
        codec.configure(config, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        try {
            return encodeSilence(codec, format)
        } finally {
            codec.stop()
            codec.release()
        }
    }

    private fun encodeSilence(codec: MediaCodec, format: AacFormat): ByteArray {
        val info = MediaCodec.BufferInfo()
        val frameBytes = Fmp4Aac.SAMPLES_PER_FRAME * BYTES_PER_SAMPLE * format.channels
        val frames = ArrayList<ByteArray>()
        var fed = 0
        var tries = 0
        while (frames.size < WANTED && tries++ < MAX_TRIES) {
            if (fed < FEED) {
                val i = codec.dequeueInputBuffer(TIMEOUT_US)
                if (i >= 0) {
                    val buf = codec.getInputBuffer(i)!!
                    val n = minOf(buf.remaining(), frameBytes)
                    buf.put(ByteArray(n))
                    codec.queueInputBuffer(i, 0, n, fed.toLong() * FRAME_US, 0)
                    fed++
                }
            }
            val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (o >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    val out = codec.getOutputBuffer(o)!!
                    out.position(info.offset)
                    frames.add(ByteArray(info.size).also { out.get(it) })
                }
                codec.releaseOutputBuffer(o, false)
            }
        }
        check(frames.isNotEmpty()) { "the AAC encoder gave no silent frame" }
        return frames.last()
    }

    private const val BITRATE = 64_000
    private const val BYTES_PER_SAMPLE = 2
    private const val WANTED = 6
    private const val FEED = 12
    private const val MAX_TRIES = 500
    private const val TIMEOUT_US = 10_000L
    private const val FRAME_US = 21_333L
}
