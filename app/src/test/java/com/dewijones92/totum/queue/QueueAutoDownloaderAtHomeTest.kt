package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.download.fake.FakeDownloadManager
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.downloads.home.PhoneSide
import com.dewijones92.totum.settings.AppPreferences
import com.dewijones92.totum.settings.InMemoryAppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueAutoDownloaderAtHomeTest {

    private val dispatcher = StandardTestDispatcher()
    private val downloads = FakeDownloadManager()
    private val queue = MutableStateFlow(QueueSnapshot())
    private val finishedAtHome = mutableSetOf<String>()

    private fun entry(id: String) = QueueEntry(
        PlayableItem(
            MediaItem(
                id = MediaItemId(id),
                sourceId = SourceId("feed"),
                title = id,
                publishedAt = null,
                duration = null,
                mediaUrl = HttpUrl.of("https://example.com/$id.mp3"),
            ),
            PlayHandle.Podcast(),
        ),
    )

    private fun downloader() = QueueAutoDownloader(
        queue = queue,
        downloads = downloads,
        scope = CoroutineScope(dispatcher),
        isEnabled = { true },
        isAllowedOnThisNetwork = { true },
        fetchesAudioOnly = { true },
        atHome = { it.item.id.value in finishedAtHome },
    )

    @Test
    fun `a queued item the Pi finished is never copied by the auto-download`() = runTest(dispatcher) {
        finishedAtHome += "pi-film"
        downloader().start()

        queue.value = QueueSnapshot(listOf(entry("pi-film"), entry("on-the-web")))
        advanceUntilIdle()

        assertEquals(listOf("on-the-web"), downloads.requested.map { it.first.value })
    }

    @Test
    fun `an item stays skipped while it is at home, however often the queue changes`() = runTest(dispatcher) {
        finishedAtHome += "pi-film"
        downloader().start()

        queue.value = QueueSnapshot(listOf(entry("pi-film")))
        advanceUntilIdle()
        queue.value = QueueSnapshot(listOf(entry("pi-film"), entry("later")))
        advanceUntilIdle()
        queue.value = QueueSnapshot(listOf(entry("later"), entry("pi-film")))
        advanceUntilIdle()

        assertEquals(listOf("later"), downloads.requested.map { it.first.value })
    }

    @Test
    fun `the reason is logged once for the item, naming that it is finished at home`() = runTest(dispatcher) {
        finishedAtHome += "pi-film"
        val lines = mutableListOf<String>()
        val previous = Diag.sink
        Diag.sink = Diag.Sink { _, _, message, _ -> if ("finished at home" in message) lines += message }
        try {
            downloader().start()
            queue.value = QueueSnapshot(listOf(entry("pi-film")))
            advanceUntilIdle()
            queue.value = QueueSnapshot(listOf(entry("pi-film"), entry("other")))
            advanceUntilIdle()
        } finally {
            Diag.sink = previous
        }

        assertEquals(1, lines.size)
        assertTrue(lines.single(), "pi-film" in lines.single())
    }

    @Test
    fun `a queue of nearly six thousand with most of it at home fetches only what is not`() = runTest(dispatcher) {
        val all = (1..SCALE).map { "item$it" }
        val onTheWeb = listOf("item7", "item2500", "item$SCALE")
        finishedAtHome += all - onTheWeb.toSet()
        downloader().start()

        queue.value = QueueSnapshot(all.map(::entry))
        advanceUntilIdle()

        assertEquals(onTheWeb, downloads.requested.map { it.first.value })
    }

    @Test
    fun `the copy-on-Wi-Fi choice is off by default for a new install and for the phone side`() {
        assertFalse(AppPreferences.Settings().copyHomeOnWifi)
        assertFalse(InMemoryAppPreferences().settings.value.copyHomeOnWifi)
        assertFalse(PhoneSide({ null }, { emptyList() }, { true }).copyOnWifi())
    }

    private companion object {
        const val SCALE = 5_857
    }
}
