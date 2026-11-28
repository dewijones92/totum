package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.cli.serve.power.PowerOff
import com.dewijones92.totum.cli.serve.power.SteppedPower
import com.dewijones92.totum.cli.serve.sound.ProcessedSound
import com.dewijones92.totum.cli.serve.targets.TargetDeck
import com.dewijones92.totum.cli.serve.targets.TargetSounds
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetAction
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetPower
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CastLinkSwitchOffTest {

    private val dir = Files.createTempDirectory("cast-link-off").toFile()
    private val control = FakeCastControl()
    private val ran = mutableListOf<String>()

    private fun power(answer: String?, available: Boolean = true) = SteppedPower(
        label = "LG TV",
        wakeSteps = emptyList(),
        answers = { true },
        wakeFailed = "it did not wake",
        off = object : PowerOff {
            override val name = "webOS system/turnOff"
            override val available = available

            override suspend fun run(): String? {
                ran += name
                return answer
            }
        },
    )

    private fun deck(power: SteppedPower): TargetDeck {
        val streams = PiStreams(ProcessedSound("ffmpeg-not-run") { null }, null, 0)
        return TargetDeck(
            Target("lg-tv", "LG TV", TargetKind.TV),
            ChromecastPlayer("LG TV", control, streams, wake = power),
            CastLink("tv.local", control, power),
            TargetSounds { _, _ -> "https://sound/talk.mp3" },
            File(dir, "lg.json"),
        )
    }

    @Test
    fun `a switch off that worked is reported as nothing wrong, not as no way to do it`() = runBlocking {
        val link = CastLink("tv.local", control, power(answer = null))

        assertNull(link.switchOff())
        assertEquals(listOf("webOS system/turnOff"), ran)
    }

    @Test
    fun `a switch off that failed says why`() = runBlocking {
        val link = CastLink("tv.local", control, power(answer = "the TV closed the connection (1008)"))

        assertEquals("the TV closed the connection (1008)", link.switchOff())
    }

    @Test
    fun `a link with no way to switch off says so`() = runBlocking {
        assertEquals("Totum has no way to switch it off", CastLink("tv.local", control).switchOff())
        assertEquals(
            "Totum has no way to switch it off",
            CastLink("tv.local", control, power(answer = null, available = false)).switchOff(),
        )
    }

    @Test
    fun `the state the phone reads after a switch off that worked has no problem and the TV is asleep`() =
        runBlocking {
            val tv = power(answer = null)
            val deck = deck(tv)

            val state = deck.control(TargetControl(TargetAction.SWITCH_OFF))

            assertEquals(listOf("webOS system/turnOff"), ran)
            assertNull("it worked, so it must not say it failed: ${state.problem}", state.problem)
            assertNull(state.trouble)
            assertTrue(tv.power == TargetPower.ASLEEP)
        }
}
