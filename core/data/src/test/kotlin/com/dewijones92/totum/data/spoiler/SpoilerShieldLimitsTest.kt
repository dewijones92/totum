package com.dewijones92.totum.data.spoiler

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Duration.Companion.minutes

class SpoilerShieldLimitsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val channel = ShieldedChannel("UCaaaaaaaaaaaaaaaaaaaaaa", "Tennis TV")
    private val channelUrl = HttpUrl.parse("https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa")!!
    private val asked = mutableListOf<List<SpoilerQuery>>()
    private val watched = mutableSetOf<String>()
    private var now = 1_000L

    private fun item(id: String, title: String = "Alcaraz beats Sinner $id") = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId("feed"),
        title = title,
        publishedAt = null,
        duration = 90.minutes,
        author = "Tennis TV",
        sourceUrl = channelUrl,
    )

    private fun TestScope.shield(
        store: ShieldStore = InMemoryShieldStore(),
        answerEach: Boolean = true,
    ) = SpoilerShield(
        store = store,
        check = { batch ->
            asked += batch
            now += 1_000
            if (answerEach) {
                AskOutcome.Answered(
                    SpoilerAnswer(batch.map { SpoilerVerdict(it.id, true, "Match ${it.id}") }),
                    1,
                )
            } else {
                AskOutcome.Failed("down")
            }
        },
        watched = { it.value in watched },
        scope = this,
        clock = { now },
        debounceMs = 10,
    ).also { it.setChannel(channel, on = true) }

    @Test
    fun `sixty new titles are asked about 25 at a time, none twice, none dropped`() = runTest {
        val shield = shield()

        (1..60).forEach { shield.face(item("v$it")) }
        advanceUntilIdle()

        assertEquals(listOf(25, 25, 10), asked.map { it.size })
        assertEquals((1..60).map { "v$it" }, asked.flatten().map { it.id })
        assertEquals(60, shield.state.value.safe.size)
        assertEquals("Match v42", shield.face(item("v42")).title)
    }

    @Test
    fun `the store keeps the 3000 newest titles and forgets the oldest`() = runTest {
        val shield = shield()

        (1..3_100).forEach { shield.face(item("v$it")) }
        advanceUntilIdle()

        val safe = shield.state.value.safe
        assertEquals(3_000, safe.size)
        assertFalse("v1" in safe)
        assertFalse("v100" in safe)
        assertTrue("v101" in safe)
        assertTrue("v3100" in safe)
    }

    @Test
    fun `an item turns plain the moment it is watched and hides again if it is not`() = runTest {
        val shield = shield(answerEach = false)
        val video = item("a")

        assertTrue(shield.face(video).shielded)
        watched += "a"
        assertFalse(shield.face(video).shielded)
        assertEquals(video.title, shield.face(video).title)
        watched -= "a"
        assertTrue(shield.face(video).shielded)
    }

    @Test
    fun `a title revealed by hand stays revealed after a restart, and hiding it again does too`() = runTest {
        val store = InMemoryShieldStore()
        val first = shield(store, answerEach = false)
        first.reveal(MediaItemId("a"))

        val second = shield(store, answerEach = false)
        assertTrue(second.revealedByHand(MediaItemId("a")))
        assertFalse(second.face(item("a")).shielded)
        second.hideAgain(MediaItemId("a"))

        val third = shield(store, answerEach = false)
        assertFalse(third.revealedByHand(MediaItemId("a")))
        assertTrue(third.face(item("a")).shielded)
    }

    @Test
    fun `the safe title of an item nobody has answered for is the neutral one`() = runTest {
        val shield = shield(answerEach = false)

        assertEquals(SpoilerRules.NEUTRAL, shield.safeTitleOf("unknown"))
    }

    @Test
    fun `the file store writes what it was given and reads it back`() {
        val store = FileShieldStore(tmp.newFile("shield.json"))
        val state = ShieldState(
            channels = listOf(channel),
            safe = mapOf("a" to StoredTitle("Alcaraz v Sinner", spoils = true, at = 5)),
            revealed = setOf("b"),
        )

        store.save(state)

        assertEquals(state, FileShieldStore(java.io.File(tmp.root, "shield.json")).load())
    }

    @Test
    fun `a missing or corrupt file starts with nothing shielded rather than failing`() {
        assertEquals(ShieldState(), FileShieldStore(java.io.File(tmp.root, "none.json")).load())
        val corrupt = tmp.newFile("corrupt.json").apply { writeText("{not json") }
        assertEquals(ShieldState(), FileShieldStore(corrupt).load())
        assertNull(ShieldState().channels.firstOrNull())
    }

    @Test
    fun `a save that cannot be written does not throw`() {
        FileShieldStore(java.io.File(tmp.root, "no-such-folder/deeper/shield.json")).save(ShieldState())
    }

    @Test
    fun `a channel covers an item by id, or by name ignoring case and spaces`() {
        val byId = ShieldedChannel("UCx", "Name")
        assertTrue(byId.covers("UCx", null))
        assertFalse(byId.covers("UCy", null))
        assertTrue(ShieldedChannel(null, "Tennis TV").covers(null, "  tennis tv "))
        assertFalse(ShieldedChannel(null, "Tennis TV").covers(null, null))
        assertFalse(ShieldedChannel(null, "Tennis TV").covers("UCx", "WTA"))
    }
}
