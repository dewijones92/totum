package com.dewijones92.totum.data.morning

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.ask.AskKind
import com.dewijones92.totum.data.ask.Suggestion
import com.dewijones92.totum.data.ask.Taste
import com.dewijones92.totum.data.playlist.fake.InMemoryLocalPlaylistStore
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.PlaylistId
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MorningRulesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun pick(kind: AskKind, title: String, why: String = "because") =
        Suggestion(kind, title, year = 2020, why = why, searchWords = title, alreadyHave = true)

    private val request = ForYouRequest(
        taste = Taste(channels = listOf("Kurzgesagt"), shows = listOf("In Our Time")),
        have = listOf("Owned Video"),
        earlier = listOf("Picked Yesterday"),
    )

    private fun tidy(vararg picks: Suggestion) = MorningRules.tidyForYou(request, ForYouAnswer(picks.toList())).picks

    @Test
    fun `a channel or show already followed is never recommended, by name or by containing it`() {
        val kept = tidy(
            pick(AskKind.YOUTUBE, "Kurzgesagt"),
            pick(AskKind.YOUTUBE, "KURZGESAGT - In a Nutshell"),
            pick(AskKind.PODCAST, "in our time"),
            pick(AskKind.YOUTUBE, "Veritasium"),
        )

        assertEquals(listOf("Veritasium"), kept.map { it.title })
    }

    @Test
    fun `something owned, picked lately or named twice is dropped`() {
        val kept = tidy(
            pick(AskKind.YOUTUBE, "Owned Video"),
            pick(AskKind.YOUTUBE, "Picked Yesterday"),
            pick(AskKind.YOUTUBE, "Fresh One"),
            pick(AskKind.YOUTUBE, "fresh   one"),
        )

        assertEquals(listOf("Fresh One"), kept.map { it.title })
    }

    @Test
    fun `only videos and podcasts are wanted, and a pick with no title is dropped`() {
        val kept = tidy(
            pick(AskKind.SONG, "A Song"),
            pick(AskKind.FILM, "A Film"),
            pick(AskKind.YOUTUBE, "   "),
            pick(AskKind.PODCAST, "A Show"),
        )

        assertEquals(listOf("A Show"), kept.map { it.title })
    }

    @Test
    fun `at most six videos and four shows are kept, videos first, and extras are cleaned`() {
        val picks = (1..9).map { pick(AskKind.YOUTUBE, "Video $it", why = "**great** [link](https://x.example)") } +
            (1..6).map { pick(AskKind.PODCAST, "Show $it") }

        val kept = tidy(*picks.toTypedArray())

        assertEquals(10, kept.size)
        assertEquals(6, kept.count { it.kind == AskKind.YOUTUBE })
        assertEquals(4, kept.count { it.kind == AskKind.PODCAST })
        assertEquals("Video 1", kept.first().title)
        assertEquals("great link", kept.first().why)
        assertEquals(null, kept.first().year)
        assertFalse(kept.first().alreadyHave)
    }

    @Test
    fun `the digest keeps two whole lines that name something it was given`() {
        val digest = MorningDigestRequest(
            listOf(GroupHeadlines("Tennis", listOf("Sinner serves")), GroupHeadlines("Travel", listOf("Lisbon walk"))),
        )

        val tidy = MorningRules.tidyDigest(
            digest,
            MorningDigestAnswer(
                listOf(
                    "**Tennis** has Sinner serves.",
                    "Totally invented news about nothing",
                    "Travel: a Lisbon walk.",
                    "Tennis again with Sinner.",
                ),
            ),
        )

        assertEquals(listOf("Tennis has Sinner serves.", "Travel: a Lisbon walk."), tidy.lines)
    }

    @Test
    fun `without the model the busiest two groups are told plainly, with how many more`() {
        val digest = MorningDigestRequest(
            listOf(
                GroupHeadlines("Quiet", listOf("One video")),
                GroupHeadlines("Empty", emptyList()),
                GroupHeadlines("Busy", listOf("First", "Second", "Third")),
                GroupHeadlines("Medium", listOf("Alpha", "Beta")),
            ),
        )

        val lines = MorningRules.fallbackDigest(digest).lines

        assertEquals(listOf("🆕 Busy: First and 2 more", "🆕 Medium: Alpha and 1 more"), lines)
        assertEquals(
            listOf("🆕 Quiet: One video"),
            MorningRules.fallbackDigest(
                MorningDigestRequest(listOf(GroupHeadlines("Quiet", listOf("One video"))))
            ).lines,
        )
    }

    @Test
    fun `an inbox entry told is gone and the clock moves, and picks are remembered for two weeks`() {
        var now = 100L * DAY
        val store = MorningStore(InMemoryMorningFile(), clock = { now })
        val a = InboxEntry("G", "a", "A", false, now)
        val b = InboxEntry("G", "b", "B", false, now)
        store.addToInbox(a)
        store.addToInbox(b)

        store.consumed(listOf(a), at = now)

        assertEquals(listOf("b"), store.state.value.inbox.map { it.itemId })
        assertEquals(now, store.state.value.lastDigestAt)
        store.picked(listOf("Pick One", "Pick Two"), now)
        assertEquals(setOf("Pick One", "Pick Two"), store.pickedLately(now + 13 * DAY).toSet())
        assertTrue(store.pickedLately(now + 15 * DAY).isEmpty())
    }

    @Test
    fun `picks older than a month are forgotten when new ones are noted`() {
        var now = 100L * DAY
        val store = MorningStore(InMemoryMorningFile(), clock = { now })
        store.picked(listOf("Old"), now)

        store.picked(listOf("New"), now + 31 * DAY)

        assertEquals(setOf("New"), store.state.value.picked.keys)
    }

    @Test
    fun `with the digest on a new video goes into the inbox and no notification is wanted`() {
        var asked = 0
        val store = MorningStore(InMemoryMorningFile(), clock = { 777L })
        store.setDigest(true)

        val held = store.holdForDigest("Tennis", "v1", "Day 2", shielded = { asked++ >= 0 })

        assertTrue(held)
        assertEquals(1, asked)
        val entry = store.state.value.inbox.single()
        assertEquals(InboxEntry("Tennis", "v1", "Day 2", shielded = true, at = 777L), entry)
    }

    @Test
    fun `with the digest off the video is left to notify and the shield is not asked`() {
        val store = MorningStore(InMemoryMorningFile(), clock = { 777L })

        val held = store.holdForDigest("Tennis", "v1", "Day 2", shielded = { error("must not be asked") })

        assertFalse(held)
        assertTrue(store.state.value.inbox.isEmpty())
    }

    @Test
    fun `the switches and the inbox survive a restart through the file`() {
        val file = File(tmp.root, "morning.json")
        val store = MorningStore(FileMorningFile(file), clock = { 10L })
        store.setDigest(true)
        store.setForYou(true)
        store.addToInbox(InboxEntry("G", "a", "A", true, 5))

        val again = MorningStore(FileMorningFile(file), clock = { 10L })

        assertTrue(again.digestOn)
        assertTrue(again.state.value.forYou)
        assertEquals(listOf("a"), again.state.value.inbox.map { it.itemId })
        assertTrue(again.state.value.inbox.single().shielded)
    }

    @Test
    fun `a corrupt morning file starts with both features off`() {
        val file = tmp.newFile("morning.json").apply { writeText("[]") }

        assertEquals(MorningState(), FileMorningFile(file).load())
        assertEquals(MorningState(), FileMorningFile(File(tmp.root, "none.json")).load())
        FileMorningFile(File(tmp.root, "x/y/z.json")).save(MorningState())
    }

    @Test
    fun `replacing a playlist with the same items changes nothing and with fewer drops the rest`() = runTest {
        val store = InMemoryLocalPlaylistStore()
        val id = PlaylistId("for-you")
        fun playable(name: String) = PlayableItem(
            MediaItem(MediaItemId(name), SourceId("for-you"), name, null, duration = null),
            PlayHandle.Video(HttpUrl.parse("https://www.youtube.com/watch?v=$name")!!),
        )

        store.replaceContents(id, "🤖 For you", listOf(playable("a"), playable("b")))
        store.replaceContents(id, "🤖 For you", listOf(playable("a"), playable("b")))
        assertEquals(listOf("a", "b"), store.observeItems(id).first().map { it.item.id.value }.sorted())

        store.replaceContents(id, "🤖 For you", listOf(playable("b")))
        assertEquals(listOf("b"), store.observeItems(id).first().map { it.item.id.value })
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
