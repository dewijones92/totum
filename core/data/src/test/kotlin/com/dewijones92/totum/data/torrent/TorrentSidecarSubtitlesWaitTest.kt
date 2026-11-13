package com.dewijones92.totum.data.torrent

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.torrent.fake.FakeHomeTorrentServer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentSidecarSubtitlesWaitTest {

    private val gate = CompletableDeferred<Unit>()
    private var prepares = 0
    private val files = listOf(
        TorrentFile(1, "Show.S01/Subs/Show.S01E01/2_English.srt", 60_000),
        TorrentFile(7, "Show.S01/Show.S01E01.mp4", 900_000_000),
    )
    private val fake = FakeHomeTorrentServer().also { server -> server.files = files }
    private val slowServer = object : HomeTorrentServer by fake {
        override suspend fun prepare(link: TorrentLink): PreparedTorrent? {
            prepares++
            gate.await()
            return fake.prepare(link)
        }
    }

    private fun episode() = MediaItem(
        id = MediaItemId("torrent:abc123:7"),
        sourceId = SourceId("torrent"),
        title = "S01E01",
        publishedAt = null,
        duration = null,
        mediaUrl = HttpUrl.of("https://home.test/ts/stream/Show.S01E01.mp4"),
    )

    @Test
    fun `a file list that is slow does not hold the start up, and is not given up on`() = runBlocking {
        val subtitles = TorrentSidecarSubtitles(slowServer, waitMs = 50, lateWaitMs = 5_000)

        val first = subtitles.forItem(episode())
        gate.complete(Unit)
        val second = subtitles.forItem(episode())

        assertTrue(first.isEmpty())
        assertEquals(listOf("en"), second.map { it.languageCode })
        assertEquals("the same file list was asked for once, not once per lookup", 1, prepares)
    }

    @Test
    fun `the lookup after a timeout waits longer for the file list that is still on its way`() = runBlocking {
        val subtitles = TorrentSidecarSubtitles(slowServer, waitMs = 50, lateWaitMs = 5_000)
        subtitles.forItem(episode())
        Thread {
            Thread.sleep(300)
            gate.complete(Unit)
        }.start()

        val started = System.currentTimeMillis()
        val second = subtitles.forItem(episode())
        val waited = System.currentTimeMillis() - started

        assertEquals(1, second.size)
        assertTrue("waited ${waited}ms", waited in 200..4_000)
    }

    @Test
    fun `a first lookup still gives up at the short wait`() = runBlocking {
        val subtitles = TorrentSidecarSubtitles(slowServer, waitMs = 80, lateWaitMs = 5_000)

        val started = System.currentTimeMillis()
        val first = subtitles.forItem(episode())
        val waited = System.currentTimeMillis() - started

        gate.complete(Unit)
        assertTrue(first.isEmpty())
        assertTrue("waited ${waited}ms", waited < 1_000)
    }
}
