package com.dewijones92.totum.cli.serve.cast

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

    private companion object {
        const val WAIT_S = 10L
        const val SETTLE_MS = 700L
    }
}
