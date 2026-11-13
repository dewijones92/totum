package com.dewijones92.totum.cli.serve.speakers

import com.dewijones92.totum.data.targets.TroubleKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BluezTest {

    private class FakeShell(var sinks: String = "", var inputs: String = "") : Shell {
        val ran = mutableListOf<String>()
        var linksOnConnect = true
        override suspend fun run(args: List<String>): String? {
            val line = args.joinToString(" ")
            ran += line
            return when {
                line == "bluetoothctl devices Paired" ->
                    "Device 54:15:89:76:CD:A4 LG S65Q\nDevice AA:BB:CC:DD:EE:FF Pixel 7\n"
                line == "bluetoothctl info 54:15:89:76:CD:A4" ->
                    "Device 54:15:89:76:CD:A4 (public)\n\tAlias: Living room bar\n\tUUID: Audio Sink (0000110b)\n"
                line.startsWith("bluetoothctl info") -> "\tAlias: Pixel 7\n\tUUID: Audio Source (0000110a)\n"
                line.startsWith("bluetoothctl connect") -> {
                    if (linksOnConnect) sinks = "7\tbluez_sink.54_15_89_76_CD_A4.a2dp_sink\tmodule-bluez5-device.c\n"
                    "Connection successful"
                }
                line == "pactl list short sinks" -> sinks
                line == "pactl list short sink-inputs" -> inputs
                else -> ""
            }
        }
    }

    @Test
    fun `only paired devices that take audio are speakers, named by their alias`() = runBlocking {
        val found = BluezSpeakers(FakeShell()).find()
        assertEquals(listOf("Living room bar"), found.map { it.speaker.label })
        assertEquals("54158976cda4", found.single().speaker.id)
    }

    @Test
    fun `a speaker Totum linked is unlinked when it is done, unless something else is playing on it`() = runBlocking {
        val shell = FakeShell()
        val link = BluezLink("54:15:89:76:CD:A4", shell, pollMs = 0)
        assertEquals("bluez_sink.54_15_89_76_CD_A4.a2dp_sink", link.connect())
        shell.inputs = "3\t7\t12\tprotocol-native.c\ts16le 2ch 44100Hz\n"
        link.release()
        assertFalse(shell.ran.any { it.startsWith("bluetoothctl disconnect") })
        shell.inputs = ""
        link.release()
        assertTrue(shell.ran.contains("bluetoothctl disconnect 54:15:89:76:CD:A4"))
    }

    @Test
    fun `a speaker already linked is used and left linked, and one that never links says so`() = runBlocking {
        val shell = FakeShell(sinks = "7\tbluez_sink.54_15_89_76_CD_A4.a2dp_sink\tx\n")
        val link = BluezLink("54:15:89:76:CD:A4", shell, pollMs = 0)
        assertEquals("bluez_sink.54_15_89_76_CD_A4.a2dp_sink", link.connect())
        link.release()
        assertFalse(shell.ran.any { it.startsWith("bluetoothctl connect") || it.startsWith("bluetoothctl disconnect") })
        val off = FakeShell().apply { linksOnConnect = false }
        assertNull(BluezLink("54:15:89:76:CD:A4", off, pollMs = 0, tries = 2).connect())
    }

    @Test
    fun `a speaker that never links is not connected, and one switched on by hand says so`() = runBlocking {
        val off = FakeShell().apply { linksOnConnect = false }
        val link = BluezLink("54:15:89:76:CD:A4", off, pollMs = 0, tries = 2)
        assertNull(link.unreachable)

        link.connect()

        assertEquals(TroubleKind.NOT_CONNECTED, link.unreachable?.kind)
        val byHand = BluezLink("54:15:89:76:CD:A4", off, pollMs = 0, tries = 2, switchedOnByHand = true)
        byHand.connect()
        assertEquals(TroubleKind.SWITCH_ON, byHand.unreachable?.kind)
    }

    @Test
    fun `a link that works clears the reason, and says how long it waited for the sink`() = runBlocking {
        val shell = FakeShell()
        var now = 1_000L
        val link = BluezLink("54:15:89:76:CD:A4", shell, pollMs = 0, clock = { now.also { now += 250 } })

        link.connect()

        assertNull(link.unreachable)
        assertTrue("waited ${link.sinkWaitMs}ms", link.sinkWaitMs >= 250)
    }

    @Test
    fun `a speaker that is kept awake stays linked when Totum is done, even if Totum linked it`() = runBlocking {
        val shell = FakeShell()
        val link = BluezLink("54:15:89:76:CD:A4", shell, pollMs = 0, keepAwake = true)
        link.connect()

        link.release()

        assertFalse(shell.ran.any { it.startsWith("bluetoothctl disconnect") })
    }

    @Test
    fun `keeping awake relinks a dropped speaker, and says when it will not come back`() = runBlocking {
        val shell = FakeShell()
        val link = BluezLink("54:15:89:76:CD:A4", shell, pollMs = 0, keepAwake = true)
        assertFalse(link.linked())

        assertTrue(link.reconnect())

        assertTrue(link.linked())
        assertTrue(shell.ran.contains("bluetoothctl connect 54:15:89:76:CD:A4"))
        val off = FakeShell().apply { linksOnConnect = false }
        assertFalse(BluezLink("54:15:89:76:CD:A4", off, pollMs = 0).reconnect())
        assertNull("a keep-awake retry must not change what a play is told", link.unreachable)
    }

    @Test
    fun `the keep-awake signal is two seconds of the chosen source on the speaker's own sink`() = runBlocking {
        val shell = FakeShell(sinks = "7\tbluez_sink.54_15_89_76_CD_A4.a2dp_sink\tx\n")
        val link = BluezLink("54:15:89:76:CD:A4", shell, pollMs = 0, keepAwake = true, mpv = "mpv")

        assertTrue(link.sendSignal(KeepAwakeSignal.SILENCE))

        val sent = shell.ran.last()
        assertTrue(sent, sent.startsWith("mpv "))
        assertTrue(sent, "--audio-device=pulse/bluez_sink.54_15_89_76_CD_A4.a2dp_sink" in sent)
        assertTrue(sent, "--length=2" in sent)
        assertTrue(sent, sent.endsWith("av://lavfi:anullsrc=channel_layout=stereo:sample_rate=44100"))
    }
}
