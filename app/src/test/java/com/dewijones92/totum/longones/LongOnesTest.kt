package com.dewijones92.totum.longones

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.LongOnesRules
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.video.LengthCheck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class LongOnesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val asked = mutableListOf<String>()
    private val truth = mutableMapOf<String, LengthCheck>()
    private var played = mapOf<MediaItemId, PlayState>()
    private var suppressed = setOf<String>()
    private var liveIds = setOf<String>()
    private var onWifi = true
    private var storeKnows = mapOf<String, Duration>()
    private var pool = LongOnesPool(emptyList(), emptyList(), emptyList())

    private fun channel(key: Any) = "https://www.youtube.com/channel/UC${key.toString().padStart(22, 'x')}"

    private fun video(
        id: String,
        channel: Any,
        hoursAgo: Int,
        length: Duration? = null,
        kind: MediaContentKind = MediaContentKind.STANDARD,
    ) = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId("ytfeed:SUBSCRIPTIONS"),
        title = id,
        publishedAt = Instant.parse("2026-11-20T12:00:00Z").minusSeconds(hoursAgo * 3600L),
        duration = length,
        sourceUrl = HttpUrl.of(channel(channel)),
        mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=$id"),
        contentKind = kind,
    )

    private fun lengths() = VideoLengths(
        File(folder.root, "lengths.json"),
        ask = { item ->
            asked += item.id.value
            truth[item.id.value] ?: LengthCheck.Unknown("nobody told the test")
        },
    )

    private fun longOnes(rules: LongOnesRules = LongOnesRules(), budget: Int = 40) = LongOnes(
        pool = { pool },
        playStates = { played },
        withoutNotForMe = { items -> items.filter { it.id.value !in suppressed } },
        liveNow = { it.id.value in liveIds },
        freeLength = { storeKnows[it.id.value] },
        lengths = lengths(),
        unmetered = { onWifi },
        rules = rules,
        lookupBudget = budget,
    )

    private fun known(seconds: Long) = LengthCheck.Known(seconds, wasLive = false, via = "test")

    @Test
    fun `lengths the feed already carries choose the list with no look-up at all`() = runTest {
        pool = LongOnesPool(
            feed = listOf(video("long", 'a', 1, 40.minutes), video("short", 'b', 2, 5.minutes)),
            uploads = emptyList(),
            episodes = emptyList(),
        )
        val service = longOnes()

        service.refresh("test")

        assertEquals(listOf("long"), service.state.value.items?.map { it.id.value })
        assertEquals(emptyList<String>(), asked)
    }

    @Test
    fun `the same video from the feed and from RSS keeps the length the feed knew`() = runTest {
        pool = LongOnesPool(
            feed = listOf(video("both", 'a', 1, 40.minutes)),
            uploads = listOf(video("both", 'a', 1)),
            episodes = emptyList(),
        )
        val service = longOnes()

        service.refresh("test")

        assertEquals(listOf("both"), service.state.value.items?.map { it.id.value })
        assertEquals(emptyList<String>(), asked)
    }

    @Test
    fun `an upload with no length is looked up, chosen if long, and its length kept`() = runTest {
        pool = LongOnesPool(emptyList(), listOf(video("rss1", 'a', 1), video("rss2", 'b', 2)), emptyList())
        truth["rss1"] = known(45 * 60)
        truth["rss2"] = known(8 * 60)
        val service = longOnes()

        service.refresh("test")
        service.refresh("again")

        assertEquals(listOf("rss1"), service.state.value.items?.map { it.id.value })
        assertEquals("each length is asked of YouTube once, ever", listOf("rss1", "rss2"), asked.sorted())
    }

    @Test
    fun `lengths already learned from a stored lookup are used before YouTube is asked`() = runTest {
        pool = LongOnesPool(emptyList(), listOf(video("seen", 'a', 1)), emptyList())
        storeKnows = mapOf("seen" to 70.minutes)
        val service = longOnes()

        service.refresh("test")

        assertEquals(listOf("seen"), service.state.value.items?.map { it.id.value })
        assertEquals(emptyList<String>(), asked)
    }

    @Test
    fun `look-ups go newest first and stop as soon as the list is full`() = runTest {
        val uploads = (1..30).map { video("v$it", it, it) }
        pool = LongOnesPool(emptyList(), uploads, emptyList())
        uploads.forEach { truth[it.id.value] = known(30 * 60) }
        val service = longOnes(LongOnesRules(limit = 5))

        service.refresh("test")

        assertEquals(listOf("v1", "v2", "v3", "v4", "v5"), service.state.value.items?.map { it.id.value })
        assertTrue("asked ${asked.size}", asked.size <= 8)
        assertEquals(listOf("v1", "v2", "v3", "v4"), asked.take(4).sorted())
    }

    @Test
    fun `one refresh never asks more than its budget, and the next carries on deeper`() = runTest {
        val uploads = (1..30).map { video("v$it", it, it) }
        pool = LongOnesPool(emptyList(), uploads, emptyList())
        uploads.forEach { truth[it.id.value] = known(5 * 60) }
        val service = longOnes(budget = 8)

        service.refresh("first")
        val first = asked.size
        service.refresh("second")

        assertEquals(8, first)
        assertEquals(16, asked.size)
        assertTrue(service.state.value.items.orEmpty().isEmpty())
    }

    @Test
    fun `no channel has more than two in the list, even when the look-ups find five`() = runTest {
        val busy = (1..5).map { video("busy$it", 'a', it) }
        val others = (1..12).map { video("other$it", 100 + it, 10 + it) }
        pool = LongOnesPool(emptyList(), busy + others, emptyList())
        (busy + others).forEach { truth[it.id.value] = known(40 * 60) }
        val service = longOnes()

        service.refresh("test")

        val chosen = service.state.value.items.orEmpty()
        assertEquals(12, chosen.size)
        assertEquals(2, chosen.count { it.id.value.startsWith("busy") })
    }

    @Test
    fun `Shorts and live streams are never chosen and a live one is never looked up`() = runTest {
        pool = LongOnesPool(
            feed = listOf(video("tagged-short", 'a', 1, 90.minutes, kind = MediaContentKind.SHORT)),
            uploads = listOf(video("on-air", 'b', 2), video("plain", 'c', 3)),
            episodes = emptyList(),
        )
        liveIds = setOf("on-air")
        truth["plain"] = known(60 * 60)
        val service = longOnes()

        service.refresh("test")

        assertEquals(listOf("plain"), service.state.value.items?.map { it.id.value })
        assertEquals(listOf("plain"), asked)
    }

    @Test
    fun `a stream YouTube says is on now when asked is left out and not asked again soon`() = runTest {
        pool = LongOnesPool(emptyList(), listOf(video("surprise", 'a', 1), video("fine", 'b', 2)), emptyList())
        truth["surprise"] = LengthCheck.LiveNow("test")
        truth["fine"] = known(50 * 60)
        val service = longOnes()

        service.refresh("test")
        service.refresh("again")

        assertEquals(listOf("fine"), service.state.value.items?.map { it.id.value })
        assertEquals(listOf("fine", "surprise"), asked.sorted())
    }

    @Test
    fun `anything already started or finished, and anything marked not for me, is left out`() = runTest {
        pool = LongOnesPool(
            feed = listOf(
                video("finished", 'a', 1, 40.minutes),
                video("half", 'b', 2, 40.minutes),
                video("hidden", 'c', 3, 40.minutes),
                video("fresh", 'd', 4, 40.minutes),
            ),
            uploads = emptyList(),
            episodes = emptyList(),
        )
        played = mapOf(
            MediaItemId("finished") to PlayState.Played,
            MediaItemId("half") to PlayState.InProgress(60_000, 2_400_000),
        )
        suppressed = setOf("hidden")
        val service = longOnes()

        service.refresh("test")

        assertEquals(listOf("fresh"), service.state.value.items?.map { it.id.value })
    }

    @Test
    fun `on mobile data nothing is asked of YouTube and the page says it is waiting for Wi-Fi`() = runTest {
        pool = LongOnesPool(
            feed = listOf(video("known", 'a', 5, 40.minutes)),
            uploads = listOf(video("unknown", 'b', 1)),
            episodes = emptyList(),
        )
        onWifi = false
        val service = longOnes()

        service.refresh("test")

        assertEquals(emptyList<String>(), asked)
        assertEquals(listOf("known"), service.state.value.items?.map { it.id.value })
        assertTrue(service.state.value.waitingForWifi)
        assertEquals(1, service.state.value.lengthsWaiting)
    }

    @Test
    fun `a refresh that does not look anything up still builds the list from what is cached`() = runTest {
        pool = LongOnesPool(emptyList(), listOf(video("unknown", 'a', 1)), emptyList())
        val service = longOnes()

        service.refresh("test", lookUp = false)

        assertEquals(emptyList<String>(), asked)
        assertEquals(emptyList<MediaItem>(), service.state.value.items)
    }

    @Test
    fun `a podcast show counts as one source, so its long episodes cannot flood the list`() = runTest {
        val feed = "https://feeds.example.com/show.rss"
        val episodes = (1..6).map {
            MediaItem(
                id = MediaItemId("ep$it"),
                sourceId = SourceId(feed),
                title = "ep$it",
                publishedAt = Instant.parse("2026-11-20T12:00:00Z").minusSeconds(it * 3600L),
                duration = 50.minutes,
                mediaUrl = HttpUrl.of("https://cdn.example.com/ep$it.mp3"),
            )
        }
        pool = LongOnesPool(emptyList(), (1..11).map { video("v$it", it, 20 + it, 30.minutes) }, episodes)
        val service = longOnes()

        service.refresh("test")

        val chosen = service.state.value.items.orEmpty()
        assertEquals(12, chosen.size)
        assertEquals(2, chosen.count { it.id.value.startsWith("ep") })
        assertEquals(emptyList<String>(), asked)
    }

    @Test
    fun `a second refresh while one is running does not start another`() = runTest {
        pool = LongOnesPool(emptyList(), emptyList(), emptyList())
        val gate = CompletableDeferred<Unit>()
        val slow = LongOnes(
            pool = { gate.await(); pool },
            playStates = { played },
            withoutNotForMe = { it },
            liveNow = { false },
            freeLength = { null },
            lengths = lengths(),
            unmetered = { true },
        )

        val first = async { slow.refresh("first") }
        yield()
        val second = slow.refresh("second")
        gate.complete(Unit)

        assertEquals(LongOnesRun.AlreadyRunning, second)
        assertTrue(first.await() is LongOnesRun.Done)
    }
}
