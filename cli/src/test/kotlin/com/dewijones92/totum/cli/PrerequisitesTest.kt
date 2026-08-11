package com.dewijones92.totum.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What `totum doctor` says about a machine.
 *
 * Worth testing exhaustively because it is read by somebody who has just installed the thing and
 * has no other information: a wrong or vague line here is the difference between a working tool and
 * an abandoned one. Every combination is a unit test rather than something only visible on a
 * machine that happens to be broken in that particular way.
 */
class PrerequisitesTest {

    @Test
    fun `a machine with everything is ready`() {
        val found = check(present = setOf("python3", "mpv"), ytDlp = true)

        assertTrue(found.joinToString("\n") { it.line }, Prerequisites.ready(found))
    }

    @Test
    fun `any one thing missing means not ready`() {
        assertFalse(Prerequisites.ready(check(present = setOf("mpv"), ytDlp = false)))
        assertFalse(Prerequisites.ready(check(present = setOf("python3"), ytDlp = true)))
        assertFalse(Prerequisites.ready(check(present = setOf("python3", "mpv"), ytDlp = false)))
    }

    @Test
    fun `a missing python is named with how to install it`() {
        val python = check(present = setOf("mpv"), ytDlp = false).single { it.name == "python3" }

        assertFalse(python.present)
        assertTrue(python.fix, python.fix.contains("apt install python3"))
    }

    @Test
    fun `a missing yt-dlp is a pip command, not a system package`() {
        // The two fail differently and are fixed differently; telling somebody with Python but no
        // yt-dlp to install Python sends them the wrong way entirely.
        val ytDlp = check(present = setOf("python3", "mpv"), ytDlp = false).single { it.name == "yt-dlp" }

        assertTrue(ytDlp.fix, ytDlp.fix.contains("pip install"))
        assertTrue(ytDlp.fix, ytDlp.fix.contains("yt-dlp"))
    }

    @Test
    fun `with no python at all, yt-dlp says to install python first`() {
        // "yt-dlp missing" on a machine with no Python is true and useless.
        val ytDlp = check(present = setOf("mpv"), ytDlp = false).single { it.name == "yt-dlp" }

        assertEquals("install Python 3 first", ytDlp.fix)
    }

    @Test
    fun `yt-dlp is not even asked about when there is no python to ask`() {
        var asked = false
        Prerequisites.check(onPath = { it == "mpv" }, pythonHasYtDlp = {
            asked = true
            true
        })

        assertFalse("running python to ask about yt-dlp when there is no python", asked)
    }

    @Test
    fun `any one of the known players satisfies the player requirement`() {
        PlayerCommand.CANDIDATES.forEach { player ->
            val found = check(present = setOf("python3", player), ytDlp = true)

            assertTrue("$player should count as a player", Prerequisites.ready(found))
        }
    }

    @Test
    fun `a missing player names all of them and the escape hatch`() {
        val player = check(present = setOf("python3"), ytDlp = true).single { it.name == "a media player" }

        assertFalse(player.present)
        PlayerCommand.CANDIDATES.forEach { assertTrue(player.fix, player.fix.contains(it)) }
        assertTrue(player.fix, player.fix.contains("TOTUM_PLAYER"))
    }

    /**
     * ASCII only, deliberately. An em dash renders as `?` on a terminal without a UTF-8 locale —
     * seen while testing this under `env -i`, which is exactly the stripped environment a script or
     * a container runs in.
     */
    @Test
    fun `a line says MISSING loudly and carries the fix`() {
        val line = check(present = emptySet(), ytDlp = false).first().line

        assertTrue(line, line.startsWith("MISSING"))
        assertTrue(line, line.contains(" - "))
    }

    @Test
    fun `a satisfied line does not nag with a fix nobody needs`() {
        val line = check(present = setOf("python3", "mpv"), ytDlp = true).first().line

        assertTrue(line, line.startsWith("ok"))
        assertFalse(line, line.contains(" - "))
    }

    @Test
    fun `every requirement is reported, so nothing is a surprise later`() {
        val names = check(present = emptySet(), ytDlp = false).map { it.name }

        assertEquals(listOf("python3", "yt-dlp", "a media player"), names)
    }

    @Test
    fun `the report ends with a verdict, not just a list`() {
        // A column of "ok" lines with no conclusion leaves the reader to work out whether that was
        // good news, which is the one thing this command exists to answer.
        val good = Prerequisites.report(check(present = setOf("python3", "mpv"), ytDlp = true))
        val bad = Prerequisites.report(check(present = emptySet(), ytDlp = false))

        assertTrue(good.last(), good.last().startsWith("Ready"))
        assertTrue(bad.last(), bad.last().startsWith("Fix the above"))
    }

    private fun check(present: Set<String>, ytDlp: Boolean) =
        Prerequisites.check(onPath = { it in present }, pythonHasYtDlp = { ytDlp })
}
