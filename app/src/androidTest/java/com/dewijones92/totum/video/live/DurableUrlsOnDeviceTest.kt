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
        assertTrue(
            "QuickJS produced no URL with a deciphered `n` — ${formats.size} formats, clients " +
                "${formats.mapNotNull { CLIENT.find(it.url!!)?.groupValues?.get(1) }.distinct()}. " +
                "Without one, every stream stops about a megabyte in.",
            durable.isNotEmpty(),
        )
    }

    private companion object {
        /** NASA's "Moonbound Episode 2" — public domain, 37 minutes, and a VOD rather than a live recording. */
        const val PUBLIC_DOMAIN_VIDEO = "ttiLcMUQq80"

        /** Anchored on the parameter boundary: `ns=` and `sn=` ride on the URLs that fail. */
        val DECIPHERED_N = Regex("""[?&]n=[^&]+""")
        val CLIENT = Regex("""[?&]c=([A-Za-z0-9_]+)""")

        /** A challenged solve has been measured at 25s on an emulator; this leaves room for two. */
        val EXTRACT_TIMEOUT = 4.minutes
    }
}
