package com.dewijones92.totum.ytdlp.bridge

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * yt-dlp's notes are logged where the contract is PARSED, so both front ends get them.
 *
 * They were logged in `ChaquopyYtDlpEngine` instead -- one of the two engines. The desktop engine
 * (`:lib:ytdlp-process`) runs the same `totum_ytdlp.py` and parses it with the same
 * [parseExtraction], so the notes were present in its JSON, parsed into the result, and then
 * dropped on the floor: `totum` could not tell you the extraction came back degraded even with
 * `TOTUM_VERBOSE` set. That is the DRY law's "count the other places" exactly -- one rule, two
 * callers, implemented in one of them.
 *
 * Logged here rather than in each engine because this is the only place the JSON becomes types,
 * and it is common to every caller by construction.
 */
class EveryFrontEndHearsTheNotesTest {

    private val url = HttpUrl.of("https://www.youtube.com/watch?v=jNQXAC9IVRw")
    private val logged = mutableListOf<String>()
    private var previous: Diag.Sink = Diag.sink

    @Before
    fun captureDiag() {
        previous = Diag.sink
        Diag.sink = Diag.Sink { _, tag, message, _ -> if (tag == "engine") logged += message }
    }

    @After
    fun restoreDiag() {
        Diag.sink = previous
    }

    @Test
    fun `a degraded success says so wherever it was parsed`() {
        parseExtraction(
            url,
            """{"ok":true,"info":{"id":"jNQXAC9IVRw","title":"Me at the zoo"},
               "notes":["warning: formats have been skipped ... SABR-only streaming experiment"]}""",
        )

        assertTrue(
            "the parse seam has to report the notes, or a front end that does not log them itself " +
                "silently loses them: $logged",
            logged.any { "SABR-only" in it },
        )
    }

    @Test
    fun `a failure reports what yt-dlp noticed before it gave up`() {
        parseExtraction(
            url,
            """{"ok":false,"kind":"extractor","detail":"nothing playable",
               "notes":["warning: formats have been skipped ... SABR-only streaming experiment"]}""",
        )

        assertTrue("a failure's notes are the most valuable ones of all: $logged", logged.any { "SABR-only" in it })
    }

    @Test
    fun `a healthy extraction stays quiet`() {
        parseExtraction(url, """{"ok":true,"info":{"id":"jNQXAC9IVRw","title":"Me at the zoo"},"notes":[]}""")

        assertTrue("nothing to report must mean nothing logged, or the line means nothing: $logged", logged.isEmpty())
    }
}
