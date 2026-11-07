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
import com.dewijones92.totum.playback.cast.CastItem
import com.dewijones92.totum.playback.cast.CastRung
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

    private val youtube = episode.copy(
        item = episode.item.copy(id = MediaItemId("yt1"), duration = kotlin.time.Duration.parse("PT2M")),
        handle = PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=abcdefghijk")),
    )

    private val ladder = CastItem(
        mediaId = "yt1",
        uri = "https://rr1.googlevideo.com/videoplayback?v=1080",
        separateAudioUrl = "https://rr1.googlevideo.com/videoplayback?a=1",
        ladder = listOf(CastRung("https://rr1.googlevideo.com/videoplayback?v=1080", 1080, "vp9")),
    )

    private fun withHome(cast: CastItem?) =
        TargetStreams({ _, picture -> "https://home/audio:$picture" }, null, SoundFiles(sounds)) { _, _ -> cast }

    @Test
    fun `a YouTube picture for a TV is the phone's pair of streams and its ladder`() = runBlocking {
        val stream = withHome(ladder).streamFor(youtube, picture = true)!!

        assertEquals(ladder.uri, stream.url)
        assertEquals(ladder, stream.cast)
    }

    @Test
    fun `a YouTube picture with no pair of streams falls back to the one address it has, as sound or picture asks`() =
        runBlocking {
            val stream = withHome(null).streamFor(youtube, picture = true)!!

            assertEquals("https://home/audio:true", stream.url)
            assertEquals(false, stream.cast!!.listening)
            assertEquals(120_000L, stream.cast!!.durationMs)
            assertEquals(emptyList<CastRung>(), stream.cast!!.ladder)
        }

    @Test
    fun `without a picture the item is sound to listen to, whatever it is`() = runBlocking {
        val stream = withHome(ladder).streamFor(youtube, picture = false)!!

        assertEquals("https://home/audio:false", stream.url)
        assertEquals(true, stream.cast!!.listening)
        assertEquals("yt1", stream.cast!!.mediaId)
    }

    @Test
    fun `a Jellyfin film and a sound file on the Pi are cast as the files they are`() = runBlocking {
        val jelly = streams.streamFor(film, picture = true)!!
        val file = streams.streamFor(fileItem("file:///sounds/brown-noise.opus"), picture = false)!!

        assertTrue(jelly.url, jelly.url.startsWith("http://jellyfin.local/Videos/"))
        assertEquals(false, jelly.cast!!.listening)
        assertEquals(sounds.resolve("brown-noise.opus").canonicalPath, file.cast!!.uri)
        assertEquals(true, file.cast!!.listening)
    }

    @Test
    fun `an item with no stream is none, so the deck passes over it`() = runBlocking {
        assertNull(TargetStreams({ _, _ -> null }, null).streamFor(episode, picture = false))
    }
}
