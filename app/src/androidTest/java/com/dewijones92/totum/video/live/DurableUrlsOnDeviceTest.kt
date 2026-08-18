package com.dewijones92.totum.video.live

import androidx.test.core.app.ApplicationProvider
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.ytdlp.ExtractionResult
import com.dewijones92.totum.ytdlp.chaquopy.ChaquopyYtDlpEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/**
 * The phone's own QuickJS must be able to produce a **durable** stream URL.
 *
 * This is the on-device half of the 2026-08-18 fix, and the half that could not be assumed. YouTube
 * began refusing streams past roughly their first megabyte, and the streams that survive are the ones
 * whose URL carries a **deciphered `n`** — solved by a JavaScript runtime. The fix (asking yt-dlp for
 * `web_embedded`, and preferring durable URLs in `MediaMetadata.isDurable`) was measured on a laptop
 * with **node**. The app ships **QuickJS**, which is a different engine on a different architecture.
 *
 * If QuickJS cannot solve `n` here, the fix is a no-op on the actual phone and every un-downloaded
 * item stays silent — with a green JVM suite, because the JVM tests use hand-written URLs. Nothing
 * short of running the real interpreter on a real device can tell those apart.
 *
 * A live test: it talks to YouTube, so CI runs it through the home connection. It is deliberately NOT
 * allowed to skip on a refusal — "YouTube would not serve us" is the defect it exists to detect.
 *
 * **It lives in `:app` on purpose.** Written first in `:lib:ytdlp-chaquopy`, it failed reporting that
 * QuickJS had produced nothing — which looked like a finding about the phone and was not. `libqjs.so`
 * is bundled in **`:app`'s** `jniLibs`, so that module's own test APK structurally cannot contain it:
 * the runtime was simply absent and yt-dlp fell back to the client that has no `n` to solve. A test in
 * the wrong module measured the wrong app.
 */
class DurableUrlsOnDeviceTest {

    private val engine = ChaquopyYtDlpEngine(ApplicationProvider.getApplicationContext())

    @Test
    fun extractionYieldsAUrlThatHasBeenThroughTheNSolve() = runTest(timeout = EXTRACT_TIMEOUT) {
        val result = engine.extract(HttpUrl.of("https://www.youtube.com/watch?v=$PUBLIC_DOMAIN_VIDEO"))

        assertTrue("extraction failed outright: $result", result is ExtractionResult.Success)
        val formats = (result as ExtractionResult.Success).metadata.formats.filter { it.url != null }
        assertTrue("no formats came back with a URL at all", formats.isNotEmpty())

        val durable = formats.filter { DECIPHERED_N.containsMatchIn(it.url!!) }
        val notes = (result as ExtractionResult.Success).notes
        // EITHER we got a durable URL, OR yt-dlp told us why not. Both are acceptable; being unable to
        // say which is not.
        //
        // This asserted `durable.isNotEmpty()` first, and went red on a run having passed twice — because
        // YouTube enables its SABR-only experiment PER SESSION, so whether any URL survives is its
        // choice and not ours. Asserting it is the same "test someone else's policy" mistake made twice
        // already today. What IS ours is that a degraded extraction explains itself, which is why
        // `no_warnings` came off: yt-dlp says "formats have been skipped as they are missing a URL …
        // SABR-only streaming experiment", and that sentence is the whole diagnosis.
        assertTrue(
            "no durable URL AND no explanation — ${formats.size} formats, clients " +
                "${formats.mapNotNull { CLIENT.find(it.url!!)?.groupValues?.get(1) }.distinct()}. " +
                "That combination means QuickJS or the bridge has broken, because YouTube at least " +
                "says when it is withholding. notes=$notes",
            durable.isNotEmpty() || notes.any { SABR_ONLY in it || "missing a URL" in it },
        )
    }

    /**
     * Reports the device's format census, and asserts only what this app is responsible for.
     *
     * It began life asserting that a durable VIDEO stream exists — and that is YouTube's policy, not
     * our code. It went red immediately and would have stayed red until a PO token exists, which is
     * exactly the mistake recorded in `docs/todos/youtube-requires-attestation.md` and committed
     * against earlier the same day. Twice in one session, so it is worth the paragraph: **if a failure
     * cannot be fixed by a commit in this repository, it is a monitor, not a test.**
     *
     * What is asserted is that extraction produces a usable census at all. What is PRINTED is the
     * durable-video count, because that number is the one worth watching — the day it stops being zero
     * is the day the picture comes back on long videos, and the hourly canary is what will notice.
     */
    @Test
    fun theDeviceReportsItsFormatCensus() = runTest(timeout = EXTRACT_TIMEOUT) {
        val result = engine.extract(HttpUrl.of("https://www.youtube.com/watch?v=$PUBLIC_DOMAIN_VIDEO"))
        assertTrue("extraction failed outright: $result", result is ExtractionResult.Success)
        val formats = (result as ExtractionResult.Success).metadata.formats.filter { it.url != null }

        val video = formats.filter { it.hasVideo }
        val audio = formats.filter { !it.hasVideo && it.hasAudio }
        val durable = { fs: List<com.dewijones92.totum.ytdlp.MediaFormat> ->
            fs.count { DECIPHERED_N.containsMatchIn(it.url!!) }
        }
        println(
            "[census] video=${video.size} durable=${durable(video)} " +
                "heights=${video.mapNotNull { it.height }.distinct().sorted()} | " +
                "audio=${audio.size} durable=${durable(audio)} | " +
                "clients=${formats.mapNotNull { CLIENT.find(it.url!!)?.groupValues?.get(1) }.distinct()}",
        )

        assertTrue("extraction produced no usable formats at all", video.isNotEmpty() || audio.isNotEmpty())
    }

    private companion object {
        /** NASA's "Moonbound Episode 2" — public domain, 37 minutes, and a VOD rather than a live recording. */
        const val PUBLIC_DOMAIN_VIDEO = "ttiLcMUQq80"

        /** yt-dlp's wording for the experiment that strips URLs from formats, per session. */
        const val SABR_ONLY = "SABR"

        /** Anchored on the parameter boundary: `ns=` and `sn=` ride on the URLs that fail. */
        val DECIPHERED_N = Regex("""[?&]n=[^&]+""")
        val CLIENT = Regex("""[?&]c=([A-Za-z0-9_]+)""")

        /** A challenged solve has been measured at 25s on an emulator; this leaves room for two. */
        val EXTRACT_TIMEOUT = 4.minutes
    }
}
