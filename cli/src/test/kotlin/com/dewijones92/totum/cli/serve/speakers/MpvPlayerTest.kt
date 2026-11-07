package com.dewijones92.totum.cli.serve.speakers

import com.dewijones92.totum.cli.serve.sound.ProcessedSound
import com.dewijones92.totum.cli.serve.targets.PlayRequest
import com.dewijones92.totum.data.targets.SoundSettings
import com.dewijones92.totum.playback.VolumeBoost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MpvPlayerTest {

    private val dir = Files.createTempDirectory("mpv-test").toFile()
    private val ended = CopyOnWriteArrayList<Boolean>()
    private val latch = CountDownLatch(1)
    private var player: MpvPlayer? = null

    @After
    fun stop() {
        player?.stop()
    }

    private fun script(name: String, body: String): String =
        File(dir, name).apply {
            writeText("#!/bin/sh\n$body\n")
            setExecutable(true)
        }.path

    private fun argv(name: String): List<String> {
        waitFor { File(dir, name).isFile && File(dir, name).length() > 0 }
        return File(dir, name).readLines()
    }

    private fun waitFor(condition: () -> Boolean) {
        repeat(WAIT_TICKS) { if (!condition()) Thread.sleep(TICK_MS) }
    }

    private fun mpv(
        body: String,
        ffmpeg: String? = null,
        locally: (String, String) -> String = { _, url -> url },
    ): MpvPlayer {
        val processed = ffmpeg?.let { ProcessedSound(it) { null } } ?: ProcessedSound("ffmpeg-not-used") { null }
        return MpvPlayer("bar", dir, script("mpv.sh", body), processed, locally).also {
            it.onEnded = { failed ->
                ended += failed
                latch.countDown()
            }
            player = it
        }
    }

    private fun request(
        url: String = "https://example.com/talk.mp3",
        sound: SoundSettings = SoundSettings(),
        startAtMs: Long = 0,
    ) = PlayRequest(url, "bluez_output.AA_BB.1", sound, startAtMs, picture = false, title = "Talk")

    @Test
    fun `an unprocessed item is played straight from its address, starting where it was`() {
        val p = mpv("""printf '%s\n' "${'$'}@" > "${dir.path}/argv.txt"""")

        p.play(request(startAtMs = 12_500))

        val args = argv("argv.txt")
        assertTrue(args.toString(), args.containsAll(listOf("--no-video", "--no-input-terminal", "--idle=no")))
        assertTrue(args.contains("--audio-device=pulse/bluez_output.AA_BB.1"))
        assertFalse(args.toString(), args.any { it.startsWith("--volume") })
        assertTrue(args.contains("--input-ipc-server=${dir.path}/mpv-bar.sock"))
        assertTrue(args.contains("--start=12.5"))
        assertEquals(listOf("--", "https://example.com/talk.mp3"), args.takeLast(2))
        assertFalse(args.any { it.startsWith("--demuxer") })
    }

    @Test
    fun `a volume chosen from the phone is kept for the next item, and none is ever invented`() {
        val p = mpv("""printf '%s\n' "${'$'}@" > "${dir.path}/argv-${'$'}(date +%s%N).txt"; sleep 5""")

        p.play(request())
        waitFor { dir.listFiles { f -> f.name.startsWith("argv-") }?.size == 1 }
        p.volume(30)
        p.play(request(url = "https://example.com/two.mp3"))

        waitFor { dir.listFiles { f -> f.name.startsWith("argv-") }?.size == 2 }
        val runs = dir.listFiles { f -> f.name.startsWith("argv-") }!!.sortedBy { it.name }.map { it.readLines() }
        assertFalse(runs.first().toString(), runs.first().any { it.startsWith("--volume") })
        assertTrue(runs.last().toString(), runs.last().contains("--volume=30"))
        assertEquals(30, p.volumePercent)
    }

    @Test
    fun `mpv is given the address the Pi relays a stream at, and the item's own address is not used`() {
        val p = mpv(
            """printf '%s\n' "${'$'}@" > "${dir.path}/argv.txt"""",
            locally = { label, _ -> "http://127.0.0.1:8771/y/$label.mp4" },
        )

        p.play(request(url = "https://r.googlevideo.com/videoplayback?clen=1"))

        val args = argv("argv.txt")
        assertEquals(listOf("--", "http://127.0.0.1:8771/y/bar.mp4"), args.takeLast(2))
        assertFalse(args.toString(), args.any { it.contains("googlevideo") })
    }

    @Test
    fun `a boosted item is decoded by ffmpeg from the relayed address too`() {
        val ffmpeg = script(
            "ffmpeg.sh",
            """printf '%s\n' "${'$'}@" > "${dir.path}/ffmpeg-argv.txt"; head -c 96000 /dev/zero"""
        )
        val p = mpv(
            """cat > /dev/null""",
            ffmpeg,
            locally = { _, _ -> "http://127.0.0.1:8771/y/relayed.mp4" },
        )

        p.play(
            request(
                url = "https://r.googlevideo.com/videoplayback?clen=1",
                sound = SoundSettings(boost = VolumeBoost.AUTO)
            )
        )

        val args = argv("ffmpeg-argv.txt")
        assertEquals("http://127.0.0.1:8771/y/relayed.mp4", args[args.indexOf("-i") + 1])
    }

    @Test
    fun `an address that begins with a dash cannot be read as an option`() {
        val p = mpv("""printf '%s\n' "${'$'}@" > "${dir.path}/argv.txt"""")

        p.play(request(url = "--script=/tmp/evil.lua"))

        val args = argv("argv.txt")
        assertEquals(listOf("--", "--script=/tmp/evil.lua"), args.takeLast(2))
        assertEquals(1, args.count { it == "--script=/tmp/evil.lua" })
    }

    @Test
    fun `a boosted item is decoded by ffmpeg from where it was and fed to mpv as raw 48 kHz stereo`() {
        val ffmpeg = script(
            "ffmpeg.sh",
            """printf '%s\n' "${'$'}@" > "${dir.path}/ffmpeg-argv.txt"; head -c 96000 /dev/zero""",
        )
        val p = mpv(
            """printf '%s\n' "${'$'}@" > "${dir.path}/argv.txt"; cat > "${dir.path}/stdin.bin"""",
            ffmpeg,
        )

        p.play(request(sound = SoundSettings(boost = VolumeBoost.AUTO), startAtMs = 12_500))

        val args = argv("argv.txt")
        assertEquals(
            listOf(
                "--demuxer=rawaudio",
                "--demuxer-rawaudio-format=s16le",
                "--demuxer-rawaudio-rate=48000",
                "--demuxer-rawaudio-channels=2",
                "-",
            ),
            args.takeLast(5),
        )
        assertFalse(args.any { it.startsWith("--start") })
        val decode = argv("ffmpeg-argv.txt")
        val from = decode.indexOf("-ss")
        assertEquals(listOf("-ss", "12.500", "-i", "https://example.com/talk.mp3"), decode.slice(from..from + 3))
        assertTrue(latch.await(WAIT_S, TimeUnit.SECONDS))
        assertTrue("mpv was fed the processed sound", File(dir, "stdin.bin").length() > 0)
        assertEquals(0L, File(dir, "stdin.bin").length() % 4)
    }

    @Test
    fun `mpv finishing cleanly is an item that ended, not one that failed`() {
        val p = mpv("exit 0")

        p.play(request())

        assertTrue(latch.await(WAIT_S, TimeUnit.SECONDS))
        assertEquals(listOf(false), ended.toList())
    }

    @Test
    fun `mpv failing is reported as a failure`() {
        val p = mpv("""echo "could not open https://jf/stream?api_key=SECRET123&x=1"; exit 3""")

        p.play(request())

        assertTrue(latch.await(WAIT_S, TimeUnit.SECONDS))
        assertEquals(listOf(true), ended.toList())
        assertTrue(File(dir, "mpv-bar.log").readText().contains("could not open"))
    }

    @Test
    fun `a failure says what mpv ended with, and carries no key from its log`() {
        val p = mpv("""echo "could not open https://jf/stream?api_key=SECRET123&x=1"; exit 2""")

        p.play(request())

        assertTrue(latch.await(WAIT_S, TimeUnit.SECONDS))
        val said = p.failure.orEmpty()
        assertTrue(said, said.contains("exited with code 2"))
        assertFalse(said, said.contains("SECRET123"))
    }

    @Test
    fun `mpv prints its warnings and errors, and what it said is in the failure`() {
        val p = mpv("""echo "[ffmpeg/demuxer] dash: Error when loading first fragment"; exit 2""")

        p.play(request())

        assertTrue(latch.await(WAIT_S, TimeUnit.SECONDS))
        val said = p.failure.orEmpty()
        assertTrue(said, said.contains("exited with code 2"))
        assertTrue(said, said.contains("Error when loading first fragment"))
    }

    @Test
    fun `mpv is told to say warnings and errors and nothing else, and to read no keys`() {
        val p = mpv("""printf '%s\n' "${'$'}@" > "${dir.path}/argv.txt"""")

        p.play(request())

        val args = argv("argv.txt")
        assertTrue(
            args.toString(),
            args.containsAll(listOf("--no-input-terminal", "--msg-level=all=warn", "--term-status-msg="))
        )
        assertFalse(args.toString(), args.contains("--no-terminal"))
    }

    @Test
    fun `a live stream is played from its live edge, not from a start offset`() {
        val p = mpv(
            """printf '%s\n' "${'$'}@" > "${dir.path}/argv.txt"""",
            locally = { _, _ -> "https://manifest.googlevideo.com/api/manifest/hls_playlist/x/index.m3u8" },
        )

        p.play(request(url = "http://127.0.0.1:8771/live/Dx5qFachd3A.mpd", startAtMs = 5_000))

        val args = argv("argv.txt")
        assertFalse(args.toString(), args.any { it.startsWith("--start") })
        assertEquals(
            listOf("--", "https://manifest.googlevideo.com/api/manifest/hls_playlist/x/index.m3u8"),
            args.takeLast(2),
        )
    }

    @Test
    fun `stopping kills mpv and does not count as the item ending`() {
        val p = mpv("sleep 30")
        p.play(request())

        p.stop()
        Thread.sleep(SETTLE_MS)

        assertTrue("a stopped player reports nothing: ${ended.toList()}", ended.isEmpty())
    }

    @Test
    fun `starting another item replaces the first without reporting it as finished`() {
        val p = mpv("""printf '%s\n' "${'$'}@" > "${dir.path}/argv-${'$'}${'$'}.txt"; sleep 30""")

        p.play(request(url = "https://example.com/one.mp3"))
        waitFor { dir.listFiles { f -> f.name.startsWith("argv-") }?.isNotEmpty() == true }
        p.play(request(url = "https://example.com/two.mp3"))
        waitFor { dir.listFiles { f -> f.name.startsWith("argv-") }?.size == 2 }

        val lasts = dir.listFiles { f -> f.name.startsWith("argv-") }!!.map { it.readLines().last() }.toSet()
        assertEquals(setOf("https://example.com/one.mp3", "https://example.com/two.mp3"), lasts)
        assertTrue(ended.toList().toString(), ended.isEmpty())
    }

    @Test
    fun `with no running mpv there is no position or duration and controls do nothing`() {
        val p = mpv("exit 0")

        p.pause(true)
        p.seek(5_000)
        p.volume(10)

        assertNull(p.positionMs)
        assertNull(p.durationMs)
    }

    private companion object {
        const val WAIT_S = 10L
        const val WAIT_TICKS = 200
        const val TICK_MS = 50L
        const val SETTLE_MS = 400L
    }
}
