package com.dewijones92.totum.cli.serve.targets

import com.dewijones92.totum.cli.serve.cast.CastProtocol
import com.dewijones92.totum.cli.serve.cast.ChromecastPlayer
import com.dewijones92.totum.cli.serve.cast.FakeCastControl
import com.dewijones92.totum.cli.serve.cast.FirstReachableControl
import com.dewijones92.totum.cli.serve.cast.PiStreams
import com.dewijones92.totum.cli.serve.cast.PyCastControl
import com.dewijones92.totum.cli.serve.sound.ProcessedSound
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetAction
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TroubleKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.Collections

@OptIn(ExperimentalCoroutinesApi::class)
class TargetDeckVolumeRefusalTest {

    private val logged = Collections.synchronizedList(mutableListOf<String>())
    private val dir = Files.createTempDirectory("deck-volume-refusal").toFile()
    private val tv = Target("lg-tv", "LG TV", TargetKind.TV)

    init {
        Diag.sink = Diag.Sink { _, _, message, _ -> logged += message }
    }

    @After
    fun tearDown() {
        Diag.sink = Diag.Sink { _, _, _, _ -> }
    }

    private class Player(var refusal: String?) : TargetPlayer {
        val commands = mutableListOf<Int>()
        var level: Int? = null
        override fun play(request: PlayRequest) = Unit
        override fun pause(paused: Boolean) = Unit
        override fun seek(positionMs: Long) = Unit
        override fun volume(percent: Int) {
            commands += percent
            level = percent
        }
        override fun volumeRefusal(): String? = refusal
        override val volumePercent: Int? get() = level
        override fun stop() = Unit
        override val positionMs: Long? = null
        override val durationMs: Long? = null
        override var onEnded: (failed: Boolean) -> Unit = {}
    }

    private fun TestScope.deck(player: TargetPlayer) = TargetDeck(
        tv,
        player,
        ConnectingLink(),
        TargetSounds { item, _ -> "https://sound/${item.item.id.value}" },
        dir.resolve("tv-${player.hashCode()}.json"),
        clock = { testScheduler.currentTime },
        scope = backgroundScope,
    )

    private fun volume(percent: Int) = TargetControl(TargetAction.VOLUME, volume = percent)

    private val unlinked = "no connection to LG TV is open, so there is nowhere to send it"

    @Test
    fun `a volume the player cannot apply is not applied, not logged as set, and the state says why`() = runTest {
        val player = Player(refusal = unlinked)
        val deck = deck(player)

        val state = deck.control(volume(2))

        assertEquals(emptyList<Int>(), player.commands)
        assertNull(state.volume)
        assertEquals("⚠️ Couldn't set LG TV's volume to 2: $unlinked", state.problem)
        assertEquals(TroubleKind.WONT_WAKE, state.trouble?.kind)
        assertEquals(state.problem, state.trouble?.reason)
        assertFalse(logged.toString(), logged.any { it.contains("volume set to 2") })
        assertTrue(logged.toString(), logged.any { it.contains("lg-tv: volume 2 NOT set") && unlinked in it })
    }

    @Test
    fun `a volume the player can apply is applied and logged as set, and clears the earlier refusal`() = runTest {
        val player = Player(refusal = unlinked)
        val deck = deck(player)
        deck.control(volume(2))

        player.refusal = null
        val state = deck.control(volume(30))

        assertEquals(listOf(30), player.commands)
        assertEquals(30, state.volume)
        assertNull(state.problem)
        assertTrue(logged.toString(), logged.any { it.contains("lg-tv: volume set to 30") })
    }

    @Test
    fun `a Chromecast with no connection open refuses a volume, through the player and the protocol picker`() {
        val pycast = PyCastControl("127.0.0.1", "LG TV")
        val streams = PiStreams(ProcessedSound("ffmpeg-not-run") { null }, null, 0)

        val reason = ChromecastPlayer("LG TV", pycast, streams).volumeRefusal()

        assertNotNull(reason)
        assertTrue(reason, reason!!.contains("LG TV"))
        assertEquals(reason, FirstReachableControl("LG TV", listOf(CastProtocol.CHROMECAST to pycast)).volumeRefusal())
    }

    @Test
    fun `a control that can always be sent to is never refused`() {
        val streams = PiStreams(ProcessedSound("ffmpeg-not-run") { null }, null, 0)

        assertNull(ChromecastPlayer("LG TV", FakeCastControl(), streams).volumeRefusal())
    }
}
