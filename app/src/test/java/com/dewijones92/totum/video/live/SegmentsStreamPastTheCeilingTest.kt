package com.dewijones92.totum.video.live

import com.dewijones92.totum.common.Breadcrumbs
import com.dewijones92.totum.sabr.SabrClientInfo
import com.dewijones92.totum.sabr.SabrSegments
import com.dewijones92.totum.sabr.SabrTrackKind
import com.dewijones92.totum.sabr.SabrTransport
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Does asking segment by segment, at an honest position, get past the ceiling every other shape hit?
 *
 * The byte-addressed reader always stopped at 979459B on a device and 956KB here, and four
 * interventions could not move it: deriving the claim from the reader, feeding it the real player
 * position, capping ExoPlayer's buffer by duration, capping it by bytes. The reason is in the fetch
 * log — `SabrStream.read` loops inside ONE blocking call, so a single read pulls a megabyte and races
 * the claimed position to fifty-seven seconds in about a second, and ExoPlayer never sees those
 * requests so it cannot say "that is enough".
 *
 * [SabrSegments] makes exactly one request per call, at a position the caller states. This measures
 * whether that is sufficient, because if it is not then the chunk-source migration is not worth
 * starting.
 *
 * Needs a live endpoint, which needs its `n` solved, which needs a JavaScript runtime this JVM has
 * none of — so `tools/potoken/websabr.py` prepares one and passes it in. Skips without it, and
 * therefore never runs in CI.
 */
class SegmentsStreamPastTheCeilingTest {

    private val http = OkHttpClient.Builder().callTimeout(TIMEOUT_S, TimeUnit.SECONDS).build()

    private val transport = SabrTransport { url, body ->
        val request = Request.Builder().url(url).post(body.toRequestBody(PROTOBUF)).build()
        http.newCall(request).execute().use { it.body.bytes() }
    }

    @Test
    fun segmentsAreFetchedOneAtATimeUntilTheyStop() {
        val endpoint = System.getProperty("sabrEndpoint")
        val config = System.getProperty("ustreamerConfig")
        val audioSpec = System.getProperty("sabrAudio")
        assumeTrue(
            "needs -DsabrEndpoint, -DustreamerConfig and -DsabrAudio — see tools/potoken/websabr.py",
            !endpoint.isNullOrBlank() && !config.isNullOrBlank() && !audioSpec.isNullOrBlank(),
        )
        val spec = audioSpec!!.split(",")
        val segments = SabrSegments(
            url = endpoint!!,
            ustreamerConfig = Base64.getDecoder().decode(config),
            format = com.dewijones92.totum.sabr.SabrFormat(
                itag = spec[0].toInt(),
                lastModified = spec[1].toLong(),
                xtags = spec[2].ifBlank { null },
                contentLength = spec[3].toLongOrNull(),
            ),
            kind = SabrTrackKind.AUDIO,
            transport = transport,
            clientInfo = SabrClientInfo.WEB,
            // THE combination that had never been run: the correct segment shape, a WEB endpoint whose
            // `n` is solved so it actually answers, and a proof-of-origin token bound to the very
            // visitorData that player request used. Every earlier token measurement used the
            // byte-addressed reader, and most of them used an endpoint that was quietly returning 403.
            poToken = System.getProperty("poToken")?.takeIf { it.isNotBlank() }
                ?.let { Base64.getUrlDecoder().decode(it) },
        )

        var held = 0L
        var sequence = -1
        var covered = 0L
        // Paced to REAL TIME, which every earlier shape ignored. The run that got furthest (1732KB)
        // was the one that waited, and `maxSinceLastRequest=60000ms` sits suspiciously close to the
        // 60001ms of media where an unpaced run stops: the server appears to refuse a position a
        // player could not yet have reached. A real player satisfies this for free, because playing
        // takes time; only our probes and ExoPlayer's four-minute initial buffer do not.
        val startedAt = System.currentTimeMillis()
        runBlocking {
            repeat(SEGMENTS_TO_PULL) {
                // Stay within the readahead the server itself states.
                val ahead = covered - (System.currentTimeMillis() - startedAt) - READAHEAD_MS
                if (PACED && ahead > 0) kotlinx.coroutines.delay(ahead)
                val next = if (sequence < 0) segments.covering(0) else segments.after(sequence)
                if (next == null) {
                    println("[segments] stopped after ${it + 1} calls, holding ${held / KB}KB")
                    return@runBlocking
                }
                sequence = next.sequenceNumber
                held += next.bytes.size
                covered = next.startMs + next.durationMs
                if (it % REPORT_EVERY == 0) {
                    println("[segments] call ${it + 1}: seq=$sequence covers ${covered}ms, ${held / KB}KB held")
                }
            }
        }
        println("[segments] finished holding ${held / KB}KB covering ${covered}ms — policy ${segments.policy}")
        // Diag writes to the in-memory breadcrumb trail rather than stdout, and the line that says what
        // the REFUSING response contained is the whole point of this run.
        Breadcrumbs.snapshot()
            .filter { it.tag == "sabr" && ("nothing new" in it.message || "format extent" in it.message) }
            .forEach { println("[segments] ${it.message}") }
        assertTrue(
            "the segment source served ${held / KB}KB, which is no better than the byte-addressed " +
                "reader's ceiling. If this cannot pass it, the chunk-source migration will not either " +
                "and the diagnosis is wrong.",
            held > OLD_CEILING_BYTES,
        )
    }

    private companion object {
        /** Enough to pass the ceiling several times over at ~10s a segment. */
        const val SEGMENTS_TO_PULL = 40
        const val REPORT_EVERY = 5

        /** The readahead the server states, which is what a paced caller respects. */
        /**
         * How far ahead of real time we allow ourselves. Overridable, because the server states
         * 15000ms and pacing to exactly that still stopped at 60001ms -- sitting ON the boundary is
         * not the same as staying inside it.
         */
        val READAHEAD_MS: Long = System.getProperty("readaheadMs")?.toLongOrNull() ?: 15_000L

        /** `-Dpaced=false` to measure the unpaced ceiling again. */
        val PACED: Boolean = System.getProperty("paced")?.toBooleanStrictOrNull() ?: true
        const val OLD_CEILING_BYTES = 1_200L * 1024
        const val KB = 1024
        const val TIMEOUT_S = 60L
        val PROTOBUF = "application/x-protobuf".toMediaType()
    }
}
