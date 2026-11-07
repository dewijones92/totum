package com.dewijones92.totum.cli.serve.ai

import com.dewijones92.totum.cli.serve.AnswerDidNotFit
import com.dewijones92.totum.cli.serve.BrainFailure
import com.dewijones92.totum.cli.serve.ProcessResult
import com.dewijones92.totum.cli.serve.PromptBrain
import com.dewijones92.totum.cli.serve.StoredState
import com.dewijones92.totum.data.ask.AskSource
import com.dewijones92.totum.data.ask.AskTurn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class StateCallTest {

    private val structured = """{"reply":"You have 3.","cards":[{"title":"T","note":"n","itemIds":["v1"]}]}"""

    private fun toolUse(name: String, command: String? = null): String {
        val input = command?.let { """"command":"$it"""" }.orEmpty()
        return """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"$name","input":{$input}}]}}"""
    }

    private fun stream(answer: String = structured) = listOf(
        """{"type":"system","subtype":"init"}""",
        toolUse("Bash", "jq .snapshot.counts state.json"),
        """{"type":"system","subtype":"permission_denied","message":"nope"}""",
        toolUse("Bash", "jq .queue.items state.json"),
        toolUse("StructuredOutput"),
        """{"type":"result","subtype":"success","is_error":false,"num_turns":4,"total_cost_usd":0.003,""" +
            """"result":"x","structured_output":$answer}""",
    ).joinToString("\n")

    private val state = StoredState(
        "snapshot-0001",
        """{"type":"object"}""",
        """{"queue":{"items":[]}}""",
        setOf("v1"),
        0,
        "{}"
    )

    private class Capture {
        var args: List<String> = emptyList()
        var dir: File? = null
        var stateSeen: String? = null
    }

    private fun completer(capture: Capture, output: String, env: Map<String, String> = emptyMap()) =
        ClaudeCodeCompleter(
            run = { _, _, _ -> error("a call with files must run in its own directory") },
            runIn = { args, _, dir, _ ->
                capture.args = args
                capture.dir = dir
                capture.stateSeen = File(dir, "state.json").takeIf { it.isFile }?.readText()
                ProcessResult(0, output, "", false)
            },
            env = { env },
        )

    @Test
    fun `a state call runs in a private directory with the file, bash named and only jq allowed`() = runBlocking {
        val capture = Capture()
        val brain = PromptBrain(completer(capture, stream()))
        val reply = brain.answerFromApp("how many?", emptyList(), state)
        assertEquals("You have 3.", reply.reply)
        val args = capture.args
        assertEquals("Bash", args[args.indexOf("--tools") + 1])
        assertEquals("Bash(jq:*)", args[args.indexOf("--allowedTools") + 1])
        assertEquals("stream-json", args[args.indexOf("--output-format") + 1])
        assertTrue("--verbose" in args)
        assertEquals("""{"queue":{"items":[]}}""", capture.stateSeen)
        assertFalse("the scratch directory is gone afterwards", capture.dir!!.exists())
        assertFalse(args.any { it.contains("WebSearch") || it.contains("WebFetch") })
    }

    @Test
    fun `tool calls are counted from the stream, refusals apart, and the answer itself is not one`() = runBlocking {
        val capture = Capture()
        val reply = PromptBrain(completer(capture, stream())).answerFromApp("q", emptyList(), state)
        assertEquals(2, reply.toolCalls)
        assertEquals(1, reply.denied)
    }

    @Test
    fun `a call without files is the old one-line json call with no directory`() = runBlocking {
        var args: List<String> = emptyList()
        val completer = ClaudeCodeCompleter(
            run = { a, _, _ ->
                args = a
                ProcessResult(0, """{"is_error":false,"structured_output":{"verdicts":[]}}""", "", false)
            },
            runIn = { _, _, _, _ -> error("no directory without files") },
        )
        completer.complete(Completion("vet", "vet", "s", "m", "{}", tools = ""))
        assertEquals("json", args[args.indexOf("--output-format") + 1])
        assertFalse("--verbose" in args)
        assertEquals("", args[args.indexOf("--tools") + 1])
    }

    @Test
    fun `an answer holding a secret from the environment is withheld`() = runBlocking {
        val leaky = stream("""{"reply":"the token is SUPERSECRETVALUE-12345","cards":[]}""")
        val capture = Capture()
        val brain = PromptBrain(completer(capture, leaky, mapOf("CLAUDE_CODE_OAUTH_TOKEN" to "SUPERSECRETVALUE-12345")))
        try {
            brain.answerFromApp("q", emptyList(), state)
            fail("the answer should have been withheld")
        } catch (e: BrainFailure) {
            assertTrue(e.message!!.contains("withheld"))
        }
        assertFalse(capture.dir!!.exists())
    }

    @Test
    fun `an answer shaped like a secret is withheld even when the environment does not hold it`() = runBlocking {
        val leaky = stream("""{"reply":"here: ya29.a0AfH6SMBxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx","cards":[]}""")
        try {
            PromptBrain(completer(Capture(), leaky)).answerFromApp("q", emptyList(), state)
            fail("the answer should have been withheld")
        } catch (e: BrainFailure) {
            assertTrue(e.message!!.contains("withheld"))
        }
    }

    @Test
    fun `file names that could leave the directory are refused before anything runs`() {
        assertNotNull(Scratch.problem(mapOf("../x" to "1")))
        assertNotNull(Scratch.problem(mapOf("a/b" to "1")))
        assertNotNull(Scratch.problem(mapOf(".hidden" to "1")))
        assertNull(Scratch.problem(mapOf("state.json" to "{}")))
    }

    @Test
    fun `a stream with no result line is an unreadable answer, not a silent success`() {
        assertNull(ClaudeStream.parse("""{"type":"system"}${'\n'}{"type":"assistant","message":{"content":[]}}"""))
        val run = ClaudeStream.parse(stream())
        assertEquals(2, run!!.toolCalls)
        assertEquals(listOf("jq .snapshot.counts state.json", "jq .queue.items state.json"), run.commands)
    }

    @Test
    fun `the allow-list names bash for the tool and keeps the pattern for the permission`() {
        assertEquals("Bash", CallerTools.namesOf("Bash(jq:*)"))
        assertEquals("WebSearch,WebFetch", CallerTools.namesOf("WebSearch,WebFetch"))
        assertEquals("", CallerTools.namesOf(""))
        assertEquals("Bash,WebSearch", CallerTools.namesOf("Bash(jq:*),WebSearch,Bash(jq:*)"))
        assertNull(CallerTools.STANDARD.refusal(CallerTools.ASK_STATE, "Bash(jq:*)"))
        assertNotNull(CallerTools.STANDARD.refusal(CallerTools.ASK_STATE, "Bash(jq:*),WebFetch"))
        assertNotNull(CallerTools.STANDARD.refusal(CallerTools.ASK_STATE, "Bash"))
        assertNotNull(CallerTools.STANDARD.refusal(CallerTools.ASK_ROUTE, "WebSearch"))
        assertNull(CallerTools.STANDARD.refusal(CallerTools.ASK_ROUTE, ""))
    }

    @Test
    fun `the router answer is read, and a malformed one is asked again once`() = runBlocking {
        var calls = 0
        val completer = ClaudeCodeCompleter(
            run = { _, _, _ ->
                calls++
                val body = if (calls == 1) {
                    """{"route":"sideways"}"""
                } else {
                    """{"route":"both","appQuestion":"a","webQuestion":"b","webNeedsAppData":true,"why":"w"}"""
                }
                ProcessResult(0, """{"is_error":false,"structured_output":$body}""", "", false)
            },
        )
        val decision = PromptBrain(completer).route(listOf(AskTurn("both?", source = AskSource.WEB)))
        assertEquals(AskSource.BOTH, decision.route)
        assertTrue(decision.webNeedsAppData)
        assertEquals(2, calls)
        assertTrue(AnswerDidNotFit("x", RuntimeException()) is BrainFailure)
    }
}
