package com.dewijones92.totum.cli

import com.dewijones92.totum.common.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words handed to the player.
 *
 * Worth testing precisely because a wrong flag here fails as "it just opens a window" or "no
 * sound" — symptoms that look like our bug and are actually one character of somebody else's
 * command line.
 */
class PlayerCommandTest {

    @Test
    fun `mpv is told not to show a picture when you asked for audio`() {
        val command = forStream("mpv", audioOnly = true)

        assertEquals("mpv", command.first())
        assertTrue("--no-video" in command)
        assertEquals("the url goes last, so nothing can be read as its argument", URL, command.last())
    }

    @Test
    fun `and is not told that when you asked to watch`() {
        assertTrue("--no-video" !in forStream("mpv", audioOnly = false))
    }

    @Test
    fun `the title reaches the player, so the terminal can name what is playing`() {
        assertTrue(forStream("mpv", audioOnly = true).any { it.contains(TITLE) })
        assertTrue(forStream("vlc", audioOnly = true).any { it.contains(TITLE) })
    }

    @Test
    fun `vlc is told to exit at the end rather than sit on a black window`() {
        assertTrue("--play-and-exit" in forStream("vlc", audioOnly = true))
    }

    @Test
    fun `ffplay is given no display and told to exit`() {
        val command = forStream("ffplay", audioOnly = true)

        assertTrue("-nodisp" in command)
        assertTrue("-autoexit" in command)
    }

    @Test
    fun `a player given by absolute path is still recognised`() {
        // TOTUM_PLAYER=/usr/bin/mpv must behave like TOTUM_PLAYER=mpv.
        assertTrue("--no-video" in forStream("/usr/bin/mpv", audioOnly = true))
    }

    @Test
    fun `an unknown player is given the url and nothing else to trip over`() {
        val command = forStream("someplayer", audioOnly = true)

        assertEquals(listOf("someplayer", URL), command)
    }

    @Test
    fun `the override is split into words`() {
        assertEquals(listOf("mpv", "--no-config"), PlayerCommand.override("mpv --no-config"))
        assertEquals(listOf("mpv"), PlayerCommand.override("  mpv  "))
    }

    @Test
    fun `no override means no override`() {
        assertNull(PlayerCommand.override(null))
        assertNull("an empty variable is not a player", PlayerCommand.override("   "))
    }

    private fun forStream(player: String, audioOnly: Boolean) =
        PlayerCommand.forStream(player, HttpUrl.of(URL), TITLE, audioOnly)

    private companion object {
        const val URL = "https://cdn.test/stream.m4a"
        const val TITLE = "Relaxing Jazz Piano Radio"
    }
}
