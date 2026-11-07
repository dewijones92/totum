package com.dewijones92.totum.data.morning

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.ai.HttpAiJobs
import com.dewijones92.totum.data.ask.Suggestion
import com.dewijones92.totum.data.ask.Taste
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MorningRunTest {

    private val server = MockWebServer()
    private var now = 1_000_000_000L
    private val file = InMemoryMorningFile()
    private val store = MorningStore(file, clock = { now })
    private val replaced = mutableListOf<List<PlayableItem>>()
    private val searched = mutableListOf<String>()
    private val taste = Taste(channels = listOf("Kurzgesagt"), shows = listOf("In Our Time"))
    private val base get() = server.url("/fetch").toString().trimEnd('/')

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun job(state: String, extra: String = "") =
        MockResponse.Builder().code(200).body("""{"id":"j","state":"$state","elapsedMs":10$extra}""").build()

    private fun playable(id: String, title: String) = PlayableItem(
        MediaItem(MediaItemId(id), SourceId("for-you"), title, null, duration = null),
        PlayHandle.Video(HttpUrl.parse("https://www.youtube.com/watch?v=${id.replace(' ', '-')}")!!),
    )

    private fun run(withPi: Boolean = true, find: (Suggestion) -> PlayableItem? = { playable(it.title, it.title) }) =
        MorningRun(
            jobs = { if (withPi) HttpAiJobs(OkHttpClient(), base, { "t" }, pollMs = 1) else null },
            store = store,
            taste = { taste },
            have = { listOf("Something owned") },
            find = { pick, _ ->
                searched += pick.title
                find(pick)
            },
            replaceForYou = { replaced += it },
            titleOf = { if (it.shielded) "🙈 Title hidden" else it.title },
            clock = { now },
        )

    private fun pick(kind: String, title: String) =
        """{"kind":"$kind","title":"$title","year":null,"why":"w","searchWords":"$title","alreadyHave":false}"""

    private fun entry(group: String, id: String, title: String, shielded: Boolean = false, at: Long = now - 1_000) =
        InboxEntry(group, id, title, shielded, at)

    @Test
    fun `with both switched off nothing is asked and nothing is told`() = runTest {
        val result = run().run()
        assertNull(result.picks)
        assertNull(result.digestLines)
        assertFalse(result.worthTelling)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a digest with nothing new makes no call and still moves the clock on`() = runTest {
        store.setDigest(true)
        val result = run().run()
        assertEquals(emptyList<String>(), result.digestLines)
        assertEquals(0, server.requestCount)
        assertEquals(now, store.state.value.lastDigestAt)
    }

    @Test
    fun `the digest sends titles with shielded ones hidden, uses the model's lines and empties the inbox`() = runTest {
        store.setDigest(true)
        store.addToInbox(entry("Tennis", "a", "Sinner beats Alcaraz", shielded = true))
        store.addToInbox(entry("Tennis", "b", "Fritz v Fils Highlights"))
        store.addToInbox(entry("Science", "c", "How memories are stored"))
        server.enqueue(job("running"))
        val line = "Tennis has two new videos, including Fritz v Fils highlights."
        server.enqueue(job("done", ""","result":{"lines":["$line"]}"""))
        val result = run().run()
        assertEquals(listOf("Tennis has two new videos, including Fritz v Fils highlights."), result.digestLines)
        assertTrue(result.digestFromAi)
        assertEquals(3, result.news)
        val sent = server.takeRequest().body!!.utf8()
        assertTrue(sent, "Sinner beats Alcaraz" !in sent && "Title hidden" in sent)
        assertTrue(store.state.value.inbox.isEmpty())
    }

    @Test
    fun `when the Pi is down the digest still goes out as plain lines`() = runTest {
        store.setDigest(true)
        store.addToInbox(entry("Tennis", "b", "Fritz v Fils Highlights"))
        store.addToInbox(entry("Tennis", "d", "Road to Turin"))
        val result = run(withPi = false).run()
        assertFalse(result.digestFromAi)
        assertEquals(listOf("🆕 Tennis: Fritz v Fils Highlights and 1 more"), result.digestLines)
        assertTrue(store.state.value.inbox.isEmpty())
    }

    @Test
    fun `an entry older than the last digest is not told again`() = runTest {
        store.setDigest(true)
        store.addToInbox(entry("Tennis", "old", "Old news", at = now - 5_000))
        store.consumed(emptyList(), now - 2_000)
        val result = run().run()
        assertEquals(emptyList<String>(), result.digestLines)
    }

    @Test
    fun `for you finds each pick with the app's own search and replaces the playlist`() = runTest {
        store.setForYou(true)
        server.enqueue(job("running"))
        server.enqueue(
            job(
                "done",
                ""","result":{"picks":[${pick("youtube", "Fresh video")},${pick("podcast", "Missing show")},
                ${pick("youtube", "In Our Time")}]}""",
            ),
        )
        val result = run(find = { if (it.title == "Missing show") null else playable("id-${it.title}", it.title) })
            .run()
        assertEquals(1, result.picks)
        assertEquals(listOf("Fresh video", "Missing show"), searched)
        assertEquals(listOf("Fresh video"), replaced.single().map { it.item.title })
        assertEquals(listOf("Fresh video"), store.pickedLately(now))
    }

    @Test
    fun `for you leaves the playlist alone when nothing was found`() = runTest {
        store.setForYou(true)
        server.enqueue(job("done", ""","result":{"picks":[${pick("youtube", "X")}]}"""))
        val result = run(find = { null }).run()
        assertEquals(0, result.picks)
        assertTrue(replaced.isEmpty())
    }

    @Test
    fun `for you says why it failed when the Pi cannot be asked`() = runTest {
        store.setForYou(true)
        val result = run(withPi = false).run()
        assertNull(result.picks)
        assertEquals("❌ No home server", result.picksFailed)
    }

    @Test
    fun `the inbox keeps the newest three hundred and forgets the old`() {
        store.setDigest(true)
        repeat(305) { store.addToInbox(entry("G", "id$it", "t$it")) }
        assertEquals(300, store.state.value.inbox.size)
        now += 4L * 24 * 60 * 60 * 1000
        store.addToInbox(entry("G", "new", "fresh", at = now))
        assertEquals(listOf("new"), store.state.value.inbox.map { it.itemId })
    }
}
