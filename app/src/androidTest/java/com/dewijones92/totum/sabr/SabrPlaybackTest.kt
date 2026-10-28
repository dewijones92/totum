package com.dewijones92.totum.sabr

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.browse.InnerTubeResponse
import com.dewijones92.totum.innertube.player.PlayerResponseParser
import com.dewijones92.totum.innertube.player.PlayerResult
import com.dewijones92.totum.innertube.player.StreamingData
import com.dewijones92.totum.playback.SabrDataSource
import com.dewijones92.totum.support.YouTubeAnswers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Plays a real YouTube video through SABR, on the device.
 *
 * This is the test that decides whether any of the protocol work is worth anything: unit tests
 * with a fake transport prove the bytes are framed correctly, and the desktop check proved they
 * decode — but only ExoPlayer on Android can say the app can actually play them.
 *
 * It is deliberately end to end and uses no fakes: a live `/player` call, the real
 * [SabrStream], the real [SabrDataSource], and a real [ExoPlayer] rendering to no surface. The
 * assertion is the only one that matters — the playback position moved.
 */
@RunWith(AndroidJUnit4::class)
class SabrPlaybackTest {

    @get:Rule
    val youTube = YouTubeAnswers()

    private val http = OkHttpClient()

    private class OkHttpSabrTransport(private val client: OkHttpClient) : SabrTransport {
        override suspend fun post(url: String, body: ByteArray): ByteArray {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", ANDROID_UA)
                .post(body.toRequestBody(PROTOBUF))
                .build()
            return client.newCall(request).execute().use { it.body.bytes() }
        }

        private companion object {
            val PROTOBUF = "application/x-protobuf".toMediaType()
            const val ANDROID_UA = "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip"
        }
    }

    /**
     * SKIPPED, not failed, when YouTube will not serve this machine.
     *
     * This talks to the live service, and a GitHub runner is a datacentre IP that gets
     * bot-checked — the first CI run of 2026-08-01 came back `Unplayable`, and the hard cast
     * that was here turned an environment condition into a red build. A test that cannot run
     * where it is running should say so: claiming a defect it has no evidence for is how a
     * suite teaches everyone to ignore it.
     */
    private fun servedStreams(): StreamingData {
        val player = runBlocking {
            val response = InnerTubeClient(http).player(VIDEO_ID)
            (response as? InnerTubeResponse.Success)?.body?.let(PlayerResponseParser::parse)
        }
        assumeTrue(
            "YouTube did not serve this machine a player response ($player) — commonly a " +
                "datacentre IP being bot-checked, which is not a defect in the SABR path",
            player is PlayerResult.Success,
        )
        return (player as PlayerResult.Success).streaming
    }

    @Test
    fun playsRealAudioOverSabr() {
        val streaming = servedStreams()
        val endpoint = streaming.serverAbrStreamingUrl
        val config = streaming.ustreamerConfig
        assertNotNull("no SABR endpoint in the player response", endpoint)
        assertNotNull("no ustreamer config in the player response", config)

        // The best audio track that names itself completely; xtags is what makes it selectable.
        val audio = streaming.formats
            .filter { it.mimeType?.startsWith("audio/") == true && it.lastModified != null }
            .maxByOrNull { it.bitrate ?: 0 }
        assertNotNull("no identifiable audio format", audio)

        val stream = SabrStream(
            url = endpoint!!.value,
            ustreamerConfig = config!!,
            format = SabrFormat(audio!!.itag, audio.lastModified!!, audio.xtags),
            kind = SabrTrackKind.AUDIO,
            transport = OkHttpSabrTransport(http),
        )

        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = DataSource.Factory { SabrDataSource(stream) }
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
                player.setMediaSource(
                    ProgressiveMediaSource.Factory(factory)
                        .createMediaSource(MediaItem.fromUri(Uri.parse("sabr://${audio.itag}"))),
                )
                player.prepare()
                player.play()
            }

            assertTrue("player never became ready", ready.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            failure[0]?.let { throw AssertionError("SABR playback failed", it) }

            val advanced: Long? = runBlocking {
                withTimeoutOrNull(ADVANCE_TIMEOUT_MS) {
                    var position = 0L
                    while (position <= MIN_POSITION_MS) {
                        delay(POLL_MS)
                        position = runBlocking(Dispatchers.Main) { player.currentPosition }
                    }
                    position
                }
            }
            assertNotNull("position never passed ${MIN_POSITION_MS}ms — it did not really play", advanced)
            Diag.log("sabr", "PLAYED ${advanced}ms of itag ${audio.itag} over SABR")
        } finally {
            runBlocking(Dispatchers.Main) { player.release() }
        }
    }

    private companion object {
        /** "Me at the zoo" — short, ancient, and unlikely ever to be taken down. */
        const val VIDEO_ID = "jNQXAC9IVRw"
        const val READY_TIMEOUT_SECONDS = 45L
        const val ADVANCE_TIMEOUT_MS = 30_000L
        const val MIN_POSITION_MS = 1_000L
        const val POLL_MS = 250L
    }
}
