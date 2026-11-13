package com.dewijones92.totum.cli.serve.speakers

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BluezTransportTest {

    private class BusShell(
        var tree: String? = TREE,
        var read: String? = "q 64\n",
        var set: String? = "",
    ) : Shell {
        val ran = mutableListOf<String>()
        override suspend fun run(args: List<String>): String? {
            val line = args.joinToString(" ")
            ran += line
            return when {
                "tree org.bluez" in line -> tree
                "set-property" in line -> set
                "get-property" in line -> read
                else -> ""
            }
        }
    }

    @Test
    fun `volume maps 0 to 100 percent onto the transport's 0 to 127 and back`() {
        assertEquals(0, HardwareVolume.rawOf(0))
        assertEquals(127, HardwareVolume.rawOf(100))
        assertEquals(64, HardwareVolume.rawOf(50))
        assertEquals(127, HardwareVolume.rawOf(500))
        assertEquals(0, HardwareVolume.rawOf(-5))
        assertEquals(50, HardwareVolume.percentOf(64))
        assertEquals(100, HardwareVolume.percentOf(127))
        assertEquals(1, HardwareVolume.percentOf(1))
        (0..100).forEach { assertEquals(it, HardwareVolume.percentOf(HardwareVolume.rawOf(it))) }
    }

    @Test
    fun `the level is read from the speaker's own transport, found by its address among all the bus objects`() =
        runBlocking {
            val shell = BusShell()

            val answer = BluezTransport("00:11:22:33:44:55", shell).volume()

            assertEquals(TransportVolume.Level(64), answer)
            val wanted = "busctl --system get-property org.bluez /org/bluez/hci0/dev_00_11_22_33_44_55/fd3 " +
                "org.bluez.MediaTransport1 Volume"
            assertEquals(wanted, shell.ran.last())
        }

    @Test
    fun `setting the volume writes a uint16 to the transport and reports what the speaker says after`() = runBlocking {
        val shell = BusShell(read = "q 90\n")

        val answer = BluezTransport("00:11:22:33:44:55", shell).setVolume(96)

        assertEquals(TransportVolume.Level(90), answer)
        assertTrue(
            shell.ran.any {
                it == "busctl --system set-property org.bluez /org/bluez/hci0/dev_00_11_22_33_44_55/fd3 " +
                    "org.bluez.MediaTransport1 Volume q 96"
            },
        )
    }

    @Test
    fun `a speaker that is not streaming has no transport, and nothing is written`() = runBlocking {
        val shell = BusShell(tree = "/org/bluez\n/org/bluez/hci0\n/org/bluez/hci0/dev_00_11_22_33_44_55\n")

        val answer = BluezTransport("00:11:22:33:44:55", shell).setVolume(50)

        assertTrue(answer is TransportVolume.Unavailable)
        assertTrue(shell.ran.none { "set-property" in it })
    }

    @Test
    fun `another device's transport is never used`() = runBlocking {
        val shell = BusShell()

        val answer = BluezTransport("AA:BB:CC:DD:EE:FF", shell).volume()

        assertTrue(answer is TransportVolume.Unavailable)
    }

    @Test
    fun `a transport with no Volume property, or an answer that cannot be read, is unavailable with its reason`() =
        runBlocking {
            val none = BluezTransport("00:11:22:33:44:55", BusShell(read = null)).volume()
            val odd = BluezTransport("00:11:22:33:44:55", BusShell(read = "s \"idle\"\n")).volume()

            assertTrue((none as TransportVolume.Unavailable).reason, "no Volume property" in none.reason)
            assertTrue((odd as TransportVolume.Unavailable).reason, "idle" in odd.reason)
        }

    @Test
    fun `a refused write is unavailable, and busctl being missing is too`() = runBlocking {
        val refused = BluezTransport("00:11:22:33:44:55", BusShell(set = null)).setVolume(30)
        val missing = BluezTransport("00:11:22:33:44:55", BusShell(tree = null)).volume()

        assertTrue(refused is TransportVolume.Unavailable)
        assertTrue(missing is TransportVolume.Unavailable)
    }

    private companion object {
        const val TREE = "/org/bluez\n/org/bluez/hci0\n/org/bluez/hci0/dev_00_11_22_33_44_55\n" +
            "/org/bluez/hci0/dev_00_11_22_33_44_55/sep1\n/org/bluez/hci0/dev_00_11_22_33_44_55/fd3\n" +
            "/org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF\n/org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF/sep1\n"
    }
}
