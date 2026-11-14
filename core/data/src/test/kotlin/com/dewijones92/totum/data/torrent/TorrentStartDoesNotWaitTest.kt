package com.dewijones92.totum.data.torrent

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.torrent.fake.FakeHomeTorrentServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class TorrentStartDoesNotWaitTest {

    @Test
    fun `starting a torrent never waits on a poster lookup`() {
        val slow = object : HomeTorrentServer by FakeHomeTorrentServer() {
            override suspend fun posterFor(torrentName: String): HttpUrl? {
                delay(60_000)
                return null
            }
        }
        val torrent = PreparedTorrent(
            "abc123",
            "Some.Show.S01.1080p-GROUP",
            listOf(TorrentFile(1, "S01E01.mkv", 1_000_000_000)),
        )

        val items = runBlocking { withTimeout(2_000) { TorrentPlayables.queueItems(slow, torrent) } }

        assertEquals(listOf(null), items.map { it.item.thumbnailUrl })
    }
}
