package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.cli.serve.sound.ProcessedSound
import com.dewijones92.totum.cli.serve.targets.PlayRequest
import com.dewijones92.totum.data.targets.SoundSettings
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.playback.VolumeBoost
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

class ChromecastPlayerTest {

    private class Loaded(val url: String, val type: String, val title: String, val startAtMs: Long)

    private class FakeControl : CastControl {
        val loads = mutableListOf<Loaded>()
        val calls = mutableListOf<String>()
        var reading: CastReading? = null
        var reachable = true
        var deviceVolume: Int? = null
        override fun load(url: String, contentType: String, title: String, startAtMs: Long) {
            loads += Loaded(url, contentType, title, startAtMs)
        }
        override fun pause(paused: Boolean) {
            calls += "pause=$paused"
        }
        override fun seek(positionMs: Long) {
            calls += "seek=$positionMs"
        }
        override fun volume(percent: Int) {
            calls += "volume=$percent"
        }
        override fun volumePercent(): Int? = deviceVolume
        override fun stop() {
            calls += "stop"
        }
        override fun reading(): CastReading? = reading
        override var onEnded: (failed: Boolean) -> Unit = {}
        override suspend fun reachable(): Boolean = reachable
    }

    private val dir = Files.createTempDirectory("chromecast-player").toFile()
    private val port = ServerSocket(0).use { it.localPort }
    private val control = FakeControl()
    private var now = 50_000L

    private fun streams(base: String? = "http://pi:$port") =
        SoundStreams(ProcessedSound("ffmpeg-not-run") { null }, base, port)

    private fun player(streams: SoundStreams = streams()) = ChromecastPlayer("LG TV", control, streams) { now }

    private fun request(
        url: String = "https://example.com/talk.mp3",
        sound: SoundSettings = SoundSettings(),
        picture: Boolean = false,
        startAtMs: Long = 0,
    ) = PlayRequest(url, "10.0.0.5", sound, startAtMs, picture, "Talk")

    @Test
    fun `an unprocessed item is loaded from its own address at its own place and the volume is left alone`() {
        player().play(request(startAtMs = 9_000))

        val load = control.loads.single()
        assertEquals("https://example.com/talk.mp3", load.url)
        assertEquals("audio/mpeg", load.type)
        assertEquals("Talk", load.title)
        assertEquals(9_000L, load.startAtMs)
        assertEquals(listOf("stop"), control.calls)
    }

    @Test
    fun `a boosted item with no picture is loaded from the Pi's processed sound, from the start of it`() {
        val p = player()

        p.play(request(sound = SoundSettings(boost = VolumeBoost.AUTO), startAtMs = 9_000))

        val load = control.loads.single()
        assertTrue(load.url, Regex("""http://pi:$port/s/[0-9a-f]{32}\.aac""").matches(load.url))
        assertEquals("audio/aac", load.type)
        assertEquals(0L, load.startAtMs)
    }

    @Test
    fun `positions on a processed stream add where it started, and its duration is not the TV's`() {
        val p = player()
        p.play(request(sound = SoundSettings(boost = VolumeBoost.AUTO), startAtMs = 9_000))
        control.reading = CastReading(positionMs = 4_000, durationMs = 30_000, playing = true, atMs = now - 1_000)

        assertEquals(9_000L + 5_000L, p.positionMs)
        assertNull(p.durationMs)
    }

    @Test
    fun `a picture is never processed, because the sound would run ahead of the video`() {
        player().play(
            request(
                url = "https://example.com/v.mp4",
                sound = SoundSettings(boost = VolumeBoost.AUTO),
                picture = true,
                startAtMs = 9_000
            )
        )

        val load = control.loads.single()
        assertEquals("https://example.com/v.mp4", load.url)
        assertEquals("video/mp4", load.type)
        assertEquals(9_000L, load.startAtMs)
    }

    @Test
    fun `with no sound server configured a boosted item plays as it comes`() {
        player(streams(base = null)).play(request(sound = SoundSettings(boost = VolumeBoost.AUTO), startAtMs = 3_000))

        val load = control.loads.single()
        assertEquals("https://example.com/talk.mp3", load.url)
        assertEquals(3_000L, load.startAtMs)
    }

    @Test
    fun `a file on the Pi is offered over HTTP, because a TV cannot read a path`() {
        val file = File(dir, "episode.mkv").apply { writeBytes(ByteArray(10)) }

        player().play(request(url = file.path, picture = true))

        val load = control.loads.single()
        assertTrue(load.url, Regex("""http://pi:$port/f/[0-9a-f]{32}\.mkv""").matches(load.url))
        assertEquals("video/x-matroska", load.type)
    }

    @Test
    fun `unprocessed positions are the TV's own and its duration is read`() {
        val p = player()
        p.play(request())
        control.reading = CastReading(positionMs = 4_000, durationMs = 30_000, playing = false, atMs = now - 9_000)

        assertEquals(4_000L, p.positionMs)
        assertEquals(30_000L, p.durationMs)
    }

    @Test
    fun `stopping stops the TV and withdraws what was offered`() {
        val streams = streams()
        val p = player(streams)
        p.play(request(sound = SoundSettings(boost = VolumeBoost.AUTO)))
        assertEquals(1, streams.offered())

        p.stop()

        assertEquals("stop", control.calls.last())
        assertEquals(0, streams.offered())
        assertNull(p.durationMs)
    }

    @Test
    fun `pause, seek and volume go straight to the TV`() {
        val p = player()

        p.pause(true)
        p.seek(7_000)
        p.volume(25)

        assertEquals(listOf("pause=true", "seek=7000", "volume=25"), control.calls)
    }

    @Test
    fun `the volume shown is the TV's own, and unknown until it says`() {
        val p = player()
        assertNull(p.volumePercent)

        control.deviceVolume = 1

        assertEquals(1, p.volumePercent)
    }

    @Test
    fun `a play of any kind of item leaves the TV's volume alone`() {
        val file = File(dir, "episode.mkv").apply { writeBytes(ByteArray(10)) }
        val p = player()

        p.play(request(sound = SoundSettings(boost = VolumeBoost.AUTO)))
        p.play(request(url = file.path, picture = true))
        p.play(request(url = "https://r.googlevideo.com/p?mime=video%2Fmp4", picture = true))

        assertEquals(3, control.loads.size)
        assertTrue(control.calls.toString(), control.calls.none { it.startsWith("volume") })
    }

    @Test
    fun `the ended callback is the control's`() {
        val p = player()
        var failedSeen: Boolean? = null

        p.onEnded = { failedSeen = it }
        control.onEnded(true)

        assertEquals(true, failedSeen)
    }

    @Test
    fun `the content type comes from the address's own mime, else its extension, else picture or not`() {
        assertEquals("audio/aac", contentTypeOf("http://pi/s/x.aac", picture = false, processed = true))
        assertEquals("video/webm", contentTypeOf("https://r.googlevideo.com/p?mime=video%2Fwebm&x=1", true, false))
        assertEquals("audio/mpeg", contentTypeOf("https://a/b.MP3?x=1", false, false))
        assertEquals("audio/mp4", contentTypeOf("https://a/b.m4a", false, false))
        assertEquals("audio/ogg", contentTypeOf("https://a/b.opus", false, false))
        assertEquals("video/webm", contentTypeOf("https://a/b.webm", true, false))
        assertEquals("audio/webm", contentTypeOf("https://a/b.webm", false, false))
        assertEquals("video/mp4", contentTypeOf("https://a/stream", true, false))
        assertEquals("audio/mpeg", contentTypeOf("https://a/stream", false, false))
    }

    @Test
    fun `configured casts become targets named and typed from their names`() = runBlocking {
        val known = knownCastsFrom("LG TV=192.168.0.10; Chromecast Ultra = 192.168.0.11;;broken;=x;y=")
        val found = ChromecastSource(known, streams()).find(fresh = false)

        assertEquals(listOf("lg-tv", "chromecast-ultra"), found.map { it.target.id })
        assertEquals(listOf(TargetKind.TV, TargetKind.CHROMECAST), found.map { it.target.kind })
        assertEquals(listOf("LG TV", "Chromecast Ultra"), found.map { it.target.label })
        assertEquals(emptyList<KnownCasts>(), knownCastsFrom(null))
    }

    @Test
    fun `a cast link is its host when the TV answers and nothing when it does not`() = runBlocking {
        assertEquals("192.168.0.10", CastLink("192.168.0.10", control).connect())
        control.reachable = false
        assertNull(CastLink("192.168.0.10", control).connect())
        assertFalse(control.reachable)
    }
}
