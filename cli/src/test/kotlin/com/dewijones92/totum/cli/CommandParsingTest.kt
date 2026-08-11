package com.dewijones92.totum.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the words you type mean.
 *
 * The whole surface, stated exhaustively, because it is small enough to be — and because argument
 * parsing is the one part of a CLI a user meets before anything else works.
 */
class CommandParsingTest {

    @Test
    fun `a bare url plays it`() {
        val command = parse(listOf(URL)) as Command.Play

        assertEquals(URL, (command.target as Target.Url).url.value)
        assertFalse("audio unless asked otherwise", command.watch)
    }

    @Test
    fun `bare words are a search to play`() {
        val command = parse(listOf("jazz", "live", "stream")) as Command.Play

        assertEquals("jazz live stream", (command.target as Target.Query).text)
    }

    @Test
    fun `play takes either`() {
        assertTrue((parse(listOf("play", URL)) as Command.Play).target is Target.Url)
        assertTrue((parse(listOf("play", "some", "words")) as Command.Play).target is Target.Query)
    }

    @Test
    fun `watch keeps the picture`() {
        assertTrue((parse(listOf("play", URL, "--watch")) as Command.Play).watch)
        assertTrue((parse(listOf(URL, "--watch")) as Command.Play).watch)
    }

    @Test
    fun `a flag never becomes part of the search phrase`() {
        // Otherwise "--watch" would be searched for on YouTube, which is a baffling way to fail.
        val command = parse(listOf("jazz", "--watch")) as Command.Play

        assertEquals("jazz", (command.target as Target.Query).text)
    }

    @Test
    fun `resolve prints instead of playing, in json when asked`() {
        assertFalse((parse(listOf("resolve", URL)) as Command.Resolve).json)
        assertTrue((parse(listOf("resolve", URL, "--json")) as Command.Resolve).json)
    }

    @Test
    fun `search takes a limit, within reason`() {
        assertEquals(10, (parse(listOf("search", "jazz")) as Command.Search).limit)
        assertEquals(3, (parse(listOf("search", "jazz", "--limit=3")) as Command.Search).limit)
        assertEquals("a nonsense limit falls back rather than failing", 10, limitOf("--limit=banana"))
        assertEquals("zero would find nothing", 1, limitOf("--limit=0"))
        assertEquals("and nobody wants ten thousand rows", 50, limitOf("--limit=10000"))
    }

    @Test
    fun `help and version are reachable however you ask`() {
        assertTrue(parse(listOf("help")) is Command.Help)
        assertTrue(parse(listOf("--help")) is Command.Help)
        assertTrue(parse(listOf("-h")) is Command.Help)
        assertTrue(parse(listOf("version")) is Command.Version)
        assertTrue(parse(listOf("--version")) is Command.Version)
        assertTrue(parse(listOf("-V")) is Command.Version)
    }

    @Test
    fun `doctor is reachable`() {
        assertTrue(parse(listOf("doctor")) is Command.Doctor)
    }

    @Test
    fun `help beats everything else, so it always works`() {
        assertTrue(parse(listOf("play", URL, "--help")) is Command.Help)
    }

    @Test
    fun `nothing at all is help, not an error`() {
        val command = parse(emptyList()) as Command.Help

        assertEquals("running it bare should teach, not scold", null, command.reason)
    }

    @Test
    fun `a command with nothing to act on says which`() {
        assertTrue((parse(listOf("play")) as Command.Help).reason!!.contains("play"))
        assertTrue((parse(listOf("resolve")) as Command.Help).reason!!.contains("resolve"))
        assertTrue((parse(listOf("search")) as Command.Help).reason!!.contains("search"))
    }

    @Test
    fun `a url with spaces around it is still a url`() {
        assertTrue((parse(listOf(" $URL ")) as Command.Play).target is Target.Url)
    }

    @Test
    fun `several words that include a url are a search`() {
        // "play this $URL for me" is words, not a link — one argument is what makes it a link.
        val command = parse(listOf("watch", URL, "later")) as Command.Play

        assertTrue(command.target is Target.Query)
    }

    private fun limitOf(flag: String) = (parse(listOf("search", "jazz", flag)) as Command.Search).limit

    private companion object {
        const val URL = "https://www.youtube.com/watch?v=jNQXAC9IVRw"
    }
}
