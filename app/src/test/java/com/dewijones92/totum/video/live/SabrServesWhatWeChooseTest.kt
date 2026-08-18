package com.dewijones92.totum.video.live

import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.browse.InnerTubeResponse
import com.dewijones92.totum.innertube.player.PlayableFormat
import com.dewijones92.totum.innertube.player.PlayerResponseParser
import com.dewijones92.totum.innertube.player.PlayerResult
import com.dewijones92.totum.sabr.SabrFormat
import com.dewijones92.totum.sabr.SabrSession
import com.dewijones92.totum.sabr.SabrSessions
import com.dewijones92.totum.sabr.SabrStream
import com.dewijones92.totum.sabr.SabrTrackKind
import com.dewijones92.totum.sabr.SabrTransport
import com.dewijones92.totum.video.SabrResolve
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Does SABR still serve the formats `SabrResolve` is willing to choose?
 *
 * `SabrResolve` carries three caps — mp4 only, 30fps only, 1080p or below — and each one is a
 * measurement of YouTube's behaviour taken on **2026-07-31**, written into a `const`. A measurement
 * in a constant is a fact with no expiry date on it, and the two ways that goes wrong are opposites:
 *
 * * **It gets stricter.** A format we still pick stops being served, and playback breaks for a
 *   reason no unit test can see, because our picker is behaving exactly as designed.
 * * **It gets looser.** 60fps or 2160p start serving, and we carry on refusing them forever —
 *   Dewi watches a 4K60 upload at 1080p30 with nothing anywhere saying why. That is the shape of
 *   the "works great in smarttube" gap.
 *
 * So this test **asserts only the first**, which is ours: every format our own picker chooses must
 * actually deliver bytes. What YouTube permits beyond that is *reported*, never asserted — a test
 * that failed when YouTube RELAXED a restriction would be red for good news, which is the same
 * mistake as asserting someone else's policy and one this repo has already made three times.
 *
 * The printed line is the deliverable as much as the green tick: it is the only place that says
 * what quality YouTube would serve today versus what we ask for.
 */
class SabrServesWhatWeChooseTest {

    private val http = OkHttpClient.Builder()
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val transport = SabrTransport { url, body ->
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(PROTOBUF))
            .build()
        http.newCall(request).execute().use { it.body.bytes() }
    }

    @Test
    fun everyFormatWeWouldChooseActuallyServes() = runBlocking {
        val parsed = playerResponse(FOUR_K_SIXTY)
        val prepared = SabrResolve.prepare(FOUR_K_SIXTY, parsed.streaming, parsed.details)
        assertTrue(
            "SabrResolve refused to build a session for a public-domain 4K60 video. The reasons are " +
                "logged by SabrResolve.refuse; if YouTube has stopped offering a SABR endpoint at all " +
                "then this is the news, not a skip.",
            prepared != null,
        )
        val session = SabrSessions.of(FOUR_K_SIXTY)!!

        val audio = served(session, session.audio!!, SabrTrackKind.AUDIO)
        val video = session.video?.let { served(session, it, SabrTrackKind.VIDEO) } ?: 0L

        println("[sabr] our own picks: audio ${audio / KB}KB, video ${video / KB}KB")
        println("[sabr] ${wouldYouTubeAllowMore(parsed.streaming.formats, session)}")

        assertTrue("SABR served no audio for a format our own picker chose", audio > ENOUGH_BYTES)
        assertTrue(
            "SABR served no video for a format our own picker chose. Our caps let it through and " +
                "YouTube then refused it, so the caps in SabrResolve no longer match reality.",
            session.video == null || video > ENOUGH_BYTES,
        )
    }

    /**
     * What YouTube would serve beyond our caps — printed, never asserted.
     *
     * Probes one excluded format of each kind rather than all of them: the point is to notice a
     * relaxation, and one 60fps format serving is enough to say "go and look". Probing the whole
     * ladder would cost a minute of live requests to tell us the same thing.
     */
    private suspend fun wouldYouTubeAllowMore(formats: List<PlayableFormat>, session: SabrSession): String {
        val excluded = formats.filter { it.lastModified != null && it.height != null }
            .filter { (it.fps ?: 0) > CAPPED_FPS || (it.height ?: 0) > CAPPED_HEIGHT }
            .filter { it.mimeType?.contains("mp4") == true && it.mimeType?.contains("mp4a") != true }
        val probe = excluded.maxByOrNull { it.height ?: 0 } ?: return "YouTube offered nothing above our caps"
        val format = SabrFormat(probe.itag, probe.lastModified!!, probe.xtags, probe.contentLength)
        val got = served(session, format, SabrTrackKind.VIDEO)
        val label = "itag ${probe.itag} ${probe.height}p${probe.fps ?: ""}"
        return if (got > ENOUGH_BYTES) {
            "$RELAXED $label served ${got / KB}KB — SabrResolve still refuses it, so we are " +
                "throwing quality away. Re-measure the caps (they date from 2026-07-31)."
        } else {
            "confirmed still refused: $label served ${got / KB}KB. Our caps remain correct."
        }
    }

    private suspend fun served(session: SabrSession, format: SabrFormat, kind: SabrTrackKind): Long {
        val stream = SabrStream(
            url = session.streamingUrl,
            ustreamerConfig = session.ustreamerConfig,
            format = format,
            kind = kind,
            transport = transport,
            totalBytes = format.contentLength,
            durationMs = session.durationMs,
        )
        // Reads from the START, and asks for more than the first response can hold: an empty first
        // response is normal (the server sends config before media), so a one-shot probe would report
        // every format as refused.
        return runCatching { stream.read(0).size.toLong() }.getOrDefault(0L)
    }

    private fun playerResponse(videoId: String): PlayerResult.Success {
        val response = runBlocking { InnerTubeClient(http).player(videoId) }
        val parsed = (response as? InnerTubeResponse.Success)?.body?.let(PlayerResponseParser::parse)
        assertTrue(
            "YouTube served no playable player response for $videoId — got $parsed. That is the " +
                "failure the app experiences as \"nothing plays\".",
            parsed is PlayerResult.Success,
        )
        return parsed as PlayerResult.Success
    }

    private companion object {
        /**
         * Blender's "Big Buck Bunny" — Creative Commons, 4K at 60fps, and permanently up. It has to
         * be 4K60 or the interesting formats are not in the response to probe.
         */
        const val FOUR_K_SIXTY = "aqz-KE-bpKQ"

        /** Mirrors `SabrResolve`'s own caps. Duplicated deliberately: this test must fail if they drift. */
        const val CAPPED_FPS = 30
        const val CAPPED_HEIGHT = 1080

        const val RELAXED = "SABR HAS RELAXED:"
        const val ENOUGH_BYTES = 10L * 1024
        const val KB = 1024
        const val CALL_TIMEOUT_SECONDS = 60L
        val PROTOBUF = "application/x-protobuf".toMediaType()
    }
}
