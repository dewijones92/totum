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
 * MEASURES which formats SABR will really serve, per video, rather than trusting the recorded table.
 *
 * `SabrResolve` refuses whole classes of format on the strength of one probe run on 2026-07-31: every
 * `video/webm` (VP9) refused, every 60fps refused. Those rules are load-bearing and expensive — on
 * Big Buck Bunny they take a 4K60 upload down to **480p**, because once 60fps and webm are excluded the
 * best remaining mp4 rung is itag 135. If either rule has since stopped being true, the app is throwing
 * away quality for nothing; if both still hold, 480p is an honest ceiling and worth recording as one.
 *
 * Also answers why a LIVE stream resolves to nothing: `bestAudio` requires `lastModified`, and this
 * prints whether a live format has one.
 *
 * A diagnostic, so it REPORTS. The only assertion is that the probe itself ran — asserting what YouTube
 * serves would make it a monitor of someone else's policy.
 */
class WhatSabrWillServeTest {

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
    fun whichFormatsActuallyServe() {
        var probed = 0
        SUBJECTS.forEach { (id, label) ->
            val streaming = streamsFor(id) ?: run {
                Log.i("dewidebug", "sabr-serve $label: no player response")
                return@forEach
            }
            Log.i(
                "dewidebug",
                "sabr-serve $label endpoint=${streaming.serverAbrStreamingUrl != null} " +
                    "config=${streaming.ustreamerConfig != null} formats=${streaming.formats.size}",
            )
            streaming.formats.filter { it.mimeType?.startsWith("audio/") == true }.forEach { f ->
                Log.i(
                    "dewidebug",
                    "sabr-serve $label AUDIO itag=${f.itag} mime=${f.mimeType?.take(MIME_CHARS)} " +
                        "lastModified=${f.lastModified != null} xtags=${f.xtags != null} bitrate=${f.bitrate}",
                )
            }
            val candidates = streaming.formats
                .filter { it.mimeType?.startsWith("video/") == true && (it.height ?: 0) <= MAX_HEIGHT }
                .sortedByDescending { it.height ?: 0 }
                .take(MAX_PROBES)
            candidates.forEach { format ->
                probed++
                Log.i("dewidebug", "sabr-serve $label VIDEO ${describe(format)} -> ${serveTest(streaming, format)}")
            }
            // A LIVE stream carries no lastModified on any format, and `bestAudio` requires one, so the
            // app refuses live outright. Whether that requirement is YouTube's or just ours is the whole
            // question, and substituting 0 is the cheapest way to ask it.
            streaming.formats
                .filter { it.mimeType?.startsWith("audio/") == true && it.lastModified == null }
                .take(MAX_AUDIO_PROBES)
                .forEach { format ->
                    probed++
                    Log.i(
                        "dewidebug",
                        "sabr-serve $label AUDIO-NO-LASTMOD itag=${format.itag} -> ${serveTest(streaming, format)}",
                    )
                }
        }
        assertTrue("the probe never ran against a single format, so it measured nothing", probed > 0)
    }

    private fun describe(f: PlayableFormat) =
        "itag=${f.itag} ${f.height}p${f.fps ?: "?"} ${f.mimeType?.substringBefore(';')}"

    /** Asks SABR for the first bytes of one format and says what came back. */
    private fun serveTest(streaming: StreamingData, format: PlayableFormat): String = runBlocking {
        val endpoint = streaming.serverAbrStreamingUrl?.value ?: return@runBlocking "no endpoint"
        val config = streaming.ustreamerConfig ?: return@runBlocking "no config"
        val stream = SabrStream(
            url = endpoint,
            ustreamerConfig = config,
            // 0 when YouTube gave none — a live stream never does, and that is exactly the case
            // being measured here rather than assumed.
            format = SabrFormat(format.itag, format.lastModified ?: 0L, format.xtags, format.contentLength),
            kind = if (format.mimeType?.startsWith("audio/") == true) SabrTrackKind.AUDIO else SabrTrackKind.VIDEO,
            transport = OkHttpSabrTransport(http),
        )
        val bytes = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            runCatching { stream.read(0L) }.getOrElse { return@withTimeoutOrNull "threw ${it::class.simpleName}" }
        }
        when {
            bytes == null -> "timed out"
            bytes is String -> bytes
            bytes is ByteArray && bytes.isNotEmpty() -> "SERVED ${bytes.size}B"
            else -> "refused (0 bytes) ${stream.describeProgress().take(DESC_CHARS)}"
        }
    }

    private fun streamsFor(videoId: String): StreamingData? = runBlocking {
        val response = InnerTubeClient(http).player(videoId)
        val parsed = (response as? InnerTubeResponse.Success)?.body?.let(PlayerResponseParser::parse)
        (parsed as? PlayerResult.Success)?.streaming
    }

    private companion object {
        val SUBJECTS = listOf(
            "aqz-KE-bpKQ" to "BigBuckBunny4K60",
            "YDvsBbKfLPA" to "live",
        )

        /** Dewi's cap, and SabrResolve's: nothing above 1080p is worth measuring here. */
        const val MAX_HEIGHT = 1080

        /**
         * Kept cheap on purpose. This is a DIAGNOSTIC — it answers "what will SABR serve" when someone
         * is investigating — and it runs in the live phase on every push, where its cost is paid by
         * everybody. Eight probes at a thirty-second timeout each is four minutes of worst case per
         * subject, and it is a large part of what took CI's emulator job from thirteen minutes to
         * twenty-eight. Four probes at twelve seconds still covers the interesting rungs, because the
         * refusals answer in tens of milliseconds and only a hang reaches the timeout.
         */
        const val MAX_PROBES = 4
        const val MAX_AUDIO_PROBES = 2
        const val PROBE_TIMEOUT_MS = 12_000L
        const val MIME_CHARS = 40
        const val DESC_CHARS = 120
    }
}
