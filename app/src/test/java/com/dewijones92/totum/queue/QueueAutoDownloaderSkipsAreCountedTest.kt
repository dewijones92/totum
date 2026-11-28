package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.download.fake.FakeDownloadManager
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueAutoDownloaderSkipsAreCountedTest {

    private val dispatcher = StandardTestDispatcher()
    private val downloads = FakeDownloadManager()
    private val queue = MutableStateFlow(QueueSnapshot())
    private val lines = mutableListOf<String>()
    private lateinit var previousSink: Diag.Sink

    @Before
    fun listen() {
        previousSink = Diag.sink
        Diag.sink = Diag.Sink { _, _, message, _ -> lines += message }
    }

    @After
    fun stopListening() {
        Diag.sink = previousSink
    }

    private fun item(id: String, url: String? = "https://example.com/$id.mp3") = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId("feed"),
        title = id,
        publishedAt = null,
        duration = null,
        mediaUrl = url?.let(HttpUrl::of),
    )

    private fun entry(id: String, url: String? = "https://example.com/$id.mp3") =
        QueueEntry(PlayableItem(item(id, url), PlayHandle.Podcast()))

    private fun downloader(groupAllows: (String) -> Boolean = { true }) = QueueAutoDownloader(
        queue = queue,
        downloads = downloads,
        scope = CoroutineScope(dispatcher),
        isEnabled = { true },
        isAllowedOnThisNetwork = { true },
        groupAllows = groupAllows,
    )

    private fun summaries() = lines.filter { "dewidebug pass skips" in it }

    @Test
    fun `every kind of skip is counted in one summary line for the pass`() = runTest(dispatcher) {
        val album = QueueGroup("album:a", "An album")
        downloads.download(item("done"), audioOnly = true)
        downloads.setDownloading(MediaItemId("fresh"), 0, null)
        downloads.setDownloading(MediaItemId("half"), 40, 100)
        downloads.requested.clear()
        downloader(groupAllows = { it != album.id }).start()

        queue.value = QueueSnapshot(
            listOf(
                entry("done"),
                entry("fresh"),
                entry("half"),
                entry("no-url", url = null),
                QueueEntry(PlayableItem(item("local"), PlayHandle.LocalVideo("/tmp/v.mkv"))),
                entry("t1").copy(group = album),
                entry("t2").copy(group = album),
                entry("new"),
            ),
        )
        advanceUntilIdle()

        val summary = summaries().single()
        listOf(
            "2 in a group not downloaded automatically",
            "1 already on the phone",
            "1 downloading, 1 downloading with 0 bytes so far",
            "1 no fetch address yet",
            "1 a local file",
            "7 skipped, 1 asked for, 8 queued",
        ).forEach { assertTrue("$it missing from: $summary", it in summary) }
    }

    @Test
    fun `a queue of thousands already on the phone is one line, not one per item`() = runTest(dispatcher) {
        (1..6_164).forEach { downloads.download(item("p$it"), audioOnly = true) }
        lines.clear()
        downloader().start()

        queue.value = QueueSnapshot((1..6_164).map { entry("p$it") })
        advanceUntilIdle()

        assertEquals(1, summaries().size)
        assertTrue(summaries().single(), "6164 already on the phone" in summaries().single())
        assertTrue("one pass logged ${lines.size} lines", lines.size <= 3)
    }

    @Test
    fun `the same picture again is not said again, a changed one is`() = runTest(dispatcher) {
        downloads.download(item("done"), audioOnly = true)
        downloads.download(item("done-too"), audioOnly = true)
        downloader().start()

        queue.value = QueueSnapshot(listOf(entry("done"), entry("no-url", url = null)))
        advanceUntilIdle()
        queue.value = QueueSnapshot(listOf(entry("done-too"), entry("no-url", url = null)))
        advanceUntilIdle()
        assertEquals(1, summaries().size)

        queue.value = QueueSnapshot(
            listOf(entry("done"), entry("no-url", url = null), entry("also-no-url", url = null)),
        )
        advanceUntilIdle()
        assertEquals(2, summaries().size)
        assertTrue(summaries().last(), "2 no fetch address yet" in summaries().last())
    }

    @Test
    fun `a downloading row with no download behind it is recovered and named`() = runTest(dispatcher) {
        downloads.know(PlayableItem(item("stuck"), PlayHandle.Podcast()))
        downloads.strand(MediaItemId("stuck"))
        downloader().start()

        queue.value = QueueSnapshot(listOf(entry("stuck"), entry("other")))
        advanceUntilIdle()

        assertEquals(listOf(MediaItemId("stuck")), downloads.recovered)
        val said = lines.single { "dewidebug stranded downloads" in it }
        assertTrue(said, "1 row(s)" in said && "restarted 1 [stuck]" in said)
    }

    @Test
    fun `a download that is really running is not touched`() = runTest(dispatcher) {
        downloads.setDownloading(MediaItemId("busy"), 0, null)
        downloader().start()

        queue.value = QueueSnapshot(listOf(entry("busy")))
        advanceUntilIdle()

        assertTrue(downloads.recovered.isEmpty())
        assertTrue(lines.none { "dewidebug stranded downloads" in it })
        assertTrue(summaries().single(), "1 downloading with 0 bytes so far" in summaries().single())
    }

    @Test
    fun `no recovery is attempted while nothing is downloading`() = runTest(dispatcher) {
        downloader().start()

        queue.value = QueueSnapshot(listOf(entry("a"), entry("b")))
        advanceUntilIdle()

        assertTrue(downloads.recovered.isEmpty())
        assertEquals(listOf("a", "b"), downloads.requested.map { it.first.value })
    }
}
