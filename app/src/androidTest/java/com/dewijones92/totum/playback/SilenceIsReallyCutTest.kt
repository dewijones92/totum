package com.dewijones92.totum.playback

import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.MainActivity
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Breadcrumbs
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.support.GappedWav
import com.dewijones92.totum.support.PlaybackWaits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Collections

class SilenceIsReallyCutTest {

    @get:Rule
    val activity = ActivityScenarioRule(MainActivity::class.java)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as TotumApplication
    private val container get() = app.container
    private val controller get() = container.playbackController
    private val queue get() = container.playbackQueue

    private lateinit var wav: File
    private lateinit var video: File
    private lateinit var mp3: File
    private lateinit var aac: File
    private lateinit var quiet: File
    private var autoPlayNextBefore = true

    @Before
    fun setUp() {
        wav = File(context.cacheDir, "gapped.wav").apply {
            writeBytes(GappedWav.bytes(bursts = BURSTS, toneMs = TONE_MS, gapMs = GAP_MS))
        }
        video = asset("gaps.mp4")
        mp3 = asset("gaps.mp3")
        aac = asset("gaps.m4a")
        quiet = asset("gaps-quiet.mp3")
        runBlocking(Dispatchers.Main) {
            withTimeoutOrNull(TIMEOUT_MS) { while (controller.player == null) delay(PlaybackWaits.POLL_MS) }
            assertNotNull("the media controller never connected", controller.player)
            autoPlayNextBefore = container.appPreferences.settings.value.autoPlayNext
            container.appPreferences.setAutoPlayNext(false)
            controller.setSpeed(1f)
            queue.clear()
            controller.player?.stop()
            controller.player?.clearMediaItems()
        }
    }

    @After
    fun tearDown() = runBlocking(Dispatchers.Main) {
        controller.setSkipSilence(false)
        controller.setVolumeBoost(VolumeBoost.OFF)
        controller.setSpeed(1f)
        container.appPreferences.setAutoPlayNext(autoPlayNextBefore)
        queue.clear()
        controller.player?.stop()
        controller.player?.clearMediaItems()
        listOf(wav, video, mp3, aac, quiet).forEach(File::delete)
        Unit
    }

    @Test
    fun `a podcast with skip-silence on plays its sound and little else`() = runBlocking(Dispatchers.Main) {
        controller.setSkipSilence(true)
        val wall = playToTheEnd("gapped-wav", wav)
        assertCut("podcast", wall, SOUND_MS)
    }

    @Test
    fun `an mp3 podcast with skip-silence on plays its sound and little else`() = runBlocking(Dispatchers.Main) {
        controller.setSkipSilence(true)
        val wall = playToTheEnd("gapped-mp3", mp3)
        assertCut("mp3 podcast", wall, SOUND_MS)
    }

    @Test
    fun `a stereo aac podcast with skip-silence on plays its sound and little else`() = runBlocking(Dispatchers.Main) {
        controller.setSkipSilence(true)
        val wall = playToTheEnd("gapped-aac", aac)
        assertCut("aac podcast", wall, SOUND_MS)
    }

    @Test
    fun `a quiet podcast with the boost on comes up loud and still has its pauses cut`() = runBlocking(
        Dispatchers.Main
    ) {
        controller.setVolumeBoost(VolumeBoost.AUTO)
        controller.setSkipSilence(true)
        val id = "gapped-quiet"
        queue.playNow(item(id, quiet))
        awaitMoving(id)
        Breadcrumbs.clear()
        val wall = timeToEnd(id)
        val boostLines = Breadcrumbs.snapshot().map { it.message }.filter { "auto gain" in it }
        val gainDb = boostLines.mapNotNull { GAIN.find(it)?.groupValues?.get(1)?.toFloatOrNull() }.maxOrNull()

        assertCut("quiet podcast, boosted", wall, SOUND_MS)
        assertTrue("the boost never said what it was doing: $boostLines", gainDb != null)
        assertTrue(
            "a recording this quiet should get nearly all of the +30dB, got $gainDb",
            gainDb!! >= MIN_QUIET_GAIN_DB
        )
        assertTrue("the boost clipped: $boostLines", boostLines.all { "clipped=0" in it })
    }

    @Test
    fun `the gaps stay cut after a seek`() = runBlocking(Dispatchers.Main) {
        controller.setSkipSilence(true)
        val id = "gapped-wav-seek"
        queue.playNow(item(id, wav))
        awaitMoving(id)
        controller.player?.seekTo(SEEK_TO_MS)
        val wall = timeToEnd(id)
        assertCut("after a seek", wall, SOUND_AFTER_SEEK_MS)
    }

    @Test
    fun `the gaps stay cut after the speed changes`() = runBlocking(Dispatchers.Main) {
        controller.setSkipSilence(true)
        val id = "gapped-wav-speed"
        queue.playNow(item(id, wav))
        awaitMoving(id)
        controller.setSpeed(FAST)
        val wall = timeToEnd(id)
        assertCut("after a speed change", wall, (SOUND_MS / FAST).toLong())
    }

    @Test
    fun `a video with skip-silence on plays its sound and little else`() = runBlocking(Dispatchers.Main) {
        controller.setSkipSilence(true)
        val wall = playToTheEnd("gapped-video", video)
        assertCut("video", wall, SOUND_MS)
    }

    @Test
    fun `a video shows the frames of its sound and skips the frames of its pauses`() = runBlocking(Dispatchers.Main) {
        val id = "gapped-video-picture"
        val shown = Collections.synchronizedList(mutableListOf<Int>())
        val timeline = Collections.synchronizedList(mutableListOf<Long>())
        val thread = HandlerThread("frames").apply { start() }
        val reader = ImageReader.newInstance(FRAME_EDGE, FRAME_EDGE, ImageFormat.YUV_420_888, READER_IMAGES)
        reader.setOnImageAvailableListener(
            {
                it.acquireLatestImage()?.use { image ->
                    shown += frameIndexOf(image)
                    timeline += SystemClock.elapsedRealtime()
                }
            },
            Handler(thread.looper),
        )
        try {
            controller.player?.setVideoSurface(reader.surface)
            controller.setSkipSilence(true)
            val wall = playToTheEnd(id, video)
            val frames = shown.toList()
            val times = timeline.toList()
            frames.indices.chunked(LOG_CHUNK).forEach { chunk ->
                Log.i(
                    TAG,
                    "dewidebug silence-cut frames " +
                        chunk.joinToString(" ") { "${times[it] - times[0]}:${frames[it]}" }
                )
            }
            val inSound = frames.count { (it % FRAMES_PER_BURST) < FRAMES_PER_TONE }
            val perBurst = frames.groupingBy { it / FRAMES_PER_BURST }.eachCount().toSortedMap()
            Log.i(
                TAG,
                "dewidebug silence-cut picture shown=${frames.size} inSound=$inSound " +
                    "perBurst=$perBurst last=${frames.lastOrNull()}"
            )
            assertTrue(
                "no frame was ever drawn, so nothing here says anything about the picture",
                frames.size >= MIN_FRAMES_SHOWN
            )
            assertTrue(
                "the picture fell behind the sound: only $inSound of ${frames.size} drawn frames were from the " +
                    "sound, the rest from pauses the sound had already cut ($perBurst)",
                inSound >= frames.size * IN_SOUND_SHARE,
            )
            assertTrue(
                "the picture never reached the end with the sound (last frame ${frames.lastOrNull()})",
                (frames.lastOrNull() ?: 0) >= LAST_BURST_FIRST_FRAME,
            )
            assertCut("video with a picture", wall, SOUND_MS)
        } finally {
            controller.player?.clearVideoSurface()
            reader.close()
            thread.quitSafely()
        }
    }

    private fun frameIndexOf(image: Image): Int {
        val plane = image.planes[0]
        fun luma(
            y: Int
        ): Int = plane.buffer.get(y * plane.rowStride + image.width / 2 * plane.pixelStride).toInt() and 0xFF
        val coarse = Math.round((luma(image.height / 4) - LUMA_FLOOR) / LUMA_STEP.toFloat())
        val fine = Math.round((luma(image.height * 3 / 4) - LUMA_FLOOR) / LUMA_STEP.toFloat())
        return coarse * FINE_STEPS + fine
    }

    private suspend fun playToTheEnd(id: String, file: File): Long {
        queue.playNow(item(id, file))
        awaitMoving(id)
        return timeToEnd(id)
    }

    private suspend fun awaitMoving(id: String) {
        val moving = PlaybackWaits.awaitStateOf(controller, MediaItemId(id), TIMEOUT_MS) {
            it.isPlaying && it.positionMs > 0
        }
        assertNotNull("$id never started; playing ${PlaybackWaits.whatIsActuallyPlaying(controller)}", moving)
    }

    private fun asset(name: String): File = File(context.cacheDir, name).also { file ->
        instrumentation.context.assets.open(name).use { input -> file.outputStream().use(input::copyTo) }
    }

    private suspend fun timeToEnd(id: String): Long {
        Breadcrumbs.clear()
        val start = SystemClock.elapsedRealtime()
        var nextLog = start
        val ended = PlaybackWaits.awaitStateOf(controller, MediaItemId(id), TIMEOUT_MS) {
            val now = SystemClock.elapsedRealtime()
            if (now >= nextLog) {
                nextLog = now + PROGRESS_LOG_MS
                Log.i(
                    TAG,
                    "dewidebug silence-cut $id at ${now - start}ms position=${it.positionMs} " +
                        "playing=${it.isPlaying} buffering=${it.isBuffering}"
                )
            }
            it.hasEnded
        }
        val wall = SystemClock.elapsedRealtime() - start
        Log.i(TAG, "dewidebug silence-cut $id ended=${ended != null} wall=${wall}ms media=${MEDIA_MS}ms")
        assertNotNull("$id never reached its end in ${TIMEOUT_MS}ms", ended)
        val dropouts = Breadcrumbs.snapshot().map { it.message }.filter { "audio underrun" in it }
        val stalls = dropouts.mapNotNull {
            UNDERRUN_AT.find(
                it
            )?.groupValues?.get(1)?.toLong()?.div(STALL_BUCKET_MS)
        }.toSet()
        assertTrue(
            "$id: the sound broke up at ${stalls.size} separate points while its pauses were cut: $dropouts",
            stalls.size <= STALLS_ALLOWED,
        )
        return wall
    }

    private fun assertCut(what: String, wallMs: Long, soundMs: Long) {
        val allowed = soundMs + GAPS * KEPT_PER_GAP_MS + STARTUP_SLACK_MS
        assertTrue(
            "$what: took ${wallMs}ms to play ${soundMs}ms of sound, so the gaps were not cut " +
                "(allowed ${allowed}ms; uncut would be about ${soundMs + GAPS * GAP_MS}ms)",
            wallMs <= allowed,
        )
    }

    private fun item(id: String, file: File) = PlayableItem(
        item = MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("test"),
            title = id,
            publishedAt = null,
            duration = null,
            mediaUrl = null,
        ),
        handle = PlayHandle.Podcast(file.absolutePath),
    )

    private companion object {
        const val TAG = "SilenceIsReallyCut"
        const val BURSTS = 8
        const val GAPS = BURSTS
        const val TONE_MS = 1_000
        const val GAP_MS = 2_000
        const val MEDIA_MS = BURSTS * (TONE_MS + GAP_MS)
        const val SOUND_MS = (BURSTS * TONE_MS).toLong()
        const val SEEK_TO_MS = 3_000L
        const val SOUND_AFTER_SEEK_MS = ((BURSTS - 1) * TONE_MS).toLong()
        const val FAST = 2f
        const val KEPT_PER_GAP_MS = 100L
        const val STARTUP_SLACK_MS = 1_200L
        const val TIMEOUT_MS = 60_000L
        const val FRAME_EDGE = 64
        const val READER_IMAGES = 4
        const val FRAMES_PER_BURST = 45
        const val FRAMES_PER_TONE = 15
        const val LAST_BURST_FIRST_FRAME = (BURSTS - 1) * FRAMES_PER_BURST
        const val MIN_FRAMES_SHOWN = 60
        const val IN_SOUND_SHARE = 0.85
        const val LUMA_FLOOR = 16
        const val LUMA_STEP = 10
        const val FINE_STEPS = 20
        const val LOG_CHUNK = 40
        const val PROGRESS_LOG_MS = 500L
        const val MIN_QUIET_GAIN_DB = 25f
        val GAIN = Regex("auto gain (-?[0-9.]+)dB")
        val UNDERRUN_AT = Regex("audio underrun #[0-9]+ at ([0-9]+)ms")
        const val STALL_BUCKET_MS = 1_000L
        const val STALLS_ALLOWED = 2
    }
}
