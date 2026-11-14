package com.dewijones92.totum.data.spoiler

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.ask.AskOutcome
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class SpoilerShieldLoggingTest {

    private val channelUrl = HttpUrl.parse("https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa")!!
    private var reply: AskOutcome<SpoilerAnswer> = AskOutcome.Failed("not set")

    private fun TestScope.shield() = SpoilerShield(
        store = InMemoryShieldStore(),
        check = { reply },
        watched = { false },
        scope = this,
        clock = { 1_000L },
        debounceMs = 10,
    )

    private fun item(id: String, title: String, author: String = "Some Channel") = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId("feed"),
        title = title,
        publishedAt = null,
        duration = null,
        author = author,
        sourceUrl = channelUrl,
    )

    private inline fun capturingSpoilerLines(body: (List<String>) -> Unit) {
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val previous = Diag.sink
        Diag.sink = Diag.Sink { _, tag, message, _ -> if (tag == "spoiler") lines += message }
        try {
            body(lines)
        } finally {
            Diag.sink = previous
        }
    }

    @Test
    fun `five hundred rows shown at start-up are one summary line, with a few examples`() = runTest {
        capturingSpoilerLines { lines ->
            val shield = shield()
            repeat(500) { shield.face(item("item-$it", "Title $it")) }
            advanceUntilIdle()

            assertEquals(lines.toString(), 1, lines.size)
            val summary = lines.single()
            assertTrue(summary, summary.startsWith("faces:"))
            assertTrue(summary, "500" in summary)
            assertTrue(summary, "its channel is not shielded" in summary)
            assertTrue(summary, "item-0" in summary)
            assertFalse(summary, "item-499" in summary)
        }
    }

    @Test
    fun `an item seen again is not counted again`() = runTest {
        capturingSpoilerLines { lines ->
            val shield = shield()
            repeat(5) { shield.face(item("same", "Title")) }
            advanceUntilIdle()

            assertTrue(lines.toString(), "1 new item" in lines.single())
        }
    }

    @Test
    fun `the rule check overriding the model is its own line, an ordinary verdict is only counted`() = runTest {
        capturingSpoilerLines { lines ->
            val shield = shield()
            shield.setChannel(ShieldedChannel("UCaaaaaaaaaaaaaaaaaaaaaa", "Some Channel"), on = true)
            reply = AskOutcome.Answered(
                SpoilerAnswer(
                    listOf(
                        SpoilerVerdict("over", true, "Alcaraz wins v Sinner"),
                        SpoilerVerdict("fine", false),
                        SpoilerVerdict("redone", true, "Zverev v Musetti"),
                    ),
                ),
                1,
            )
            shield.face(item("over", "Alcaraz beats Sinner"))
            shield.face(item("fine", "Zverev v Musetti highlights"))
            shield.face(item("redone", "Zverev beats Musetti"))
            advanceUntilIdle()

            assertEquals(lines.toString(), 1, lines.count { it.startsWith("item over:") })
            assertTrue(lines.toString(), lines.none { it.startsWith("item fine:") || it.startsWith("item redone:") })
            val verdicts = lines.single { it.startsWith("verdicts:") }
            assertTrue(verdicts, "kept 1" in verdicts)
            assertTrue(verdicts, "rewritten 1" in verdicts)
            assertTrue(verdicts, "replaced by the neutral title 1" in verdicts)
        }
    }
}
