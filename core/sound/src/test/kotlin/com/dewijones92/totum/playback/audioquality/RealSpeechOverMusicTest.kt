package com.dewijones92.totum.playback.audioquality

import com.dewijones92.totum.playback.LoudnessBoost
import com.dewijones92.totum.playback.SpeechGate
import com.dewijones92.totum.playback.SpeechModel
import com.dewijones92.totum.playback.SpeechTrack
import com.dewijones92.totum.playback.VolumeBoost
import com.dewijones92.totum.playback.audioquality.RealSpeech.RATE
import com.dewijones92.totum.playback.audioquality.RealSpeech.clip
import com.dewijones92.totum.playback.audioquality.RealSpeech.gained
import com.dewijones92.totum.playback.audioquality.RealSpeech.speechWeights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class RealSpeechOverMusicTest {

    private val musicAlone = RATE * MUSIC_SECONDS
    private val voice = gained(clip.copyOfRange(0, RATE * VOICE_SECONDS), VOICE_DB)

    private val mixed: ShortArray by lazy {
        val total = musicAlone + voice.size
        ShortArray(total) { i ->
            val bed = chord(i) * if (i < musicAlone) LOUD_BED else QUIET_BED
            val talk = if (i >= musicAlone) voice[i - musicAlone] / FULL else 0.0
            ((bed + talk) * FULL).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun chord(i: Int): Double {
        val t = i.toDouble() / RATE
        val beat = if ((t * BEATS_PER_SECOND) % 1.0 < 0.5) 1.0 else 0.6
        return beat * listOf(220.0, 277.18, 329.63).sumOf { hz -> sin(2 * PI * hz * t) } / 3
    }

    private fun run(gate: SpeechGate): ShortArray {
        val track = SpeechTrack(SpeechModel(speechWeights), RATE)
        val boost = LoudnessBoost(RATE).apply {
            level = VolumeBoost.AUTO
            speechGate = gate
            speech = track::isSpeech
        }
        val out = ArrayList<Short>(mixed.size)
        var at = 0
        while (at < mixed.size) {
            val end = minOf(mixed.size, at + CHUNK)
            for (k in at until end) track.push(mixed[k] / FULL.toFloat())
            val result = boost.process(mixed.copyOfRange(at, end), end - at)
            for (k in 0 until boost.outputSamples) out += result[k]
            at = end
        }
        val tail = boost.drain()
        for (k in 0 until boost.outputSamples) out += tail[k]
        return out.toShortArray()
    }

    private fun ShortArray.rms(from: Int, to: Int): Double {
        var sum = 0.0
        for (k in from until minOf(to, size)) sum += this[k].toDouble() * this[k]
        return sqrt(sum / (minOf(to, size) - from))
    }

    private val bedWindow = RATE * 4 to RATE * MUSIC_SECONDS
    private val voiceWindow = musicAlone + RATE * 5 to musicAlone + RATE * VOICE_SECONDS

    @Test
    fun `lifting only speech leaves the music bed alone and lifts the voice over it`() {
        val out = run(SpeechGate.LIFT)
        val bed = out.rms(bedWindow.first, bedWindow.second) / mixed.rms(bedWindow.first, bedWindow.second)
        val talk = out.rms(voiceWindow.first, voiceWindow.second) / mixed.rms(voiceWindow.first, voiceWindow.second)
        println("dewidebug speech-over-music LIFT: bed ${"%.2f".format(bed)}x, voice section ${"%.1f".format(talk)}x")
        assertEquals("the bed came out at ${"%.2f".format(bed)}x", 1.0, bed, BED_TOLERANCE)
        assertTrue("the voice section came out at ${"%.1f".format(talk)}x", talk > LIFTED)
    }

    @Test
    fun `after a loud music intro, measuring only speech has the voice loud while measuring everything lags`() {
        val today = run(SpeechGate.OFF)
        val measured = run(SpeechGate.MEASURE)
        val early = musicAlone + RATE / 2 to musicAlone + RATE * 3
        val todayEarly = today.rms(early.first, early.second)
        val measuredEarly = measured.rms(early.first, early.second)
        val todayLate = today.rms(voiceWindow.first, voiceWindow.second)
        val measuredLate = measured.rms(voiceWindow.first, voiceWindow.second)
        println(
            "dewidebug speech-over-music MEASURE: first seconds today ${todayEarly.toInt()} measured " +
                "${measuredEarly.toInt()}; later today ${todayLate.toInt()} measured ${measuredLate.toInt()}",
        )
        assertTrue(
            "first seconds: today ${todayEarly.toInt()}, measured ${measuredEarly.toInt()}; " +
                "later: today ${todayLate.toInt()}, measured ${measuredLate.toInt()}",
            measuredEarly > todayEarly * EARLY_ADVANTAGE && measuredLate >= todayLate,
        )
    }

    @Test
    fun `neither way clips`() {
        listOf(SpeechGate.MEASURE, SpeechGate.LIFT).forEach { gate ->
            assertEquals(gate.name, 0, run(gate).count { abs(it.toInt()) >= Short.MAX_VALUE - 1 })
        }
    }

    private companion object {
        const val MUSIC_SECONDS = 8
        const val VOICE_SECONDS = 20
        const val VOICE_DB = -12.0
        const val LOUD_BED = 0.25
        const val QUIET_BED = 0.006
        const val BEATS_PER_SECOND = 2.0
        const val FULL = 32_767.0
        const val CHUNK = 4_096
        const val BED_TOLERANCE = 0.2
        const val LIFTED = 3.0
        const val EARLY_ADVANTAGE = 2.0
    }
}
