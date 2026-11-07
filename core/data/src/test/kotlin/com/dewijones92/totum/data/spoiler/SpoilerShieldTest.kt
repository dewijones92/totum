package com.dewijones92.totum.data.spoiler

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.ask.AskOutcome
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

class SpoilerShieldTest {

    private val channelUrl = HttpUrl.parse("https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa")!!
    private val asked = mutableListOf<List<SpoilerQuery>>()
    private var reply: AskOutcome<SpoilerAnswer> = AskOutcome.Failed("not set")
    private val watched = mutableSetOf<String>()
    private var now = 1_000L

    private fun TestScope.shield(store: ShieldStore = InMemoryShieldStore()) = SpoilerShield(
        store = store,
        check = { batch ->
            asked += batch
            reply
        },
        watched = { it.value in watched },
        scope = this,
        clock = { now },
        debounceMs = 10,
    )

    private fun item(
        id: String,
        title: String,
        author: String = "Tennis TV",
        sourceUrl: HttpUrl? = channelUrl,
    ) = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId("feed"),
        title = title,
        publishedAt = null,
        duration = 90.minutes,
        author = author,
        sourceUrl = sourceUrl,
        thumbnailUrl = HttpUrl.parse("https://img.example/$id.jpg"),
    )

    private fun answerWith(vararg verdicts: SpoilerVerdict) = AskOutcome.Answered(SpoilerAnswer(verdicts.toList()), 1)

    @Test
    fun `an item from a channel that is not shielded is shown as it is`() = runTest {
        val shield = shield()
        val face = shield.face(item("a", "Alcaraz beats Sinner"))
        assertEquals("Alcaraz beats Sinner", face.title)
        assertFalse(face.shielded)
        assertFalse(face.blurThumbnail)
        assertEquals(90.minutes, face.duration)
        assertEquals(face.thumbnailUrl, face.artworkUrl)
    }

    @Test
    fun `a shielded item is hidden at once, blurred, with no length, and asked about once`() = runTest {
        val shield = shield()
        shield.setChannel(ShieldedChannel("UCaaaaaaaaaaaaaaaaaaaaaa", "Tennis TV"), on = true)
        val first = shield.face(item("a", "Alcaraz beats Sinner"))
        shield.face(item("a", "Alcaraz beats Sinner"))
        assertEquals(SpoilerRules.NEUTRAL, first.title)
        assertTrue(first.shielded)
        assertTrue(first.blurThumbnail)
        assertNull(first.duration)
        assertNull(first.artworkUrl)
        reply = answerWith(SpoilerVerdict("a", true, "Alcaraz v Sinner"))
        advanceUntilIdle()
        assertEquals(listOf(listOf(SpoilerQuery("a", "Alcaraz beats Sinner", "Tennis TV"))), asked)
        assertEquals("Alcaraz v Sinner", shield.face(item("a", "Alcaraz beats Sinner")).title)
    }

    @Test
    fun `a channel is matched by its id or by its name when the item names no channel link`() = runTest {
        val shield = shield()
        shield.setChannel(ShieldedChannel(id = null, name = "tennis tv"), on = true)
        assertTrue(shield.isShielded(item("a", "x", author = "Tennis TV", sourceUrl = null)))
        assertFalse(shield.isShielded(item("b", "x", author = "Someone Else", sourceUrl = null)))
    }

    @Test
    fun `a watched item and a revealed item show their real title`() = runTest {
        val shield = shield()
        shield.setChannel(ShieldedChannel("UCaaaaaaaaaaaaaaaaaaaaaa", "Tennis TV"), on = true)
        watched += "w"
        assertEquals("real", shield.face(item("w", "real")).title)
        shield.reveal(MediaItemId("r"))
        assertEquals("real", shield.face(item("r", "real")).title)
        shield.hideAgain(MediaItemId("r"))
        assertTrue(shield.face(item("r", "real")).shielded)
    }

    @Test
    fun `a failed check leaves the title hidden and waits for the retry time`() = runTest {
        val shield = shield()
        shield.setChannel(ShieldedChannel("UCaaaaaaaaaaaaaaaaaaaaaa", "Tennis TV"), on = true)
        shield.face(item("a", "Alcaraz beats Sinner"))
        advanceUntilIdle()
        assertEquals(1, asked.size)
        assertEquals(SpoilerRules.NEUTRAL, shield.face(item("a", "Alcaraz beats Sinner")).title)
        advanceUntilIdle()
        assertEquals(1, asked.size)
        now += 11 * 60_000
        shield.face(item("a", "Alcaraz beats Sinner"))
        advanceUntilIdle()
        assertEquals(2, asked.size)
    }

    @Test
    fun `a rewrite that names a result is replaced and an item with no verdict stays hidden`() =
        runTest(StandardTestDispatcher()) {
            val shield = shield()
            shield.setChannel(ShieldedChannel("UCaaaaaaaaaaaaaaaaaaaaaa", "Tennis TV"), on = true)
            reply = answerWith(SpoilerVerdict("a", true, "Alcaraz wins v Sinner"))
            shield.face(item("a", "Alcaraz beats Sinner"))
            shield.face(item("b", "Zverev v Musetti"))
            advanceUntilIdle()
            assertEquals(SpoilerRules.NEUTRAL, shield.face(item("a", "Alcaraz beats Sinner")).title)
            assertEquals(listOf("a"), shield.state.value.safe.keys.toList())
        }

    @Test
    fun `what is known is kept in the store and the channel list survives a restart`() = runTest {
        val store = InMemoryShieldStore()
        val before = shield(store)
        before.setChannel(ShieldedChannel("UCaaaaaaaaaaaaaaaaaaaaaa", "Tennis TV"), on = true)
        reply = answerWith(SpoilerVerdict("a", true, "Alcaraz v Sinner"))
        before.face(item("a", "Alcaraz beats Sinner"))
        advanceUntilIdle()
        val again = shield(store)
        assertTrue(again.channelShielded("UCaaaaaaaaaaaaaaaaaaaaaa", null))
        assertEquals("Alcaraz v Sinner", again.face(item("a", "Alcaraz beats Sinner")).title)
    }

    @Test
    fun `switching a channel off shows its titles again`() = runTest {
        val shield = shield()
        val channel = ShieldedChannel("UCaaaaaaaaaaaaaaaaaaaaaa", "Tennis TV")
        shield.setChannel(channel, on = true)
        shield.setChannel(channel, on = false)
        assertFalse(shield.face(item("a", "Alcaraz beats Sinner")).shielded)
    }
}
