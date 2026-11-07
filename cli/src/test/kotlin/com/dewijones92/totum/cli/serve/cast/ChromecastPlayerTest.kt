package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.cli.serve.sound.ProcessedSound
import com.dewijones92.totum.cli.serve.targets.PlayRequest
import com.dewijones92.totum.data.targets.SoundSettings
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.playback.VolumeBoost
import com.dewijones92.totum.playback.cast.CastItem
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

    private val dir = Files.createTempDirectory("chromecast-player").toFile()
    private val port = ServerSocket(0).use { it.localPort }
    private val control = FakeCastControl()
    private var now = 50_000L

    private fun streams(base: String? = "http://pi:$port") =
        PiStreams(ProcessedSound("ffmpeg-not-run") { null }, base, port)

    private fun player(streams: PiStreams = streams()) = ChromecastPlayer("LG TV", control, streams) { now }

    private fun request(
        url: String = "https://example.com/talk.mp3",
        sound: SoundSettings = SoundSettings(),
        picture: Boolean = false,
        startAtMs: Long = 0,
    ) = PlayRequest(url, "10.0.0.5", sound, startAtMs, picture, "Talk")

    @Test
    fun `an item is loaded as the manifest the phone would build, at its own place, and the volume is left alone`() {
        player().play(request(startAtMs = 9_000))

        val load = control.loads.single()
        assertTrue(load.url, Regex("""http://pi:$port/c/[0-9a-f]{16}/manifest\.mpd""").matches(load.url))
        assertEquals("application/dash+xml", load.type)
        assertEquals("Talk", load.title)
        assertEquals(9_000L, load.startAtMs)
        assertEquals(listOf("stop"), control.calls)
    }

    @Test
    fun `with no sound server configured a boosted item plays as it comes`() {
        player(streams(base = null)).play(request(sound = SoundSettings(boost = VolumeBoost.AUTO), startAtMs = 3_000))

        val load = control.loads.single()
        assertEquals("https://example.com/talk.mp3", load.url)
        assertEquals(3_000L, load.startAtMs)
    }

    @Test
    fun `stopping stops the TV and withdraws the relay it was given`() {
        val streams = streams()
        val p = player(streams)
        p.play(request(url = youtube, picture = true))
        assertEquals(1, streams.relayed())

        p.stop()

        assertEquals("stop", control.calls.last())
        assertEquals(0, streams.relayed())
        assertNull(p.durationMs)
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
    fun `a live manifest marks the player live until it stops`() {
        val p = player()
        assertFalse(p.live)

        p.play(request().copy(cast = CastItem("radio", "http://127.0.0.1:9/live/abc.mpd")))
        assertTrue(p.live)

        p.stop()
        assertFalse(p.live)
    }

    @Test
    fun `handing the target back asks the TV to quit what Totum launched`() = runBlocking {
        CastLink("tv.local", control).release()

        assertEquals(listOf("release"), control.calls)
    }

    @Test
    fun `pause, seek and volume go straight to the TV`() {
        val p = player()

        p.pause(true)
        p.seek(7_000)
        p.volume(25)

        assertEquals(listOf("pause=true", "seek=7000", "volume=25"), control.calls)
    }

    private val youtube = "https://rr1---sn-aaa.googlevideo.com/videoplayback?expire=1&mime=video%2Fmp4&clen=1000"

    @Test
    fun `a YouTube picture is loaded from the Pi's own relay, which fetches it in ranges, not from YouTube`() {
        val p = player()

        p.play(request(url = youtube, picture = true, startAtMs = 4_000))

        val load = control.loads.single()
        assertTrue(load.url, Regex("""http://pi:$port/y/[0-9a-f]{32}\.mp4""").matches(load.url))
        assertEquals("video/mp4", load.type)
        assertEquals(4_000L, load.startAtMs)
    }

    @Test
    fun `a YouTube sound is a manifest of processed segments read from the Pi's relay, withdrawn on stop`() {
        val streams = streams()
        val p = player(streams)

        p.play(request(url = youtube.replace("video%2Fmp4", "audio%2Fmp4")))

        val load = control.loads.single()
        assertTrue(load.url, Regex("""http://pi:$port/c/[0-9a-f]{16}/manifest\.mpd""").matches(load.url))
        assertEquals("application/dash+xml", load.type)
        assertEquals(1, streams.relayed())

        p.stop()

        assertEquals(0, streams.relayed())
    }

    @Test
    fun `with no address the TV can reach, a YouTube address is given as it is`() {
        player(streams(base = null)).play(request(url = youtube, picture = true))

        assertEquals(youtube, control.loads.single().url)
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
