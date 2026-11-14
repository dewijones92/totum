package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.download.fake.FakeDownloadManager
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.placeholderTitleFor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.coroutines.EmptyCoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class SharedMetadataRepairTest {
    private val id = MediaItemId("4kKd9-vCPts")
    private val url = HttpUrl.of("https://www.youtube.com/watch?v=${id.value}")
    private val placeholder = PlayableItem(
        MediaItem(
            id = id,
            sourceId = SourceId("shared"),
            title = placeholderTitleFor(id),
            publishedAt = null,
            duration = null,
            mediaUrl = url,
        ),
        PlayHandle.Video(url),
    )
    private val resolved = placeholder.item.copy(title = "A real title", author = "The channel")

    @Test
    fun `a downloaded placeholder learns its title after the network returns`() = runTest {
        val downloads = FakeDownloadManager()
        downloads.download(placeholder, audioOnly = true)
        val records = downloads.observeRecords().stateIn(backgroundScope, SharingStarted.Eagerly, emptyList())
        val queue = MutableStateFlow(QueueSnapshot(entries = listOf(QueueEntry(placeholder))))
        val online = CompletableDeferred<Unit>()
        val learned = mutableListOf<MediaItem>()
        var calls = 0
        SharedMetadataRepair(queue, downloads, {
            calls++
            resolved
        }, learned::add, { online.await() }, backgroundScope, io = EmptyCoroutineContext)
            .start()
        runCurrent()
        assertEquals(0, calls)
        online.complete(Unit)
        runCurrent()

        assertEquals(listOf(resolved), learned)
        val copy = downloads.observeDownloaded().first().single()
        assertEquals("A real title", copy.item.title)
        assertEquals("The channel", copy.item.author)
        assertEquals(resolved.title, records.value.single().item.item.title)
        assertTrue(copy.audioOnly)
        assertEquals(1, downloads.requested.size)
    }

    @Test
    fun `a torrent item with no picture learns one by its torrent's name, in the queue and downloads`() = runTest {
        val poster = HttpUrl.of("https://images.metahub.space/poster/medium/tt1/img")
        val torrent = PlayableItem(
            MediaItem(
                MediaItemId("torrent:abc:1"),
                SourceId("torrent"),
                "A film",
                publishedAt = null,
                duration = null,
                author = "A.Film.2025.1080p",
                mediaUrl = HttpUrl.of("https://home.test/stream/a.mkv"),
            ),
            PlayHandle.Podcast(),
        )
        val downloads = FakeDownloadManager()
        downloads.download(torrent, audioOnly = false)
        val queue = MutableStateFlow(QueueSnapshot(entries = listOf(QueueEntry(torrent))))
        val learned = mutableListOf<MediaItem>()
        val asked = mutableListOf<String>()
        SharedMetadataRepair(
            queue,
            downloads,
            { null },
            learned::add,
            {},
            backgroundScope,
            io = EmptyCoroutineContext,
            posterOf = { item ->
                asked += item.item.author.orEmpty()
                poster
            },
        ).start()
        runCurrent()

        assertEquals(listOf("A.Film.2025.1080p"), asked)
        assertEquals(listOf(poster), learned.map { it.thumbnailUrl })
        assertEquals(poster, downloads.observeRecords().first().single().item.item.thumbnailUrl)
    }

    @Test
    fun `an audiobook torrent is never given a film poster`() = runTest {
        val book = PlayableItem(
            MediaItem(
                MediaItemId("torrent:def:1"),
                SourceId("torrent"),
                "Chapter one",
                publishedAt = null,
                duration = null,
                author = "A.Book.Read.By.Someone",
                mediaUrl = HttpUrl.of("https://home.test/stream/a.mp3"),
                contentKind = MediaContentKind.AUDIOBOOK,
            ),
            PlayHandle.Podcast(),
        )
        val queue = MutableStateFlow(QueueSnapshot(entries = listOf(QueueEntry(book))))
        val asked = mutableListOf<String>()
        SharedMetadataRepair(
            queue,
            FakeDownloadManager(),
            { null },
            {},
            {},
            backgroundScope,
            io = EmptyCoroutineContext,
            posterOf = { item ->
                asked += item.item.author.orEmpty()
                null
            },
        ).start()
        runCurrent()

        assertEquals(emptyList<String>(), asked)
    }

    @Test
    fun `a failed metadata lookup retries without a queue edit`() = runTest {
        val downloads = FakeDownloadManager()
        val queue = MutableStateFlow(QueueSnapshot(entries = listOf(QueueEntry(placeholder))))
        val learned = mutableListOf<MediaItem>()
        var calls = 0
        SharedMetadataRepair(
            queue,
            downloads,
            { if (++calls == 1) null else resolved },
            learned::add,
            {},
            backgroundScope,
            io = EmptyCoroutineContext,
            retryDelayMs = 1_000,
            now = { testScheduler.currentTime },
        ).start()
        runCurrent()
        assertTrue(learned.isEmpty())
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(resolved), learned)
        assertEquals(2, calls)
    }

    @Test
    fun `a video that never resolves is asked five times with a growing gap, then left alone`() = runTest {
        val asked = mutableListOf<Long>()
        SharedMetadataRepair(
            MutableStateFlow(QueueSnapshot(entries = listOf(QueueEntry(placeholder)))),
            FakeDownloadManager(),
            {
                asked += testScheduler.currentTime
                null
            },
            {},
            {},
            backgroundScope,
            io = EmptyCoroutineContext,
            retryDelayMs = 1_000,
            now = { testScheduler.currentTime },
        ).start()
        runCurrent()
        advanceTimeBy(10_000_000)
        runCurrent()

        assertEquals(listOf(0L, 1_000L, 5_000L, 21_000L, 85_000L), asked)
    }

    @Test
    fun `a queue edit does not ask again about a video asked moments ago`() = runTest {
        val other = MediaItemId("aqz-KE-bpKQ")
        val otherUrl = HttpUrl.of("https://www.youtube.com/watch?v=${other.value}")
        val second = PlayableItem(
            placeholder.item.copy(id = other, title = placeholderTitleFor(other), mediaUrl = otherUrl),
            PlayHandle.Video(otherUrl),
        )
        val queue = MutableStateFlow(QueueSnapshot(entries = listOf(QueueEntry(placeholder))))
        val asked = mutableListOf<String>()
        SharedMetadataRepair(
            queue,
            FakeDownloadManager(),
            {
                asked += it.item.id.value
                null
            },
            {},
            {},
            backgroundScope,
            io = EmptyCoroutineContext,
            retryDelayMs = 30_000,
            now = { testScheduler.currentTime },
        ).start()
        runCurrent()
        advanceTimeBy(1_000)
        queue.value = QueueSnapshot(entries = listOf(QueueEntry(placeholder), QueueEntry(second)))
        runCurrent()

        assertEquals(listOf(id.value, other.value), asked)
    }

    @Test
    fun `a placeholder held only in the library is repaired too`() = runTest {
        val downloads = FakeDownloadManager()
        downloads.download(placeholder, audioOnly = true)
        SharedMetadataRepair(
            MutableStateFlow(QueueSnapshot()),
            downloads,
            { resolved },
            {},
            {},
            backgroundScope,
            io = EmptyCoroutineContext,
        ).start()
        runCurrent()

        assertEquals(resolved.title, downloads.observeDownloaded().first().single().item.title)
    }

    @Test
    fun `removing a placeholder cancels its pending metadata lookup`() = runTest {
        val queue = MutableStateFlow(QueueSnapshot(entries = listOf(QueueEntry(placeholder))))
        val lookup = CompletableDeferred<MediaItem>()
        val learned = mutableListOf<MediaItem>()
        SharedMetadataRepair(
            queue,
            FakeDownloadManager(),
            { withContext(NonCancellable) { lookup.await() } },
            learned::add,
            {},
            backgroundScope,
            io = EmptyCoroutineContext,
        ).start()
        runCurrent()
        queue.value = QueueSnapshot()
        runCurrent()
        lookup.complete(resolved)
        runCurrent()

        assertTrue(learned.isEmpty())
    }

    private fun dated(n: Int, kind: MediaContentKind = MediaContentKind.STANDARD): PlayableItem {
        val vid = MediaItemId("video-%05d".format(n))
        val link = HttpUrl.of("https://www.youtube.com/watch?v=${vid.value}")
        return PlayableItem(
            MediaItem(
                vid,
                SourceId("ytfeed"),
                "Video $n",
                publishedAt = null,
                duration = null,
                mediaUrl = link,
                contentKind = kind
            ),
            PlayHandle.Video(link),
        )
    }

    @Test
    fun `a queued video with no date learns it from its watch page, and so does its download`() = runTest {
        val day = Instant.parse("2026-10-30T12:00:00Z")
        val undated = dated(1)
        val downloads = FakeDownloadManager()
        downloads.download(undated, audioOnly = true)
        downloads.observeRecords().stateIn(backgroundScope, SharingStarted.Eagerly, emptyList())
        val learned = mutableListOf<MediaItem>()
        SharedMetadataRepair(
            MutableStateFlow(QueueSnapshot(entries = listOf(QueueEntry(undated)), currentIndex = 0)),
            downloads,
            { error("a title is known, so nothing is resolved") },
            learned::add,
            {},
            backgroundScope,
            io = EmptyCoroutineContext,
            dateOf = { if (it == undated.item.id) day else null },
        ).start()
        runCurrent()

        assertEquals(listOf(day), learned.map { it.publishedAt })
        assertEquals(day, downloads.observeDownloaded().first().single().item.publishedAt)
    }

    @Test
    fun `dates are asked only near what is playing, never for songs or rows that have one`() = runTest {
        val rows = (0 until 100).map { n ->
            when (n) {
                52 -> dated(n, MediaContentKind.MUSIC)
                53 -> dated(n).let { it.copy(item = it.item.copy(publishedText = "2 days ago")) }
                else -> dated(n)
            }
        }
        val asked = mutableListOf<String>()
        SharedMetadataRepair(
            MutableStateFlow(QueueSnapshot(entries = rows.map { QueueEntry(it) }, currentIndex = 50)),
            FakeDownloadManager(),
            { null },
            {},
            {},
            backgroundScope,
            io = EmptyCoroutineContext,
            dateOf = {
                asked += it.value
                null
            },
        ).start()
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()

        val expected = ((45..80) - 52 - 53).map { "video-%05d".format(it) }
        assertEquals(expected.toSet(), asked.toSet())
    }

    private fun torrentRow(hash: String, file: Int, name: String): PlayableItem = PlayableItem(
        MediaItem(
            MediaItemId("torrent:$hash:$file"),
            SourceId("torrent"),
            "$name file $file",
            publishedAt = null,
            duration = null,
            author = name,
            mediaUrl = HttpUrl.of("https://home.test/stream/$hash/$file.mkv"),
        ),
        PlayHandle.Podcast(),
    )

    private val somePoster = HttpUrl.of("https://images.metahub.space/poster/medium/tt1/img")

    private inline fun <T> capturingMetadataLines(body: (List<String>) -> T): T {
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val previous = Diag.sink
        Diag.sink = Diag.Sink { _, tag, message, _ -> if (tag == "metadata") lines += message }
        try {
            return body(lines)
        } finally {
            Diag.sink = previous
        }
    }

    @Test
    fun `every lookup runs off the thread that started the repair, and only the adoption comes back to it`() {
        val main = Executors.newSingleThreadExecutor { Thread(it, "pretend-main") }
        val lookedUpOn = Collections.synchronizedList(mutableListOf<String>())
        val adoptedOn = Collections.synchronizedList(mutableListOf<String>())
        val here = { lookedUpOn += Thread.currentThread().name }
        val music = dated(7).let {
            it.copy(item = it.item.copy(sourceId = SourceId("music:x"), contentKind = MediaContentKind.MUSIC))
        }
        val rows = listOf(placeholder, dated(1), music, torrentRow("aa", 1, "A.Film.2025.1080p"))
        val allFour = CompletableDeferred<Unit>()
        try {
            runBlocking {
                val scope = CoroutineScope(main.asCoroutineDispatcher() + Job())
                SharedMetadataRepair(
                    MutableStateFlow(QueueSnapshot(entries = rows.map { QueueEntry(it) }, currentIndex = 0)),
                    FakeDownloadManager(),
                    {
                        here()
                        resolved
                    },
                    {
                        adoptedOn += Thread.currentThread().name
                        if (adoptedOn.size == rows.size) allFour.complete(Unit)
                    },
                    {},
                    scope,
                    dateOf = {
                        here()
                        Instant.parse("2026-10-30T12:00:00Z")
                    },
                    artistOf = {
                        here()
                        "An artist"
                    },
                    posterOf = {
                        here()
                        somePoster
                    },
                ).start()
                withTimeout(10_000) { allFour.await() }
                scope.coroutineContext[Job]?.cancel()
            }
        } finally {
            main.shutdown()
        }
        assertEquals(4, lookedUpOn.size)
        assertTrue("looked up on $lookedUpOn", lookedUpOn.none { it.startsWith("pretend-main") })
        assertTrue("adopted on $adoptedOn", adoptedOn.all { it.startsWith("pretend-main") })
    }

    @Test
    fun `thirty files of one torrent ask for its poster once`() = runTest {
        val rows = (1..30).map { torrentRow("aa", it, "A.Film.2025.1080p") }
        val asked = mutableListOf<String>()
        val learned = mutableListOf<MediaItem>()
        SharedMetadataRepair(
            MutableStateFlow(QueueSnapshot(entries = rows.map { QueueEntry(it) })),
            FakeDownloadManager(),
            { null },
            learned::add,
            {},
            backgroundScope,
            io = EmptyCoroutineContext,
            posterOf = {
                asked += it.item.author.orEmpty()
                somePoster
            },
        ).start()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(listOf("A.Film.2025.1080p"), asked)
        assertEquals(30, learned.size)
        assertTrue(learned.all { it.thumbnailUrl == somePoster })
    }

    @Test
    fun `a long list is repaired in batches with a pause between them`() = runTest {
        val rows = (1..60).map { torrentRow("h%02d".format(it), 1, "Film.$it.2025") }
        val asked = mutableListOf<String>()
        var waits = 0
        SharedMetadataRepair(
            MutableStateFlow(QueueSnapshot(entries = rows.map { QueueEntry(it) })),
            FakeDownloadManager(),
            { null },
            {},
            { waits++ },
            backgroundScope,
            io = EmptyCoroutineContext,
            posterOf = {
                asked += it.item.author.orEmpty()
                somePoster
            },
            batchSize = 20,
            batchGapMs = 1_000,
        ).start()
        runCurrent()
        assertEquals(20, asked.size)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(40, asked.size)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(60, asked.size)
        assertEquals(3, waits)
    }

    @Test
    fun `a pass over sixty rows is one summary line, not a line per row`() = runTest {
        val names = (0 until 6).map { "Film.$it.2025" }
        val rows = names.flatMapIndexed { n, name -> (1..10).map { torrentRow("h$n", it, name) } }
        capturingMetadataLines { lines ->
            SharedMetadataRepair(
                MutableStateFlow(QueueSnapshot(entries = rows.map { QueueEntry(it) })),
                FakeDownloadManager(),
                { null },
                {},
                {},
                backgroundScope,
                io = EmptyCoroutineContext,
                posterOf = {
                    when (names.indexOf(it.item.author)) {
                        0, 1 -> somePoster
                        2, 3 -> null
                        else -> throw IOException("no route to the poster host")
                    }
                },
            ).start()
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()

            val summaries = lines.filter { it.startsWith("pass:") }
            assertEquals(lines.toString(), 1, summaries.size)
            val summary = summaries.single()
            assertTrue(summary, "due=60" in summary)
            assertTrue(summary, "repaired=20" in summary)
            assertTrue(summary, "none=20" in summary)
            assertTrue(summary, "failed=20" in summary)
            assertTrue(summary, "poster lookups=6" in summary)
            assertTrue(summary, "IOException=20" in summary)
            assertTrue(lines.toString(), lines.size <= 2)
        }
    }

    @Test
    fun `a start-up pass over Dewi's 5857 queue rows asks eight torrents and leaves a handful of lines`() = runTest {
        val names = (0 until 8).map { "Film.$it.2025.1080p" }
        val torrents = (0 until 114).map { torrentRow("h${it % 8}", it, names[it % 8]) }
        val undated = (0 until 36).map { dated(100 + it) }
        val done = (0 until 5_707).map {
            dated(1_000 + it).let { row -> row.copy(item = row.item.copy(publishedText = "1 day ago")) }
        }
        val rows = done.take(5_680) + undated + torrents + done.drop(5_680)
        val posterAsked = mutableListOf<String>()
        val dateAsked = mutableListOf<String>()
        capturingMetadataLines { lines ->
            SharedMetadataRepair(
                MutableStateFlow(QueueSnapshot(entries = rows.map { QueueEntry(it) }, currentIndex = 5_685)),
                FakeDownloadManager(),
                { null },
                {},
                {},
                backgroundScope,
                io = EmptyCoroutineContext,
                dateOf = {
                    dateAsked += it.value
                    null
                },
                posterOf = {
                    posterAsked += it.item.author.orEmpty()
                    throw IOException("no route to the poster host")
                },
            ).start()
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()

            assertEquals(5_857, rows.size)
            assertEquals(names.toSet(), posterAsked.toSet())
            assertEquals(8, posterAsked.size)
            assertEquals(36, dateAsked.size)
            assertEquals(lines.toString(), 2, lines.size)
            val summary = lines.single { it.startsWith("pass:") }
            assertTrue(summary, "due=150 of 150 candidate(s)" in summary)
            assertTrue(summary, "poster lookups=8 reused=106" in summary)
        }
    }
}
