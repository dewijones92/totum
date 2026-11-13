package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.download.JsonDownloadStore
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PosterRepairTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun item(id: String, source: String, author: String?, poster: String? = null) = PlayableItem(
        MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId(source),
            title = "t $id",
            publishedAt = null,
            duration = null,
            author = author,
            thumbnailUrl = poster?.let(HttpUrl::of),
            mediaUrl = HttpUrl.of("http://gluetun:8090/stream/x"),
        ),
        PlayHandle.Podcast(),
    )

    @Test
    fun `stored torrent jobs with no poster get one by their torrent's name, once per torrent`() = runBlocking {
        val store = JsonDownloadStore(tmp.newFile("downloads.json").apply { delete() })
        store.put(item("torrent:a:1", "torrent", "A.Film.2025"), DownloadState.Downloaded("/x.mkv"), false)
        store.put(item("torrent:a:2", "torrent", "A.Film.2025"), DownloadState.Downloaded("/y.mkv"), false)
        store.put(
            item("torrent:b:1", "torrent", "Has.Poster.2020", poster = "https://p.test/p.jpg"),
            DownloadState.Downloaded("/z.mkv"),
            false,
        )
        store.put(item("yt", "youtube", "Channel"), DownloadState.Downloaded("/v.mkv"), false)
        val asked = mutableListOf<String>()

        PosterRepair(store) { name ->
            asked += name
            "https://images.metahub.space/poster/medium/tt1/img"
        }.repair(store.observeRecords().first())

        assertEquals(listOf("A.Film.2025"), asked)
        val posters = store.observeRecords().first()
            .associate { it.item.item.id.value to it.item.item.thumbnailUrl?.value }
        assertEquals("https://images.metahub.space/poster/medium/tt1/img", posters["torrent:a:1"])
        assertEquals("https://images.metahub.space/poster/medium/tt1/img", posters["torrent:a:2"])
        assertEquals("https://p.test/p.jpg", posters["torrent:b:1"])
        assertEquals(null, posters["yt"])
    }

    @Test
    fun `a lookup that finds nothing or fails leaves the rows as they were`() = runBlocking {
        val store = JsonDownloadStore(tmp.newFile("downloads.json").apply { delete() })
        store.put(item("torrent:a:1", "torrent", "Unknown.Thing"), DownloadState.Downloaded("/x.mkv"), false)

        PosterRepair(store) { null }.repair(store.observeRecords().first())
        PosterRepair(store) { error("offline") }.repair(store.observeRecords().first())

        assertEquals(null, store.observeRecords().first().single().item.item.thumbnailUrl)
    }
}
