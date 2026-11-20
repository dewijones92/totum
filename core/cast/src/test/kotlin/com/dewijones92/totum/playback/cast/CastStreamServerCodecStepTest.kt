package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.domain.CastScreen
import com.dewijones92.totum.domain.VideoCodecFamily
import com.dewijones92.totum.playback.CastCodecStep
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.ServerSocket

class CastStreamServerCodecStepTest {

    private val port = ServerSocket(0).use { it.localPort }
    private val host = FakeCastHost("http://pi:$port")
    private val tv = FakeCastTv(CastScreen.of("Chromecast"))
    private val timers = java.util.concurrent.CopyOnWriteArrayList<Pair<Long, () -> Unit>>()
    private val store = MemoryRefusals()
    private val refusals = CodecRefusals(store)
    private val steps = mutableListOf<Pair<String, CastStep>>()

    private val vp9 = CastRung("https://rr.googlevideo.com/vp9", 1080, "vp09.00.40.08")
    private val avc = CastRung("https://rr.googlevideo.com/avc", 1080, "avc1.64001e")
    private val ladder = listOf(vp9, avc)

    private val server = CastStreamServer(
        engines = { FakeAudioEngine() },
        skipSilence = { false },
        host = host,
        tvs = tv,
        tools = null,
        port = port,
        upstream = { _, _ -> bytes() },
        refusals = refusals,
        probeRung = ::layoutOf,
        later = { delay, run -> timers += delay to run },
        startWaitMs = WAIT_MS,
    ).also { it.onTvNotStarting = { id, step -> steps += id to step } }

    private class MemoryRefusals : RefusalStore {
        var saved: Map<String, Set<VideoCodecFamily>> = emptyMap()
        override fun load() = saved
        override fun save(refusals: Map<String, Set<VideoCodecFamily>>) {
            saved = refusals
        }
    }

    @After
    fun tidy() = server.stopAll()

    private fun layoutOf(rung: CastRung): StreamLayout {
        val webm = rung.codec!!.startsWith("vp")
        return StreamLayout(
            if (webm) Container.WEBM else Container.MP4,
            rung.codec!!,
            ByteSpan(0, 219),
            ByteSpan(220, 4_000),
            width = rung.height * 16 / 9,
            height = rung.height,
        )
    }

    private fun bytes(): UpstreamReply {
        val body = ByteArray(RANGE_BYTES)
        return UpstreamReply(PARTIAL, "video/webm", "bytes 0-${body.size - 1}/$TOTAL", body.size.toLong()) {
            ByteArrayInputStream(body)
        }
    }

    private fun item() = CastItem(
        "yt",
        "https://rr.googlevideo.com/videoplayback?id=1",
        separateAudioUrl = "https://rr.googlevideo.com/audio",
        ladder = ladder,
        durationMs = 6_000,
    )

    private fun get(url: String, range: String? = null) =
        fetchFrom(url.replace("http://pi:$port", "http://127.0.0.1:$port"), range)

    private fun manifest(url: String) = String(get(url).second)

    private fun runStartWatch() {
        timers.filter { it.first == WAIT_MS }.also { timers.removeAll(it) }.forEach { it.second() }
    }

    @Test
    fun `a model nobody has measured is sent H264 first, not VP9`() {
        val url = server.urlFor(item())!!

        val mpd = manifest(url)

        assertTrue(mpd, mpd.contains("codecs=\"avc1.64001e\""))
        assertFalse(mpd, mpd.contains("vp09"))
        assertEquals(VideoCodecFamily.AVC, tv.sent["yt"]!!.family)
    }

    @Test
    fun `the Chromecast Ultra keeps VP9 first`() {
        tv.screen = CastScreen.of("Chromecast Ultra")

        val mpd = manifest(server.urlFor(item())!!)

        assertTrue(mpd, mpd.contains("codecs=\"vp09.00.40.08\""))
    }

    @Test
    fun `a TV that fetches the picture and then nothing is moved to the next codec`() {
        tv.screen = CastScreen("Odd TV", setOf(VideoCodecFamily.VP9, VideoCodecFamily.AVC), 1080, known = true)
        val url = server.urlFor(item())!!
        val base = url.substringBeforeLast('/')
        assertTrue(manifest(url).contains("codecs=\"vp09.00.40.08\""))
        get("$base/video/r0", "bytes=0-219")
        get("$base/video/r0", "bytes=220-4000")

        runStartWatch()

        val (id, step) = steps.single()
        assertEquals("yt", id)
        assertEquals(VideoCodecFamily.VP9, step.refusedCodec)
        assertEquals(VideoCodecFamily.AVC, step.toCodec)
        assertEquals(CastCodecStep(VideoCodecFamily.VP9, VideoCodecFamily.AVC), tv.codecSteps["yt"])
        assertEquals(setOf(VideoCodecFamily.VP9), refusals.refusedFor("Odd TV"))
        assertTrue("not remembered until another codec plays", store.saved.isEmpty())

        val resent = manifest(server.urlFor(item())!!)
        assertTrue(resent, resent.contains("codecs=\"avc1.64001e\""))
    }

    @Test
    fun `the refusal is remembered only once the other codec is really fetched by the TV`() {
        tv.screen = CastScreen("Odd TV", setOf(VideoCodecFamily.VP9, VideoCodecFamily.AVC), 1080, known = true)
        val first = server.urlFor(item())!!
        manifest(first)
        get("${first.substringBeforeLast('/')}/video/r0", "bytes=0-219")
        runStartWatch()
        val second = server.urlFor(item())!!
        manifest(second)

        get("${second.substringBeforeLast('/')}/audio/init.mp4")

        assertEquals(mapOf("odd tv" to setOf(VideoCodecFamily.VP9)), store.saved)
        val later = CodecRefusals(store)
        assertEquals(setOf(VideoCodecFamily.VP9), later.refusedFor("Odd TV"))
    }

    @Test
    fun `ending the cast drops what was only tried, even after the TV has gone from the port`() {
        tv.screen = CastScreen("Odd TV", setOf(VideoCodecFamily.VP9, VideoCodecFamily.AVC), 1080, known = true)
        val url = server.urlFor(item())!!
        manifest(url)
        get("${url.substringBeforeLast('/')}/video/r0", "bytes=0-219")
        runStartWatch()
        assertEquals(setOf(VideoCodecFamily.VP9), refusals.refusedFor("Odd TV"))

        tv.screen = null
        server.stopAll()

        assertTrue(refusals.refusedFor("Odd TV").isEmpty())
    }

    @Test
    fun `a TV that goes on to ask for sound is left alone`() {
        tv.screen = CastScreen("Odd TV", setOf(VideoCodecFamily.VP9, VideoCodecFamily.AVC), 1080, known = true)
        val url = server.urlFor(item())!!
        val base = url.substringBeforeLast('/')
        manifest(url)
        get("$base/video/r0", "bytes=0-219")
        get("$base/audio/init.mp4")

        runStartWatch()

        assertTrue(steps.isEmpty())
        assertTrue(refusals.refusedFor("Odd TV").isEmpty())
    }

    @Test
    fun `with no other codec left to try the picture stays and nothing is re-sent`() {
        tv.screen = CastScreen("Only VP9", setOf(VideoCodecFamily.VP9), 1080, known = true)
        val url = server.urlFor(item())!!
        manifest(url)
        get("${url.substringBeforeLast('/')}/video/r0", "bytes=0-219")

        runStartWatch()

        assertTrue(steps.isEmpty())
        assertNull(tv.codecSteps["yt"])
        assertTrue(refusals.refusedFor("Only VP9").isEmpty())
    }

    @Test
    fun `a ladder holding only the refused codec stays too, and says so`() {
        tv.screen = CastScreen("Odd TV", setOf(VideoCodecFamily.VP9, VideoCodecFamily.AVC), 1080, known = true)
        val onlyVp9 = item().copy(ladder = listOf(vp9))
        val url = server.urlFor(onlyVp9)!!
        manifest(url)
        get("${url.substringBeforeLast('/')}/video/r0", "bytes=0-219")

        runStartWatch()

        assertTrue(steps.isEmpty())
    }

    @Test
    fun `it is tried at most twice for one item`() {
        tv.screen = CastScreen(
            "Three TV",
            setOf(VideoCodecFamily.VP9, VideoCodecFamily.AVC, VideoCodecFamily.HEVC),
            1080,
            known = true,
        )
        val hevc = CastRung("https://rr.googlevideo.com/hevc", 1080, "hvc1.1.6.L93")
        val all = item().copy(ladder = listOf(vp9, avc, hevc))
        repeat(3) {
            val url = server.urlFor(all)!!
            manifest(url)
            get("${url.substringBeforeLast('/')}/video/r0", "bytes=0-219")
            runStartWatch()
        }

        assertEquals(2, steps.size)
    }

    @Test
    fun `nothing is watched where nothing can re-send the item`() {
        server.onTvNotStarting = null
        tv.screen = CastScreen("Odd TV", setOf(VideoCodecFamily.VP9, VideoCodecFamily.AVC), 1080, known = true)
        val url = server.urlFor(item())!!
        manifest(url)
        get("${url.substringBeforeLast('/')}/video/r0", "bytes=0-219")

        assertTrue("no start watch armed", timers.none { it.first == WAIT_MS })
        runStartWatch()

        assertTrue(steps.isEmpty())
    }

    private companion object {
        const val WAIT_MS = 8_000L
        const val PARTIAL = 206
        const val RANGE_BYTES = 64
        const val TOTAL = 10_000
    }
}
