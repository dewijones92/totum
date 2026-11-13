package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.cli.serve.sound.ProcessedSound
import com.dewijones92.totum.cli.serve.targets.PlayRequest
import com.dewijones92.totum.data.targets.SoundSettings
import com.dewijones92.totum.playback.cast.CastAudio
import com.dewijones92.totum.playback.cast.CastItem
import com.dewijones92.totum.playback.cast.CastRung
import com.dewijones92.totum.playback.cast.CastVideo
import com.dewijones92.totum.playback.cast.DashManifest
import com.dewijones92.totum.playback.cast.LocalManifests
import com.dewijones92.totum.playback.cast.StreamLayouts
import com.dewijones92.totum.playback.cast.Upstream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket

class CastBrainTest {

    private val fixtures = CastFixtures()
    private val port = ServerSocket(0).use { it.localPort }
    private val control = FakeCastControl()
    private val streams = PiStreams(ProcessedSound(weights = { null }), "http://pi:$port", port)
    private val player = ChromecastPlayer("LG TV", control, streams)
    private val origins = mutableListOf<FakeOrigin>()

    @After
    fun tidy() {
        player.stop()
        origins.forEach { it.close() }
    }

    private fun origin(files: Map<String, File>) = FakeOrigin(files).also { origins += it }

    private fun request(cast: CastItem, startAtMs: Long = 0, sound: SoundSettings = SoundSettings()) =
        PlayRequest(cast.uri, "10.0.0.5", sound, startAtMs, picture = !cast.listening, title = "Item", cast = cast)

    private fun local(load: Loaded, path: String = "manifest.mpd") =
        load.url.replace("http://pi:$port", "http://127.0.0.1:$port").substringBeforeLast('/') + "/$path"

    @Test
    fun `a normal video loads exactly the manifest the phone builds for the same item`() {
        val video = fixtures.webm("v.webm", 2)
        val sound = fixtures.tone("a.wav", 2)
        val site = origin(mapOf("v" to video, "a" to sound))
        val videoUrl = site.url("v")
        val cast = CastItem(
            mediaId = "yt1",
            uri = videoUrl,
            separateAudioUrl = site.url("a"),
            ladder = listOf(CastRung(videoUrl, 180, "vp9")),
            durationMs = 2_000,
        )

        player.play(request(cast, startAtMs = 1_000))

        val load = control.loads.single()
        assertEquals("application/dash+xml", load.type)
        assertEquals(1_000L, load.startAtMs)
        assertTrue(load.url, Regex("""http://pi:$port/c/[0-9a-f]{16}/manifest\.mpd""").matches(load.url))
        val layout = StreamLayouts.probe { span -> Upstream.bytes(videoUrl, span) }
        val expected = DashManifest.build(
            2_000,
            CastVideo.Ladder(
                listOf(CastVideo.Relayed(layout, "video/r0", DashManifest.bandwidthFor(videoUrl, layout.height))),
            ),
            CastAudio.Processed(
                initUrl = "audio/init.mp4",
                mediaTemplate = "audio/\$Number\$.m4s",
                sampleRate = 48_000,
                channels = 2,
                codecs = "mp4a.40.2",
                segmentSamples = 188L * 1024,
            ),
            emptyList(),
        )
        assertEquals(expected, fetch(local(load)).text)
    }

    @Test
    fun `a normal video's picture is the original packets relayed by range, and its sound is processed AAC segments`() {
        val video = fixtures.webm("v.webm", 2)
        val site = origin(mapOf("v" to video, "a" to fixtures.tone("a.wav", 2)))
        val cast = CastItem(
            "yt1",
            site.url("v"),
            site.url("a"),
            ladder = listOf(CastRung(site.url("v"), 180, "vp9")),
            durationMs = 2_000,
        )
        player.play(request(cast))
        val load = control.loads.single()

        val picture = fetch(local(load, "video/r0"), range = "bytes=0-99")
        assertEquals(206, picture.status)
        assertEquals(video.readBytes().copyOfRange(0, 100).toList(), picture.body.toList())
        val init = fetch(local(load, "audio/init.mp4"))
        assertEquals("ftyp", String(init.body, 4, 4))
        val first = fetch(local(load, "audio/0.m4s"))
        assertEquals(200, first.status)
        assertEquals("moof", String(first.body, 4, 4))
        assertTrue(first.text.contains("mdat"))
    }

    @Test
    fun `a sound-only item is the same manifest with no picture, its sound processed`() {
        val tone = fixtures.tone("t.wav", 9)
        val cast = CastItem("pod1", tone.path, listening = true, durationMs = 9_000)

        player.play(request(cast, sound = SoundSettings()))

        val load = control.loads.single()
        assertEquals("application/dash+xml", load.type)
        val manifest = fetch(local(load)).text
        assertFalse(manifest, manifest.contains("contentType=\"video\""))
        assertTrue(manifest, manifest.contains("contentType=\"audio\""))
        assertTrue(manifest, manifest.contains("audioSamplingRate=\"48000\""))
        assertTrue(manifest, manifest.contains("mediaPresentationDuration=\"PT9.000S\""))
        val segments = (0..2).map { fetch(local(load, "audio/$it.m4s")) }
        assertTrue(segments.all { it.status == 200 && String(it.body, 4, 4) == "moof" })
    }

    @Test
    fun `a live item loads the live manifest, with the segments relayed by the Pi`() {
        val site = origin(emptyMap())
        val upstream = "http://127.0.0.1:${site.port}/live-0?host=googlevideo.com"
        val published = LocalManifests.publish(
            "live1",
            """<MPD type="dynamic"><Period><AdaptationSet><Representation id="1">""" +
                """<SegmentTemplate media="${upstream.replace("&", "&amp;")}&amp;sq=${'$'}Number${'$'}"/>""" +
                "</Representation></AdaptationSet></Period></MPD>",
        )
        val cast = CastItem("live1", published.value)

        player.play(request(cast))

        val load = control.loads.single()
        assertEquals("application/dash+xml", load.type)
        assertTrue(load.url, Regex("""http://pi:$port/c/[0-9a-f]{16}/live\.mpd""").matches(load.url))
        val manifest = fetch(local(load, "live.mpd")).text
        assertTrue(manifest, manifest.contains("media=\"live/0/\$Number\$\""))
        assertEquals("segment-7", fetch(local(load, "live/0/7")).text)
    }

    @Test
    fun `a live stream on a speaker gets the same live manifest from the core, on loopback`() {
        val site = origin(emptyMap())
        val published = LocalManifests.publish(
            "live2",
            """<MPD type="dynamic"><Period><AdaptationSet><Representation id="1"><SegmentTemplate media=""" +
                """"http://127.0.0.1:${site.port}/live-0?host=googlevideo.com&amp;sq=${'$'}Number${'$'}"/>""" +
                "</Representation></AdaptationSet></Period></MPD>",
        )

        val address = streams.locally("Speaker", published.value)

        assertTrue(address, Regex("""http://127\.0\.0\.1:\d+/c/[0-9a-f]{16}/live\.mpd""").matches(address))
        assertEquals("segment-3", fetch(address.replace("live.mpd", "live/0/3")).text)
    }

    @Test
    fun `the load tells the receiver it is a manifest and a buffered stream, as the phone's does`() {
        val line = CastWire.load("http://pi/c/x/live.mpd", "application/dash+xml", "Live", 0)

        assertTrue(line, line.contains("\"type\":\"application/dash+xml\""))
        assertTrue(line, line.contains("\"streamType\":\"BUFFERED\""))
    }

    @Test
    fun `a TV the Pi cannot describe is treated as the phone treats an unknown one, and says so in the log only`() {
        control.model = null
        val site = origin(mapOf("a" to fixtures.tone("a.wav", 2)))

        player.play(request(CastItem("x", site.url("a"), listening = true, durationMs = 2_000)))

        assertEquals(1, control.loads.size)
        assertEquals(null, player.notice)
    }

    private fun cutting(): Pair<ChromecastPlayer, () -> Boolean> {
        var check: (() -> Boolean)? = null
        val cutter = ChromecastPlayer("LG TV", control, streams, clock = { 1_000L }, watch = { check = it })
        val clip = fixtures.toneSilenceTone("gap.wav")
        val cast = CastItem("cut1", clip.path, listening = true, skipsSilence = true, durationMs = 10_000)
        cutter.play(request(cast, sound = SoundSettings(skipSilence = true)))
        val load = control.loads.single()
        fetch(local(load, "audio/0.m4s"))
        fetch(local(load, "audio/1.m4s"))
        repeat(50) { if (streams.timelineFor("LG TV|cut1")?.endTvUs == null) Thread.sleep(100) }
        return cutter to { check!!() }
    }

    @Test
    fun `with silences cut the position is the source's and a seek is the TV's`() {
        val (cutter, _) = cutting()
        val endMs = streams.timelineFor("LG TV|cut1")!!.endTvUs!! / 1_000
        control.reading = CastReading(positionMs = endMs, durationMs = 10_000, playing = false, atMs = 1_000)

        assertTrue("tv=$endMs source=${cutter.positionMs}", cutter.positionMs!! > endMs + 1_000)
        cutter.seek(11_000)

        val tvSeek = control.calls.last().removePrefix("seek=").toLong()
        assertTrue("$tvSeek", tvSeek < 8_000)
        cutter.stop()
    }

    @Test
    fun `a cut end that races with the next item loading does not stop the new item`() {
        val (cutter, check) = cutting()
        val end = streams.timelineFor("LG TV|cut1")!!.endTvUs!!
        var ended: Boolean? = null
        cutter.onEnded = { ended = it }
        control.reading = CastReading(positionMs = end / 1_000, durationMs = 10_000, playing = true, atMs = 1_000)
        val next = CastItem("next", fixtures.tone("n.wav", 2).path, listening = true, durationMs = 2_000)
        control.onReading = {
            control.onReading = {}
            cutter.play(request(next))
        }

        check()

        assertEquals("the new item was loaded once and left playing", 2, control.loads.size)
        assertEquals(1, control.calls.count { it == "stop" } - 1)
        assertEquals(null, ended)
        cutter.stop()
    }

    @Test
    fun `when the cut audio ends before the source does the TV is stopped and the queue moves on`() {
        val (cutter, check) = cutting()
        val end = streams.timelineFor("LG TV|cut1")!!.endTvUs!!
        var ended: Boolean? = null
        cutter.onEnded = { ended = it }
        control.reading = CastReading(positionMs = end / 1_000, durationMs = 10_000, playing = true, atMs = 1_000)

        assertTrue(check())

        assertEquals("stop", control.calls.last())
        assertEquals(false, ended)
    }
}
