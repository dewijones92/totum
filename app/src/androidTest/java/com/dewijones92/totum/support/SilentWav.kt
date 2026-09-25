package com.dewijones92.totum.support

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * A silent WAV, generated rather than shipped.
 *
 * The repository carries no audio, and a generated file is also **copyright-free by construction**
 * — which matters for the torrent tests, where the fixtures name public-domain films and no real
 * media may be involved.
 *
 * Silent because these tests assert positions and sources, never sound. One caveat worth knowing:
 * with skip-silence on, sample removal deletes the entire file and playback never starts, which
 * reads as "the item never played". Every test using this turns skip-silence off explicitly.
 */
object SilentWav {

    fun bytes(seconds: Int): ByteArray {
        val samples = SAMPLE_RATE * seconds
        val out = ByteArrayOutputStream()
        out.write(wavHeader(SAMPLE_RATE, BITS_PER_SAMPLE, samples))
        out.write(ByteArray(samples) { SILENCE })
        return out.toByteArray()
    }

    private const val SAMPLE_RATE = 8_000
    private const val BITS_PER_SAMPLE = 8
    private const val SILENCE: Byte = -128
}

object GappedWav {

    fun bytes(bursts: Int, toneMs: Int, gapMs: Int): ByteArray {
        val toneSamples = SAMPLE_RATE * toneMs / MILLIS
        val gapSamples = SAMPLE_RATE * gapMs / MILLIS
        val samples = bursts * (toneSamples + gapSamples)
        val body = ByteBuffer.allocate(samples * BYTES_PER_SAMPLE).order(ByteOrder.LITTLE_ENDIAN)
        repeat(bursts) {
            for (i in 0 until toneSamples) {
                body.putShort((AMPLITUDE * sin(2 * PI * TONE_HZ * i / SAMPLE_RATE)).toInt().toShort())
            }
            repeat(gapSamples) { body.putShort(0) }
        }
        val out = ByteArrayOutputStream()
        out.write(wavHeader(SAMPLE_RATE, BYTES_PER_SAMPLE * BITS_PER_BYTE, body.capacity()))
        out.write(body.array())
        return out.toByteArray()
    }

    private const val SAMPLE_RATE = 44_100
    private const val BYTES_PER_SAMPLE = 2
    private const val BITS_PER_BYTE = 8
    private const val MILLIS = 1_000
    private const val TONE_HZ = 440.0
    private const val AMPLITUDE = 9_000.0
}

private fun wavHeader(sampleRate: Int, bitsPerSample: Int, dataBytes: Int): ByteArray {
    val bytesPerSample = bitsPerSample / BITS_IN_A_BYTE
    val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray())
    header.putInt(WAV_HEADER_BYTES - RIFF_PREAMBLE + dataBytes)
    header.put("WAVEfmt ".toByteArray())
    header.putInt(FMT_CHUNK_BYTES)
    header.putShort(PCM_FORMAT)
    header.putShort(MONO)
    header.putInt(sampleRate)
    header.putInt(sampleRate * bytesPerSample)
    header.putShort(bytesPerSample.toShort())
    header.putShort(bitsPerSample.toShort())
    header.put("data".toByteArray())
    header.putInt(dataBytes)
    return header.array()
}

private const val WAV_HEADER_BYTES = 44
private const val RIFF_PREAMBLE = 8
private const val FMT_CHUNK_BYTES = 16
private const val PCM_FORMAT: Short = 1
private const val MONO: Short = 1
private const val BITS_IN_A_BYTE = 8
