package com.dewijones92.totum.data.channel

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.net.FetchResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

class ChannelLatestUploadsTest {

    private val realFeed = checkNotNull(javaClass.getResource("/youtube-channel-feed.xml")).readText()
    private val emptyFeed = """<feed xmlns="http://www.w3.org/2005/Atom"><title>Quiet</title></feed>"""
    private val now = Instant.parse("2026-09-24T12:00:00Z")
    private val store = InMemoryChannelLatestStore()
    private val asked = mutableListOf<String>()

    private fun uploads(
        concurrency: Int = 6,
        answer: suspend (String) -> FetchResult = { FetchResult.Success(realFeed) },
    ) = ChannelLatestUploads(
        fetcher = { url: HttpUrl ->
            val id = url.value.substringAfter("channel_id=")
            synchronized(asked) { asked += id }
            answer(id)
        },
        store = store,
        clock = Clock.fixed(now, ZoneOffset.UTC),
        concurrency = concurrency,
        maxAge = Duration.ofHours(6),
        batchSize = 2,
    )

    @Test
    fun `every channel's newest upload is stored`() = runTest {
        val summary = uploads().refresh(listOf("UCa", "UCb", "UCc")) as ChannelCheckSummary.Done

        assertEquals(3, summary.withUpload)
        assertEquals(listOf("giTmBaNGaHw"), store.observe().first().mapNotNull { it.latest?.id?.value }.distinct())
        assertEquals(setOf("UCa", "UCb", "UCc"), store.checkedAt().keys)
    }

    @Test
    fun `a channel checked recently is not asked again, unless forced`() = runTest {
        store.put(listOf(CheckedChannel("UCa", null, now.minus(Duration.ofHours(1)))))
        store.put(listOf(CheckedChannel("UCb", null, now.minus(Duration.ofHours(7)))))

        val summary = uploads().refresh(listOf("UCa", "UCb")) as ChannelCheckSummary.Done

        assertEquals(listOf("UCb"), asked)
        assertEquals(1, summary.skippedFresh)

        asked.clear()
        uploads().refresh(listOf("UCa", "UCb"), force = true)
        assertEquals(setOf("UCa", "UCb"), asked.toSet())
    }

    @Test
    fun `a failed fetch keeps what was known rather than wiping it`() = runTest {
        val known = checkNotNull(ChannelFeedParser.latest(realFeed))
        store.put(listOf(CheckedChannel("UCa", known, now.minus(Duration.ofDays(1)))))

        val summary = uploads(
            answer = { FetchResult.Failure("503") }
        ).refresh(listOf("UCa")) as ChannelCheckSummary.Done

        assertEquals(1, summary.failed)
        assertEquals(known, store.observe().first().single().latest)
    }

    @Test
    fun `a channel whose fetch just failed is not asked again on the next open, unless forced`() = runTest {
        var at = now
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant(): Instant = at
        }
        val failing = ChannelLatestUploads(
            fetcher = { url: HttpUrl ->
                synchronized(asked) { asked += url.value.substringAfter("channel_id=") }
                FetchResult.Failure("429")
            },
            store = store,
            clock = clock,
            maxAge = Duration.ofHours(6),
        )
        failing.refresh(listOf("UCa"))
        asked.clear()

        val again = failing.refresh(listOf("UCa")) as ChannelCheckSummary.Done
        assertEquals("a failure was re-asked on the very next open", emptyList<String>(), asked)
        assertEquals(1, again.skippedFailedRecently)

        at = now.plus(Duration.ofHours(1))
        failing.refresh(listOf("UCa"))
        assertEquals("after the retry window it is asked again", listOf("UCa"), asked)

        asked.clear()
        failing.refresh(listOf("UCa"), force = true)
        assertEquals(listOf("UCa"), asked)
    }

    @Test
    fun `a feed that briefly lists nothing does not wipe the upload that was known`() = runTest {
        val known = checkNotNull(ChannelFeedParser.latest(realFeed))
        store.put(listOf(CheckedChannel("UCa", known, now.minus(Duration.ofDays(1)))))

        uploads(answer = { FetchResult.Success(emptyFeed) }).refresh(listOf("UCa"))

        val row = store.observe().first().single()
        assertEquals(known, row.latest)
        assertEquals("still recorded as checked now", now, row.checkedAt)
    }

    @Test
    fun `a channel that never uploaded is checked, not failed`() = runTest {
        val summary = uploads(
            answer = { FetchResult.Success(emptyFeed) }
        ).refresh(listOf("UCa")) as ChannelCheckSummary.Done

        assertEquals(1, summary.neverUploaded)
        assertEquals(0, summary.failed)
        assertEquals(setOf("UCa"), store.checkedAt().keys)
    }

    @Test
    fun `an error page is a failure, and leaves the channel due`() = runTest {
        val summary = uploads(answer = { FetchResult.Success("<html>404</html>") }).refresh(listOf("UCa"))

        assertEquals(1, (summary as ChannelCheckSummary.Done).failed)
        assertTrue(store.checkedAt().isEmpty())
    }

    @Test
    fun `no more than the allowed number are fetched at once`() = runTest {
        val inFlight = AtomicInteger()
        var peak = 0
        uploads(concurrency = 3, answer = {
            val n = inFlight.incrementAndGet()
            synchronized(this@ChannelLatestUploadsTest) { peak = maxOf(peak, n) }
            delay(10)
            inFlight.decrementAndGet()
            FetchResult.Success(realFeed)
        }).refresh((1..20).map { "UC$it" })

        assertEquals(3, peak)
    }

    @Test
    fun `a second check while one runs does not start`() = runTest {
        val uploads = uploads(answer = {
            delay(100)
            FetchResult.Success(realFeed)
        })
        val first = launch { uploads.refresh(listOf("UCa")) }
        delay(10)

        assertEquals(ChannelCheckSummary.AlreadyRunning, uploads.refresh(listOf("UCb")))
        first.join()
    }
}
