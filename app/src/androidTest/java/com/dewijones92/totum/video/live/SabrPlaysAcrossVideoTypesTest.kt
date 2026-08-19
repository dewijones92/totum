package com.dewijones92.totum.video.live

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.core.app.ApplicationProvider
import com.dewijones92.totum.common.Breadcrumbs
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
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Does SABR actually PLAY, across the range of things this app has to play?
 *
 * `SabrPlaybackTest` proves the protocol works, but only for AUDIO and only on one 19-second video. The
 * question that matters to a person using the app is different: does the SABR path carry a normal clip,
 * a feature-length VOD, a made-for-kids video and a live stream — the four shapes
 * [PlaysAcrossContentTypesTest] already identifies as the ones that break — and does it carry PICTURE,
 * not just sound?
 *
 * Capped at 1080p, which is both what Dewi asked for and what `SabrResolve` already enforces: SABR
 * refuses 60fps outright, so a 4K60 upload has to come back on a 30fps rung or not at all.
 *
 * Drives the real seam — `SabrResolve.prepare` and the app's own `SabrDataSourceFactory` — rather than
 * hand-rolling a picker. A test with its own selection rules would pass while the app kept choosing a
 * format YouTube refuses, which is exactly the shape of bug this repo keeps finding.
 *
 * REPORTS what YouTube served and ASSERTS only our half. A live stream may legitimately offer nothing
 * SABR can use; failing for that would make this a monitor of YouTube's policy rather than a test, which
 * this repository has already been burnt by six times.
 */
class SabrPlaysAcrossVideoTypesTest {

    private val http = OkHttpClient()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private data class Outcome(
        val label: String,
        val resolved: Boolean,
        val detail: String,
        val playedMs: Long,
        val hadPicture: Boolean,
    )

    @Test
    fun sabrCarriesEveryContentType() {
        // Reported AS IT GOES, not collected and printed at the end. The first version logged only after
        // all five fixtures had finished, so a seventeen-minute run showed nothing at all until it was
        // over and a hang was indistinguishable from slow progress -- the exact silence this repo's
        // logging rule exists to stop.
        val outcomes = FIXTURES.map { (id, label) ->
            attempt(id, label).also { report(it) }
        }

        val playable = outcomes.filter { it.resolved }
        assertTrue(
            "SABR resolved nothing at all for any content type — that is ours, not YouTube's: $outcomes",
            playable.isNotEmpty(),
        )
        // PICTURE, not position, is the per-fixture bar. A video track only appears once SABR has served
        // enough for the extractor to parse the container, so it is real evidence of the protocol working
        // — while POSITION at 1080p measures this emulator's SOFTWARE decoder, which cannot hold real
        // time and made Ms Rachel report 10042ms on one run and 0ms on the next with identical itags.
        // A test that swings on the renderer is measuring the machine, not the app.
        val noPicture = playable.filter { !it.hadPicture }
        assertTrue(
            "SABR resolved these and the player never got a video track, so nothing was decodable: " +
                noPicture.joinToString { "${it.label} (${it.detail})" },
            noPicture.isEmpty(),
        )
        // And at least one has to genuinely PLAY, or the above could pass on a stack that parses and
        // never renders a frame.
        assertTrue(
            "not one content type actually advanced past ${MIN_POSITION_MS}ms, so nothing really played: " +
                outcomes.joinToString { "${it.label}=${it.playedMs}ms" },
            outcomes.any { it.playedMs >= MIN_POSITION_MS },
        )
    }

    /**
     * Logged rather than printed: an instrumented test's stdout is not reliably surfaced by
     * `am instrument`, and a result nobody can read is not a result.
     */
    private fun report(outcome: Outcome) = Log.i(
        "dewidebug",
        "sabr-types ${outcome.label.padEnd(26)} resolved=${outcome.resolved} played=${outcome.playedMs}ms " +
            "picture=${outcome.hadPicture} — ${outcome.detail}",
    )

    private fun attempt(videoId: String, label: String): Outcome {
        Breadcrumbs.clear()
        val player = runBlocking {
            val response = InnerTubeClient(http).player(videoId)
            (response as? InnerTubeResponse.Success)?.body?.let(PlayerResponseParser::parse)
        }
        val success = player as? PlayerResult.Success
            ?: return Outcome(label, resolved = false, detail = "YouTube served no player response", 0, false)

        val resolved = SabrResolve.prepare(videoId, success.streaming, success.details)
            ?: return Outcome(label, resolved = false, detail = "SabrResolve refused: ${lastRefusal()}", 0, false)

        val quality = Breadcrumbs.snapshot().lastOrNull { "SABR" in it.message || "sabr" == it.tag }
            ?.message?.take(TRAIL_CHARS)
        return play(resolved.videoUrl?.value, resolved.audioUrl.value, label, quality)
    }

    /** The reason SabrResolve gave, so a refusal is readable instead of just absent. */
    private fun lastRefusal(): String =
        Breadcrumbs.snapshot().lastOrNull { it.tag == "sabr" }?.message?.take(TRAIL_CHARS) ?: "no reason logged"

    private fun play(videoUri: String?, audioUri: String, label: String, quality: String?): Outcome {
        val factory = SabrDataSourceFactory(DefaultHttpDataSource.Factory())
        val player = ExoPlayer.Builder(context).build()
        val ready = CountDownLatch(1)
        val failure = arrayOfNulls<Throwable>(1)
        try {
            runBlocking(Dispatchers.Main) {
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) ready.countDown()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        failure[0] = error
                        ready.countDown()
                    }
                })
                val audio = ProgressiveMediaSource.Factory(factory)
                    .createMediaSource(MediaItem.fromUri(Uri.parse(audioUri)))
                // Video and audio are SEPARATE SABR tracks, so they are merged exactly as the app's own
                // playback path merges them. Audio alone would pass while the picture stayed broken.
                val source = videoUri?.let {
                    MergingMediaSource(
                        ProgressiveMediaSource.Factory(factory)
                            .createMediaSource(MediaItem.fromUri(Uri.parse(it))),
                        audio,
                    )
                } ?: audio
                player.setMediaSource(source)
                player.prepare()
                player.play()
            }
            if (!ready.await(READY_SECONDS, TimeUnit.SECONDS)) {
                return Outcome(label, resolved = true, detail = "never became ready", 0, false)
            }
            failure[0]?.let {
                return Outcome(
                    label,
                    resolved = true,
                    detail = "player error: ${it.message?.take(TRAIL_CHARS)}",
                    0,
                    false
                )
            }
            val advanced = runBlocking {
                withTimeoutOrNull(ADVANCE_TIMEOUT_MS) {
                    var position = 0L
                    while (position <= MIN_POSITION_MS) {
                        delay(POLL_MS)
                        position = runBlocking(Dispatchers.Main) { player.currentPosition }
                    }
                    position
                }
            } ?: 0L
            val picture = runBlocking(Dispatchers.Main) {
                player.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }
            }
            return Outcome(label, resolved = true, detail = quality ?: "played", advanced, picture)
        } finally {
            runBlocking(Dispatchers.Main) { player.release() }
        }
    }

    private companion object {
        /**
         * The same four shapes [PlaysAcrossContentTypesTest] guards, because they are the ones that have
         * broken: a clip small enough to dodge every cap, a VOD long enough not to, made-for-kids (which
         * served no playable stream to any default client at all), and a live stream, which has no
         * audio-only format and nothing durable.
         */
        val FIXTURES = listOf(
            "jNQXAC9IVRw" to "19-second clip",
            "uSMGENDH_QI" to "97-minute VOD",
            "gngPQ771Ahk" to "Ms Rachel (kids)",
            "aqz-KE-bpKQ" to "Big Buck Bunny 4K60",
            "YDvsBbKfLPA" to "live stream",
        )

        const val READY_SECONDS = 90L
        const val ADVANCE_TIMEOUT_MS = 120_000L

        /**
         * Ten seconds, not one. The failure this whole path exists to survive is YouTube refusing a
         * stream past roughly its first MEGABYTE, and 1080p crosses a megabyte in about a second — so a
         * one-second bar is passed by exactly the streams that are about to die. Ten seconds of moving
         * position is several megabytes and several SABR round trips.
         */
        const val MIN_POSITION_MS = 10_000L
        const val POLL_MS = 250L
        const val TRAIL_CHARS = 160
    }
}
