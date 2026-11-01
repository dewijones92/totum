package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.data.ask.AskKind
import com.dewijones92.totum.data.ask.AskRequest
import com.dewijones92.totum.data.ask.AskTurn
import com.dewijones92.totum.data.ask.CopyToVet
import com.dewijones92.totum.data.ask.Suggestion
import com.dewijones92.totum.data.ask.Taste
import com.dewijones92.totum.data.ask.VetItem
import com.dewijones92.totum.data.ask.VetRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeCodeBrainTest {

    private val gruffalo = AskRequest(listOf(AskTurn("a Gruffalo film for my toddler")))

    private fun envelope(structured: String?, isError: Boolean = false) = ProcessResult(
        0,
        """{"type":"result","subtype":"success","is_error":$isError,"num_turns":3,"total_cost_usd":0.01,""" +
            """"structured_output":${structured ?: "null"},"result":"x"}""",
        "",
        timedOut = false,
    )

    private val answer =
        """{"suggestions":[{"kind":"film","title":"The Gruffalo","year":2009,"why":"the original",""" +
            """"searchWords":"The Gruffalo 2009","alreadyHave":false}]}"""

    @Test
    fun `the structured answer becomes suggestions`() = runTest {
        val brain = claudeCodeBrain(run = { _, _ -> envelope(answer) })
        val got = brain.suggest(gruffalo).suggestions.single()
        assertEquals(AskKind.FILM, got.kind)
        assertEquals("The Gruffalo (2009)", got.label)
    }

    @Test
    fun `suggesting may search the web and vetting may use no tool at all`() = runTest {
        val calls = mutableListOf<List<String>>()
        val brain = claudeCodeBrain(model = "haiku", run = { args, _ ->
            calls += args
            if (args.any { "verdicts" in it }) envelope("""{"verdicts":[]}""") else envelope(answer)
        })
        brain.suggest(gruffalo)
        brain.vet(VetRequest(listOf(VetItem(Suggestion(AskKind.FILM, "Zog", 2018, "w", "Zog 2018"), emptyList()))))
        val tools = calls.map { it[it.indexOf("--tools") + 1] }
        assertEquals(listOf("WebSearch,WebFetch", ""), tools)
        assertTrue(calls.all { it[it.indexOf("--model") + 1] == "haiku" && "--strict-mcp-config" in it })
    }

    @Test
    fun `a malformed answer is asked again once`() = runTest {
        var attempts = 0
        val brain = claudeCodeBrain(run = { _, _ ->
            attempts++
            if (attempts == 1) envelope("""{"suggestions":[{"kind":"opera"}]}""") else envelope(answer)
        })
        assertEquals("The Gruffalo", brain.suggest(gruffalo).suggestions.single().title)
        assertEquals(2, attempts)
    }

    @Test
    fun `two failures in a row are reported, not retried for ever`() = runTest {
        var attempts = 0
        val brain = claudeCodeBrain(run = { _, _ ->
            attempts++
            ProcessResult(-1, "", "", timedOut = true)
        })
        val failure = runCatching { brain.suggest(gruffalo) }.exceptionOrNull()
        assertTrue(failure is BrainFailure && "timed out" in failure.message.orEmpty())
        assertEquals(2, attempts)
    }

    @Test
    fun `an error envelope says why`() = runTest {
        val brain = claudeCodeBrain(run = { _, _ -> envelope(null, isError = true) })
        val failure = runCatching { brain.suggest(gruffalo) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().startsWith("no structured answer"))
    }

    @Test
    fun `the message carries the conversation and what is already there`() {
        val text = Prompts.suggestMessage(
            AskRequest(
                listOf(AskTurn("a Gruffalo film", listOf("Zog (2018)")), AskTurn("something newer")),
                have = listOf("The Gruffalo (2009)"),
            ),
        )
        assertTrue("Newest message: something newer" in text)
        assertTrue("they said \"a Gruffalo film\"; suggested: Zog (2018)" in text)
        assertTrue("Already have: The Gruffalo (2009)" in text)
    }

    @Test
    fun `the message carries earlier replies and the family's taste`() {
        val text = Prompts.suggestMessage(
            AskRequest(
                listOf(
                    AskTurn("happy funk", listOf("Radio · Happy funk"), reply = "Here is a funk radio"),
                    AskTurn("more 70s")
                ),
                taste = Taste(
                    channels = listOf("Bald and Bankrupt"),
                    shows = listOf("The Rest Is Politics"),
                    artists = listOf("Jackson 5"),
                    albums = listOf("ABC"),
                    songs = listOf("September"),
                    played = listOf("Peep Show"),
                ),
            ),
        )
        assertTrue("you answered \"Here is a funk radio\"" in text)
        assertTrue("YouTube channels they subscribe to: Bald and Bankrupt" in text)
        assertTrue("Podcasts they follow: The Rest Is Politics" in text)
        assertTrue("Artists they follow: Jackson 5" in text)
        assertTrue("Albums they saved: ABC" in text)
        assertTrue("Songs they like: September" in text)
        assertTrue("Most played lately: Peep Show" in text)
    }

    @Test
    fun `the answer may be music, a radio or the latest from a channel, with a reply`() = runTest {
        val music = """{"reply":"Funk it is","suggestions":[""" + listOf(
            pick("radio", "Happy funk", "September Earth Wind & Fire; Dean Town Vulfpeck"),
            pick("latest", "Bald and Bankrupt", "Bald and Bankrupt"),
            pick("song", "September", "September Earth Wind & Fire"),
        ).joinToString(",") + "]}"
        val brain = claudeCodeBrain(run = { _, _ -> envelope(music) })
        val got = brain.suggest(gruffalo)
        assertEquals("Funk it is", got.reply)
        assertEquals(listOf(AskKind.RADIO, AskKind.LATEST, AskKind.SONG), got.suggestions.map { it.kind })
        listOf("song", "album", "artist", "radio", "latest", "\"reply\"").forEach {
            assertTrue("schema admits $it", it in Prompts.SUGGEST_SCHEMA)
        }
    }

    @Test
    fun `vetting numbers every copy`() {
        val text = Prompts.vetMessage(
            VetRequest(
                listOf(
                    VetItem(
                        Suggestion(AskKind.FILM, "Shrek", 2001, "fav", "Shrek 2001"),
                        listOf(CopyToVet("Shrek.2001.1080p", 2_000_000_000), CopyToVet("Shrek 2 2004")),
                    ),
                ),
            ),
        )
        assertTrue("Item 0: film \"Shrek (2001)\"" in text)
        assertTrue("  copy 0: Shrek.2001.1080p · 2.00 GB" in text)
        assertTrue("  copy 1: Shrek 2 2004" in text)
    }

    @Test
    fun `a home server not signed in to Claude says so once, without retrying`() = runTest {
        var attempts = 0
        val brain = claudeCodeBrain(run = { _, _ ->
            attempts++
            ProcessResult(
                1,
                """{"type":"result","subtype":"success","is_error":true,""" +
                    """"result":"Not logged in · Please run /login"}""",
                "",
                timedOut = false,
            )
        })
        val failure = runCatching { brain.suggest(gruffalo) }.exceptionOrNull()
        assertEquals("The home server isn't signed in to Claude yet", failure?.message)
        assertEquals(1, attempts)
    }

    private fun pick(kind: String, title: String, words: String) =
        """{"kind":"$kind","title":"$title","year":null,"why":"fits","searchWords":"$words","alreadyHave":false}"""
}
