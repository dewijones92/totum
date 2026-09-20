package com.dewijones92.totum.innertube.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The caption URL has to ASK for WebVTT, not merely hope the URL already said something to swap.
 *
 * `base.replace("fmt=srv3", "fmt=vtt")` silently does nothing when there is no `fmt=srv3` to
 * replace, and the InnerTube player response's `baseUrl` frequently carries no `fmt` at all. YouTube
 * then serves its default — srv3 XML — while the app has declared the track `text/vtt`, so Media3
 * fails it with `contentIsMalformed=true`.
 *
 * Diagnosed from the instrumentation added for it, CI run 35525069446:
 *
 * ```
 * [subtitles] uSMGENDH_QI: 2 track(s) — en/English declared=text/vtt asks=no fmt | …
 * ```
 *
 * `asks=no fmt` against `declared=text/vtt` is the whole bug. The ordinary yt-dlp route says
 * `declared=application/ttml+xml asks=ttml` and works, which is why only the SABR route breaks.
 */
class AskingForVttTest {

    @Test
    fun `an srv3 url is swapped, as it always was`() {
        assertEquals(
            "https://www.youtube.com/api/timedtext?v=abc&fmt=vtt",
            askingForVtt("https://www.youtube.com/api/timedtext?v=abc&fmt=srv3"),
        )
    }

    /** THE BUG: no `fmt` to replace, so the old code left the URL asking for nothing. */
    @Test
    fun `a url with no fmt at all is given one`() {
        assertEquals(
            "https://www.youtube.com/api/timedtext?v=abc&fmt=vtt",
            askingForVtt("https://www.youtube.com/api/timedtext?v=abc"),
        )
    }

    /**
     * Exactly one `fmt`, and it is ours. The order of query parameters is not preserved and does
     * not matter — the removed one leaves a gap and the replacement goes on the end.
     */
    @Test
    fun `some other fmt is replaced rather than appended twice`() {
        assertEquals(
            "https://www.youtube.com/api/timedtext?v=abc&tlang=de&fmt=vtt",
            askingForVtt("https://www.youtube.com/api/timedtext?v=abc&fmt=json3&tlang=de"),
        )
    }

    @Test
    fun `a url with no query gets one`() {
        assertEquals("https://example.test/caps?fmt=vtt", askingForVtt("https://example.test/caps"))
    }

    /** `fmt` must not be matched inside another parameter's name. */
    @Test
    fun `a parameter merely ending in fmt is left alone`() {
        assertEquals(
            "https://www.youtube.com/api/timedtext?xfmt=1&fmt=vtt",
            askingForVtt("https://www.youtube.com/api/timedtext?xfmt=1"),
        )
    }
}
