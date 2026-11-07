package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.cli.serve.sound.ProcessedSound
import com.dewijones92.totum.cli.serve.targets.PlayRequest
import com.dewijones92.totum.data.targets.SoundSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CastControlTest {

    private val dir = Files.createTempDirectory("castcontrol").toFile()

    private fun helper(body: String): File = File(dir, "helper.sh").also { it.writeText(body) }

    private fun control(script: File) = PyCastControl("tv.local", "LG TV", python = "/bin/sh", helper = script.path)

    @Test
    fun `a Chromecast that failed to answer once is tried again, not given up on for ever`() = runBlocking {
        val counter = File(dir, "runs")
        val control = control(
            helper(
                """
                n=${'$'}(cat "${counter.path}" 2>/dev/null || echo 0); n=${'$'}((n+1)); echo ${'$'}n > "${counter.path}"
                if [ "${'$'}n" = 1 ]; then
                  echo '{"event":"error","message":"no Chromecast answered at tv.local"}'
                  exit 2
                fi
                echo '{"event":"ready","name":"LG TV","model":"webOS"}'
                cat > /dev/null
                """.trimIndent()
            )
        )

        assertFalse("the first helper says it cannot find the TV", control.reachable())
        assertTrue("a new helper that connects is reachable", control.reachable())
    }

    @Test
    fun `a stale end from the item before does not end the one that is loading`() {
        val ended = CopyOnWriteArrayList<Boolean>()
        val latch = CountDownLatch(1)
        val control = control(
            helper(
                """
                echo '{"event":"ready","name":"LG TV","model":"webOS"}'
                while read line; do
                  case "${'$'}line" in
                    *'"cmd":"load"'*)
                      echo '{"event":"status","state":"IDLE","idle":"CANCELLED","positionMs":9000,"durationMs":null,"contentId":"https://old"}'
                      echo '{"event":"status","state":"IDLE","idle":"INTERRUPTED","positionMs":9000,"durationMs":null}'
                      ;;
                    *'"cmd":"pause"'*)
                      echo '{"event":"status","state":"IDLE","idle":"FINISHED","positionMs":60000,"durationMs":60000,"contentId":"https://new"}'
                      ;;
                  esac
                done
                """.trimIndent()
            )
        )
        control.onEnded = { failed ->
            ended += failed
            latch.countDown()
        }

        runBlocking { control.reachable() }
        control.load("https://new", "audio/mpeg", "New", 0)

        Thread.sleep(SETTLE_MS)
        assertTrue("the stale ends of the old item ended nothing: ${ended.toList()}", ended.isEmpty())
        control.pause(true)
        assertTrue("the real end of the new item arrives", latch.await(WAIT_S, TimeUnit.SECONDS))
        assertEquals("and only that one counted", listOf(false), ended.toList())
        control.stop()
    }

    private fun recordingHelper(log: File, extraEvents: String = "") = helper(
        """
        echo '{"event":"ready","name":"LG TV","model":"webOS"}'
        $extraEvents
        while read line; do echo "${'$'}line" >> "${log.path}"; done
        """.trimIndent()
    )

    private fun sent(log: File): List<String> =
        if (log.exists()) log.readLines().map { it.substringAfter("\"cmd\":\"").substringBefore('"') } else emptyList()

    private fun waitUntil(condition: () -> Boolean) {
        repeat(WAIT_TICKS) { if (!condition()) Thread.sleep(TICK_MS) }
    }

    @Test
    fun `playing through the Chromecast player sends the helper a load and no volume`() {
        val log = File(dir, "sent.log")
        val control = control(recordingHelper(log))
        val streams = SoundStreams(ProcessedSound("ffmpeg-not-run") { null }, null, 0)
        val player = ChromecastPlayer("LG TV", control, streams)

        runBlocking { control.reachable() }
        player.play(PlayRequest("https://example.com/talk.mp3", "10.0.0.5", SoundSettings(), 0, false, "Talk"))
        player.play(PlayRequest("https://example.com/two.mp3", "10.0.0.5", SoundSettings(), 0, false, "Two"))
        waitUntil { sent(log).size >= 3 }

        assertEquals(listOf("load", "stop", "load"), sent(log))

        player.volume(25)
        waitUntil { sent(log).size >= 4 }
        assertEquals(listOf("load", "stop", "load", "volume"), sent(log))
        control.stop()
    }

    @Test
    fun `a stop with nothing loaded is not sent, because the TV has no session to stop`() {
        val log = File(dir, "sent.log")
        val control = control(recordingHelper(log))
        runBlocking { control.reachable() }

        control.stop()
        control.load("https://new", "audio/mpeg", "New", 0)
        waitUntil { sent(log).isNotEmpty() }

        assertEquals(listOf("load"), sent(log))
        control.stop()
        waitUntil { sent(log).size >= 2 }
        assertEquals(listOf("load", "stop"), sent(log))
    }

    @Test
    fun `the volume the device reports is remembered, and unknown until it does`() {
        val log = File(dir, "sent.log")
        val control = control(recordingHelper(log, """sleep 2; echo '{"event":"volume","percent":1}'"""))

        runBlocking { control.reachable() }
        assertEquals(null, control.volumePercent())
        waitUntil { control.volumePercent() != null }

        assertEquals(1, control.volumePercent())
    }

    private companion object {
        const val WAIT_TICKS = 100
        const val TICK_MS = 50L
        const val WAIT_S = 10L
        const val SETTLE_MS = 700L
    }
}
