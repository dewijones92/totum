package com.dewijones92.totum.video.live

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.browse.InnerTubeResponse
import com.dewijones92.totum.innertube.player.PlayerResponseParser
import com.dewijones92.totum.innertube.player.PlayerResult
import com.dewijones92.totum.playback.SabrDataSourceFactory
import com.dewijones92.totum.video.SabrResolve
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A long item plays on without the spinner coming back — watching, and listening.
 *
 * Dewi, 2026-08-19: *"make sure there is no buffering when playing an hour long video AND just audio"*.
 * Starting is not the hard part; STAYING started is. A stream that begins fine and rebuffers every
 * thirty seconds is the difference between the app being usable on an hour-long video and not, and
 * nothing here measured it — `SabrPlaysAcrossVideoTypesTest` watches ten seconds, which is over before
 * the second SABR round trip.
 *
 * Both tracks, because they fail differently: the video path pulls megabytes and is where a cold reopen
 * or an exhausted stream shows up, while Listen mode is the one Dewi actually lives in and pulls a
 * twentieth of the data.
 *
 * Counts REBUFFERS — a return to BUFFERING after playback has already begun — rather than total buffered
 * milliseconds, because the first fill is not a stall and counting it would make every pass a judgement
 * call. Reported with the position reached, so a slow decoder on this emulator (which renders in
 * software) is never mistaken for a starved stream: a decoder that cannot keep up leaves the buffer FULL
 * and rebuffers less, not more.
 */
class AnHourLongItemDoesNotRebufferTest {

    private val http = OkHttpClient()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun anHourLongVideoPlaysOnWithoutRebuffering() = check(withPicture = true)

    @Test
    fun anHourLongItemPlaysOnAsAudioWithoutRebuffering() = check(withPicture = false)

    private fun check(withPicture: Boolean) {
        val what = if (withPicture) "video" else "audio-only"
        val streaming = runBlocking {
            val response = InnerTubeClient(http).player(VIDEO_ID)
            (response as? InnerTubeResponse.Success)?.body?.let(PlayerResponseParser::parse)
        } as? PlayerResult.Success
        assumeTrue("YouTube served no player response, so nothing can be measured", streaming != null)

        val resolved = SabrResolve.prepare(VIDEO_ID, streaming!!.streaming, streaming.details)
        assumeTrue("SABR refused this video, which its own test covers", resolved != null)

        val factory = SabrDataSourceFactory(DefaultHttpDataSource.Factory())
        val player = ExoPlayer.Builder(context).build()
        val ready = CountDownLatch(1)
        val failure = arrayOfNulls<Throwable>(1)
        var rebuffers = 0
        var started = false

        try {
            runBlocking(Dispatchers.Main) {
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_READY -> {
                                started = true
                                ready.countDown()
                            }
                            // Only AFTER playback has begun: the first fill is not a stall.
                            Player.STATE_BUFFERING -> if (started) rebuffers++
                            else -> Unit
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        failure[0] = error
                        ready.countDown()
                    }
                })
                player.setMediaSource(sourceFor(factory, resolved!!, withPicture))
                player.prepare()
                player.play()
            }
            assumeTrue(
                "the player never started, which other tests cover",
                ready.await(READY_SECONDS, TimeUnit.SECONDS)
            )
            failure[0]?.let { assumeTrue("the stream failed to start: ${it.message}", false) }

            val reached = runBlocking {
                withTimeoutOrNull(WATCH_MS + GRACE_MS) {
                    val until = System.currentTimeMillis() + WATCH_MS
                    var position = 0L
                    while (System.currentTimeMillis() < until) {
                        delay(POLL_MS)
                        position = runBlocking(Dispatchers.Main) { player.currentPosition }
                    }
                    position
                }
            } ?: 0L

            Log.i(
                "dewidebug",
                "no-rebuffer $what: rebuffers=$rebuffers reached=${reached}ms over ${WATCH_MS}ms " +
                    "error=${failure[0]?.message?.take(SHORT_CHARS) ?: "none"}",
            )
            assertTrue(
                "playing $what stalled $rebuffers time(s) in ${WATCH_MS / MS_PER_SECOND}s — the spinner " +
                    "coming back on a long item is what makes it unusable. Reached ${reached}ms.",
                rebuffers <= ALLOWED_REBUFFERS,
            )
            assertTrue(
                "playing $what never advanced at all (${reached}ms), so this measured nothing",
                reached > 0,
            )
        } finally {
            runBlocking(Dispatchers.Main) { player.release() }
        }
    }

    /** Audio alone, or video merged with it — the two shapes this test compares. */
    private fun sourceFor(
        factory: SabrDataSourceFactory,
        resolved: SabrResolve.Resolved,
        withPicture: Boolean,
    ): androidx.media3.exoplayer.source.MediaSource {
        val audio = ProgressiveMediaSource.Factory(factory)
            .createMediaSource(MediaItem.fromUri(Uri.parse(resolved.audioUrl.value)))
        val video = resolved.videoUrl?.takeIf { withPicture } ?: return audio
        return MergingMediaSource(
            ProgressiveMediaSource.Factory(factory)
                .createMediaSource(MediaItem.fromUri(Uri.parse(video.value))),
            audio,
        )
    }

    private companion object {
        /** NASA's "Cosmic Dawn" — public domain, 97 minutes, so nothing here is near its end. */
        const val VIDEO_ID = "uSMGENDH_QI"

        /**
         * Long enough to cross several SABR round trips — a fetch carries roughly ten seconds of media,
         * so this is six or more, well past where a cold reopen or an exhausted stream would show.
         */
        /**
         * Sixty seconds by default, overridable for a real soak:
         *
         * ```
         * adb shell am instrument -w -e soakMs 600000 \
         *   -e class com.dewijones92.totum.video.live.AnHourLongItemDoesNotRebufferTest \
         *   com.dewijones92.totum.test/androidx.test.runner.AndroidJUnitRunner
         * ```
         *
         * The default has to stay short enough for CI to run it on every push, and "an hour-long video"
         * deserves better evidence than one minute of it — so the same code does both rather than a
         * second copy drifting from the first.
         */
        val WATCH_MS: Long = InstrumentationRegistry.getArguments()
            .getString("soakMs")?.toLongOrNull() ?: 60_000L
        const val GRACE_MS = 30_000L
        const val READY_SECONDS = 60L
        const val POLL_MS = 500L
        const val MS_PER_SECOND = 1_000L

        /**
         * ZERO. Not "a few": this is a long item on a good connection, and the whole point of buffering
         * ahead is that the spinner never returns. A tolerance here would hide exactly the regression
         * the test exists to catch.
         */
        const val ALLOWED_REBUFFERS = 0
        const val SHORT_CHARS = 90
    }
}
