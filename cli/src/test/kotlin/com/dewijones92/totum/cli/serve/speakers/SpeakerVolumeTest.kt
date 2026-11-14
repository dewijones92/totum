package com.dewijones92.totum.cli.serve.speakers

import com.dewijones92.totum.cli.serve.targets.RecordingPlayer
import com.dewijones92.totum.data.targets.VolumeMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerVolumeTest {

    private class FakeTransport(var answer: TransportVolume) : SpeakerTransport {
        val written = mutableListOf<Int>()
        var echo = true
        override suspend fun volume(): TransportVolume = answer
        override suspend fun setVolume(raw: Int): TransportVolume {
            if (answer !is TransportVolume.Level) return answer
            written += raw
            if (echo) answer = TransportVolume.Level(raw)
            return answer
        }
    }

    private val unavailable = TransportVolume.Unavailable("no Volume property")
    private val mpv = RecordingPlayer()

    @Test
    fun `when the speaker has its own volume the phone's slider drives it, and mpv is left alone`() = runBlocking {
        val transport = FakeTransport(TransportVolume.Level(10))
        val volume = SpeakerVolume("mic", transport, mpv)

        volume.set(50)

        assertEquals(listOf(64), transport.written)
        assertEquals(VolumeMode.HARDWARE, volume.mode)
        assertEquals(50, volume.percent)
        assertEquals(emptyList<Int>(), mpv.volumeCommands)
    }

    @Test
    fun `when the speaker offers no volume the slider falls back to mpv's, and says so`() = runBlocking {
        val transport = FakeTransport(unavailable)
        val volume = SpeakerVolume("mic", transport, mpv)

        volume.set(30)

        assertEquals(emptyList<Int>(), transport.written)
        assertEquals(listOf(30), mpv.volumeCommands)
        assertEquals(VolumeMode.SOFTWARE, volume.mode)
        assertEquals(30, volume.percent)
    }

    @Test
    fun `reading the speaker's level never writes a volume anywhere`() = runBlocking {
        val transport = FakeTransport(TransportVolume.Level(HardwareVolume.rawOf(80)))
        val volume = SpeakerVolume("mic", transport, mpv)
        assertNull(volume.percent)

        volume.refresh()

        assertEquals(80, volume.percent)
        assertEquals(VolumeMode.HARDWARE, volume.mode)
        assertEquals(emptyList<Int>(), transport.written)
        assertEquals(emptyList<Int>(), mpv.volumeCommands)
    }

    @Test
    fun `a level changed on the speaker itself is shown the next time it is read`() = runBlocking {
        val transport = FakeTransport(TransportVolume.Level(HardwareVolume.rawOf(20)))
        val volume = SpeakerVolume("mic", transport, mpv)
        volume.refresh()

        transport.answer = TransportVolume.Level(HardwareVolume.rawOf(35))
        volume.refresh()

        assertEquals(35, volume.percent)
    }

    @Test
    fun `a speaker that goes away has an unknown volume, not the last one`() = runBlocking {
        val transport = FakeTransport(TransportVolume.Level(HardwareVolume.rawOf(80)))
        val volume = SpeakerVolume("mic", transport, mpv)
        volume.refresh()

        transport.answer = unavailable
        volume.refresh()

        assertNull(volume.percent)
        assertNull(volume.mode)
    }

    @Test
    fun `mpv's earlier level is shown until the phone moves the slider, then there is one control again`() =
        runBlocking {
            val transport = FakeTransport(TransportVolume.Level(HardwareVolume.rawOf(90)))
            val volume = SpeakerVolume("mic", transport, mpv)
            mpv.volume(30)
            mpv.volumeCommands.clear()

            volume.refresh()

            assertEquals(VolumeMode.SOFTWARE, volume.mode)
            assertEquals(30, volume.percent)
            assertEquals("a read must not raise mpv", emptyList<Int>(), mpv.volumeCommands)

            volume.set(40)

            assertEquals(VolumeMode.HARDWARE, volume.mode)
            assertEquals(40, volume.percent)
            assertEquals(listOf(100), mpv.volumeCommands)
        }

    @Test
    fun `what the speaker reports after a write is what is shown, because it may round to its own steps`() =
        runBlocking {
            val transport = FakeTransport(TransportVolume.Level(0)).apply { echo = false }
            transport.answer = TransportVolume.Level(HardwareVolume.rawOf(31))
            val volume = SpeakerVolume("mic", transport, mpv)

            volume.set(33)

            assertEquals(31, volume.percent)
        }

    private fun heard(hardwareRaw: Int, mpvPercent: Int?): Double =
        hardwareRaw / HardwareVolume.MAX_RAW.toDouble() * (mpvPercent ?: 100) / 100.0

    @Test
    fun `a fade on a speaker whose mpv was turned down fades mpv, and never raises what is heard`() = runBlocking {
        val transport = FakeTransport(TransportVolume.Level(HardwareVolume.rawOf(10)))
        val volume = SpeakerVolume("mic", transport, mpv)
        mpv.volume(40)
        mpv.volumeCommands.clear()
        val started = heard(transport.answer.let { (it as TransportVolume.Level).raw }, mpv.volumePercent)

        val fade = volume.beginFade()!!
        assertEquals(40, fade.basePercent)
        val seen = mutableListOf<Double>()
        for (level in listOf(40, 30, 20, 10, 0, 40)) {
            fade.set(level)
            seen += heard((transport.answer as TransportVolume.Level).raw, mpv.volumePercent)
        }

        assertTrue("heard $seen against the $started it began at", seen.all { it <= started + EPSILON })
        assertEquals("the hardware is left alone", emptyList<Int>(), transport.written)
        assertEquals("and the last write puts mpv back exactly", 40, mpv.volumeCommands.last())
    }

    @Test
    fun `a fade on the speaker's own volume never goes above where it began and puts back the exact raw`() =
        runBlocking {
            val transport = FakeTransport(TransportVolume.Level(2))
            val volume = SpeakerVolume("mic", transport, mpv)

            val fade = volume.beginFade()!!
            fade.set(1)
            fade.set(0)
            fade.set(fade.basePercent)

            assertTrue("never above raw 2: ${transport.written}", transport.written.all { it <= 2 })
            assertEquals("restored to the raw it began at, not the nearest percent", 2, transport.written.last())
            assertEquals(emptyList<Int>(), mpv.volumeCommands)
        }

    @Test
    fun `a speaker that never reports back is faded without raising anything`() = runBlocking {
        val transport = FakeTransport(TransportVolume.Level(HardwareVolume.rawOf(60))).apply { echo = false }
        val volume = SpeakerVolume("mic", transport, mpv)
        val begun = (transport.answer as TransportVolume.Level).raw

        val fade = volume.beginFade()!!
        for (level in listOf(60, 45, 30, 15, 0, 60)) fade.set(level)

        assertTrue("never above raw $begun: ${transport.written}", transport.written.all { it <= begun })
        assertEquals(begun, transport.written.last())
        assertEquals(emptyList<Int>(), mpv.volumeCommands)
    }

    @Test
    fun `a speaker that has gone cannot be put back, says so, and mpv is not made louder instead`() = runBlocking {
        val transport = FakeTransport(TransportVolume.Level(HardwareVolume.rawOf(40)))
        val volume = SpeakerVolume("mic", transport, mpv)
        val fade = volume.beginFade()!!
        fade.set(20)
        transport.answer = unavailable

        val written = fade.set(fade.basePercent)

        assertFalse(written)
        assertEquals(emptyList<Int>(), mpv.volumeCommands)
    }

    @Test
    fun `a slider move that the speaker has not taken yet does not raise mpv above what the person asked for`() =
        runBlocking {
            val transport = FakeTransport(TransportVolume.Level(HardwareVolume.rawOf(80))).apply { echo = false }
            val volume = SpeakerVolume("mic", transport, mpv)
            mpv.volume(40)
            mpv.volumeCommands.clear()

            volume.set(30)

            val shown = mpv.volumeCommands
            assertTrue("mpv was never put to 100: $shown", shown.none { it == 100 })
            val soundNow = heard(HardwareVolume.rawOf(80), mpv.volumePercent)
            assertTrue("heard $soundNow against the 0.30 asked for", soundNow <= 0.30 + 0.02)
        }

    @Test
    fun `a slider move the speaker has not taken yet, from a level below it, may put mpv back to full`() = runBlocking {
        val transport = FakeTransport(TransportVolume.Level(HardwareVolume.rawOf(10))).apply { echo = false }
        val volume = SpeakerVolume("mic", transport, mpv)
        mpv.volume(40)
        mpv.volumeCommands.clear()

        volume.set(30)

        assertEquals(listOf(100), mpv.volumeCommands)
    }

    private companion object {
        const val EPSILON = 1e-9
    }
}
