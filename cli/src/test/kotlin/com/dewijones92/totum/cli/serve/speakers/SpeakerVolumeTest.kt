package com.dewijones92.totum.cli.serve.speakers

import com.dewijones92.totum.cli.serve.targets.RecordingPlayer
import com.dewijones92.totum.data.targets.VolumeMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
