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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TargetStreamsTest {

    private val sounds = Files.createTempDirectory("sounds").toFile().apply {
        resolve("brown-noise.opus").writeBytes(ByteArray(8))
    }

    private val asked = mutableListOf<String>()
    private val streams = TargetStreams(
        { item, picture ->
            asked += "${item.item.id.value}:$picture"
            "https://home/${item.item.id.value}"
        },
        { id -> "http://jellyfin.local/Videos/$id/stream?static=true&api_key=k" },
        SoundFiles(sounds),
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

    @Test
    fun `a Jellyfin id that is not an id gets no stream, and is not sent the phone's way either`() = runBlocking {
        val odd = film.copy(item = film.item.copy(id = MediaItemId("jellyfin:x&api_key=other")))

        assertNull(streams.urlFor(odd, picture = true))
        assertEquals(emptyList<String>(), asked)
    }

    private fun fileItem(path: String) = episode.copy(handle = PlayHandle.Podcast(localPath = path))

    @Test
    fun `a sound file on the Pi is played from the Pi's own disk, the phone's route never asked`() = runBlocking {
        val url = streams.urlFor(fileItem("file:///sounds/brown-noise.opus"), picture = false)

        assertEquals(sounds.resolve("brown-noise.opus").canonicalPath, url)
        assertEquals(emptyList<String>(), asked)
    }

    @Test
    fun `a file outside the sounds directory has no stream and is not sent the phone's way either`() = runBlocking {
        val bad = fileItem("file:///sounds/../etc/passwd")

        assertNull(streams.urlFor(bad, picture = false))
        assertEquals(emptyList<String>(), asked)
        assertTrue(streams.whyNone(bad), streams.whyNone(bad).contains("sounds"))
    }

    @Test
    fun `a missing sound says which one, and any other item has the general reason`() = runBlocking {
        val missing = fileItem("file:///sounds/nothing.opus")

        assertNull(streams.urlFor(missing, picture = false))
        assertTrue(streams.whyNone(missing), streams.whyNone(missing).contains("nothing.opus"))
        assertEquals("no stream was found for it", streams.whyNone(episode))
        assertEquals("Jellyfin is not set up on this Pi", TargetStreams({ _, _ -> null }, null).whyNone(film))
    }
}
