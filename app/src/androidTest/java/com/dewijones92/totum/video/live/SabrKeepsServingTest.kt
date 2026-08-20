package com.dewijones92.totum.video.live

import android.util.Log
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.browse.InnerTubeResponse
import com.dewijones92.totum.innertube.player.PlayableFormat
import com.dewijones92.totum.innertube.player.PlayerResponseParser
import com.dewijones92.totum.innertube.player.PlayerResult
import com.dewijones92.totum.innertube.player.StreamingData
import com.dewijones92.totum.sabr.SabrFormat
import com.dewijones92.totum.sabr.SabrStream
import com.dewijones92.totum.sabr.SabrTrackKind
import com.dewijones92.totum.sabr.SabrTransport
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Does SABR keep serving, well past the point where an unattested stream is refused?
 *
 * Measures BYTES, not playback position, and that choice is the point. Position depends on the decoder,
 * and this emulator runs software rendering (`-gpu swiftshader_indirect`) that cannot hold real time at
 * 1080p — a position-based test would report the emulator's decode speed and call it a SABR verdict.
 * Reading the track directly isolates the protocol.
 *
 * The bar is several megabytes because the failure this guards is YouTube cutting an unattested stream off
 * at roughly its FIRST megabyte (`docs/todos/youtube-requires-attestation.md`). Ten seconds of 1080p is
 * past that; ten megabytes is far past it, and past any single segment, so it also exercises the repeated
 * round trips where a cold-reopen bug would show.
 *
 * Reports rather than asserts YouTube's ceiling: if it stops early, that is recorded with the byte count
 * so a reader can tell "refused at 1MB" from "the video simply ended".
 */
class SabrKeepsServingTest {

    private val http = OkHttpClient()

    private class OkHttpSabrTransport(private val client: OkHttpClient) : SabrTransport {
        override suspend fun post(url: String, body: ByteArray): ByteArray {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", ANDROID_UA)
                .post(body.toRequestBody(PROTOBUF))
                .build()
            return client.newCall(request).execute().use { it.body?.bytes() ?: ByteArray(0) }
        }

        private companion object {
            val PROTOBUF = "application/x-protobuf".toMediaType()
            const val ANDROID_UA = "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip"
        }
    }

    @Test
    fun sabrServesFarPastTheFirstMegabyte() {
        val streaming = streamsFor(VIDEO_ID)
        assertTrue("YouTube served no player response, so nothing was measured", streaming != null)
        val endpoint = streaming!!.serverAbrStreamingUrl?.value
        val config = streaming.ustreamerConfig
        assertTrue(
            "no SABR endpoint or ustreamer config, so SABR cannot be measured at all",
            endpoint != null && config != null
        )

        // The tallest rung SABR will actually serve, by the app's own rules: <=1080p, 30fps, mp4.
        val format = streaming.formats
            .filter { it.mimeType?.startsWith("video/") == true }
            .filter { (it.height ?: 0) <= MAX_HEIGHT && (it.fps ?: 0) <= MAX_FPS }
            .filter { it.mimeType?.contains("mp4") == true }
            .maxByOrNull { it.height ?: 0 }
        assertTrue("no video format within 1080p30 mp4 to measure", format != null)

        val outcome = readSequentially(endpoint!!, config!!, format!!)
        Log.i("dewidebug", "sabr-serving $outcome")
        assertTrue(
            "SABR served only ${outcome.bytes} bytes of itag ${format.itag} — under a megabyte is the " +
                "attestation cut-off, and this is the app's core streaming path: $outcome",
            outcome.bytes >= MIN_BYTES,
        )
    }

    private data class Outcome(
        val itag: Int,
        val height: Int?,
        val bytes: Long,
        val reads: Int,
        val stoppedBecause: String,
    )

    private fun readSequentially(endpoint: String, config: ByteArray, format: PlayableFormat): Outcome {
        val stream = SabrStream(
            url = endpoint,
            ustreamerConfig = config,
            format = SabrFormat(format.itag, format.lastModified ?: 0L, format.xtags, format.contentLength),
            kind = SabrTrackKind.VIDEO,
            totalBytes = format.contentLength,
            transport = OkHttpSabrTransport(http),
        )
        var served = 0L
        var reads = 0
        var stopped = "reached the target"
        runBlocking {
            withTimeoutOrNull(OVERALL_TIMEOUT_MS) {
                while (served < TARGET_BYTES) {
                    val chunk = runCatching { stream.read(served) }.getOrElse {
                        stopped = "threw ${it::class.simpleName}: ${it.message?.take(DETAIL_CHARS)}"
                        return@withTimeoutOrNull
                    }
                    reads++
                    if (chunk.isEmpty()) {
                        // An empty read is the interesting case, and it has three meanings, not two: the
                        // file ended, the server went quiet short of the stated length, or the answers
                        // never carried the byte being read. Naming all three is the whole point of this
                        // test — "end of stream" for a refusal is the report saying nothing happened.
                        stopped = when {
                            stream.lastReadStalled -> "STALLED (answers never reached the reader)"
                            stream.endedPrematurely -> "ENDED PREMATURELY (refused)"
                            else -> "end of stream"
                        }
                        return@withTimeoutOrNull
                    }
                    served += chunk.size
                }
            } ?: run { stopped = "timed out after ${OVERALL_TIMEOUT_MS}ms" }
        }
        return Outcome(
            format.itag,
            format.height,
            served,
            reads,
            "$stopped; ${stream.describeProgress().take(DETAIL_CHARS)}"
        )
    }

    private fun streamsFor(videoId: String): StreamingData? = runBlocking {
        val response = InnerTubeClient(http).player(videoId)
        val parsed = (response as? InnerTubeResponse.Success)?.body?.let(PlayerResponseParser::parse)
        (parsed as? PlayerResult.Success)?.streaming
    }

    private companion object {
        /** NASA's "Cosmic Dawn" — public domain, and long enough that ten megabytes is nowhere near its end. */
        const val VIDEO_ID = "uSMGENDH_QI"

        const val MAX_HEIGHT = 1080
        const val MAX_FPS = 30
        const val TARGET_BYTES = 10L * 1024 * 1024

        /** Ten times the attestation cut-off, so passing cannot be luck. */
        const val MIN_BYTES = 10L * 1024 * 1024 / 2
        const val OVERALL_TIMEOUT_MS = 180_000L
        const val DETAIL_CHARS = 200
    }
}
