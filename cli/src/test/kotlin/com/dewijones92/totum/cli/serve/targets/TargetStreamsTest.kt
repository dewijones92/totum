package com.dewijones92.totum.cli.serve.targets

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.jellyfin.JellyfinItem
import com.dewijones92.totum.data.jellyfin.JellyfinKind
import com.dewijones92.totum.data.jellyfin.toPlayable
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TargetStreamsTest {

    private val asked = mutableListOf<String>()
    private val streams = TargetStreams(
        { item, picture ->
            asked += "${item.item.id.value}:$picture"
            "https://home/${item.item.id.value}"
        },
        { id -> "http://jellyfin.local/Videos/$id/stream?static=true&api_key=k" },
    )

    private val film = JellyfinItem("0123456789abcdef0123456789abcdef", "Heat", JellyfinKind.MOVIE, container = "mp4")
        .toPlayable(HttpUrl.of("https://totum.example/fetch/jellyfin/stream/x.mp4"))

    private val episode = PlayableItem(
        MediaItem(MediaItemId("ep1"), SourceId("feed"), "Episode", publishedAt = null, duration = null),
        PlayHandle.Podcast(),
    )

    @Test
    fun `a Jellyfin item is read from Jellyfin itself, not from the phone's address`() = runBlocking {
        val url = streams.urlFor(film, picture = true)

        assertEquals("http://jellyfin.local/Videos/0123456789abcdef0123456789abcdef/stream?static=true&api_key=k", url)
        assertEquals(emptyList<String>(), asked)
    }

    @Test
    fun `anything else goes the way it always did, with whether a picture is wanted`() = runBlocking {
        assertEquals("https://home/ep1", streams.urlFor(episode, picture = false))
        assertEquals(listOf("ep1:false"), asked)
    }

    @Test
    fun `with no Jellyfin configured its items have no stream`() = runBlocking {
        val none = TargetStreams({ _, _ -> "https://home/x" }, null)

        assertNull(none.urlFor(film, picture = true))
    }
}
