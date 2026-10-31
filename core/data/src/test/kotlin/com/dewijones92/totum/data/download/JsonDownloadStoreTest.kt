package com.dewijones92.totum.data.download

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JsonDownloadStoreTest {

    private val dir = Files.createTempDirectory("store").toFile()
    private val file = File(dir, "downloads.json")

    private val item = PlayableItem(
        MediaItem(
            MediaItemId("ep-1"),
            SourceId("feed"),
            "An episode",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://x.example/a.mp3"),
        ),
        PlayHandle.Podcast(),
    )

    @Test
    fun `what was recorded is there after a restart`() = runTest {
        JsonDownloadStore(file).put(item, DownloadState.Downloaded("/d/a.media", audioOnly = true), audioOnly = true)
        val again = JsonDownloadStore(file)
        assertEquals(DownloadState.Downloaded("/d/a.media", audioOnly = true), again.get(MediaItemId("ep-1")))
        assertEquals("An episode", again.observeDownloaded().first().single().playable.item.title)
        assertEquals(true, again.request(MediaItemId("ep-1"))?.audioOnly)
    }

    @Test
    fun `progress and failures are kept, and removing forgets`() = runTest {
        val store = JsonDownloadStore(file)
        store.put(item, DownloadState.Downloading(10, 100), audioOnly = false)
        assertEquals(DownloadState.Downloading(10, 100), JsonDownloadStore(file).get(MediaItemId("ep-1")))
        store.put(item, DownloadState.Failed("403"), audioOnly = false)
        assertEquals(DownloadState.Failed("403"), JsonDownloadStore(file).get(MediaItemId("ep-1")))
        store.remove(MediaItemId("ep-1"))
        assertEquals(DownloadState.NotDownloaded, JsonDownloadStore(file).get(MediaItemId("ep-1")))
    }

    @Test
    fun `an unreadable file starts empty rather than failing`() = runTest {
        file.writeText("not json")
        assertTrue(JsonDownloadStore(file).observeRecords().first().isEmpty())
    }
}
