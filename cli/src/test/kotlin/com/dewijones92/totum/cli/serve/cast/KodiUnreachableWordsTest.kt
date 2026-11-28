package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.cli.serve.sound.ProcessedSound
import com.dewijones92.totum.cli.serve.targets.TargetDeck
import com.dewijones92.totum.cli.serve.targets.TargetSounds
import com.dewijones92.totum.cli.serve.targets.podcastItem
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.targets.QueueMode
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetQueueRequest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class KodiUnreachableWordsTest {

    private val kodi = FakeKodi()
    private val dir = Files.createTempDirectory("kodi-words").toFile()
    private val logged = java.util.Collections.synchronizedList(mutableListOf<String>())

    init {
        Diag.sink = Diag.Sink { _, _, message, _ -> logged += message }
    }

    @After
    fun tidy() {
        kodi.close()
        Diag.sink = Diag.Sink { _, _, _, _ -> }
    }

    private fun control(file: File?) = KodiControl(
        kodi.address,
        "Kodi",
        JdkKodiRpc(kodiEndpoint(kodi.address), KodiCredentials.fromFile(file)),
        polls = false,
    )

    private fun deck(control: KodiControl): TargetDeck {
        val streams = PiStreams(ProcessedSound("ffmpeg-not-run") { null }, null, 0)
        return TargetDeck(
            Target("kodi", "Kodi", TargetKind.TV),
            ChromecastPlayer("Kodi", control, streams),
            CastLink("kodi.local", control),
            TargetSounds { _, _ -> "https://sound/talk.mp3" },
            File(dir, "kodi.json"),
        )
    }

    private suspend fun problemAfterAPlay(control: KodiControl): String? {
        val deck = deck(control)
        deck.enqueue(TargetQueueRequest(listOf(podcastItem("a")), QueueMode.REPLACE))
        return deck.state().problem
    }

    @Test
    fun `a Kodi that refuses the sign-in says so, not that it is off or out of range`() = runBlocking {
        val wrong = kodi.credentialsFile(password = "not-the-password")

        val problem = problemAfterAPlay(control(wrong))

        assertEquals("🔑 Kodi refused the sign-in: check its web server password", problem)
    }

    @Test
    fun `a Kodi with no sign-in file says Totum has none, and names the setting`() = runBlocking {
        val problem = problemAfterAPlay(control(null))

        assertEquals("🔑 Totum has no sign-in for Kodi: check TOTUM_KODI_CREDENTIALS", problem)
    }

    @Test
    fun `a Kodi that does not answer at all is still not connected`() = runBlocking {
        kodi.down = true

        val problem = problemAfterAPlay(control(kodi.credentialsFile()))

        assertEquals("📶 Couldn't reach Kodi: it isn't connected (off or out of range)", problem)
    }

    @Test
    fun `the link carries the reason, and a good sign-in clears it`() = runBlocking {
        val control = control(kodi.credentialsFile(password = "not-the-password"))
        val link = CastLink("kodi.local", control)

        assertNull(link.connect())
        assertTrue(link.unreachable?.reason.orEmpty(), link.unreachable?.reason.orEmpty().contains("sign-in"))

        val good = CastLink("kodi.local", control(kodi.credentialsFile()))
        assertEquals("kodi.local", good.connect())
        assertNull(good.unreachable)
        assertFalse(logged.any { it.contains("password") && it.contains("not-the-password") })
    }
}
