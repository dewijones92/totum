package com.dewijones92.totum.cli.serve.targets

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.jellyfin.JellyfinItem
import com.dewijones92.totum.data.jellyfin.JellyfinKind
import com.dewijones92.totum.data.jellyfin.toPlayable
import com.dewijones92.totum.playback.cast.CastSubtitleSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class TargetStreamsJellyfinSubtitlesTest {

    private val id = "0123456789abcdef0123456789abcdef"
    private val film = JellyfinItem(id, "Spinal Tap II", JellyfinKind.MOVIE, container = "mkv")
        .toPlayable(HttpUrl.of("https://totum.example/fetch/jellyfin/stream/x.mkv"))
    private val looked = mutableListOf<String>()
    private val tracks = listOf(
        CastSubtitleSource("http://jellyfin.local/Videos/$id/s/Subtitles/2/Stream.vtt?api_key=k", "en", "English"),
    )
    private val streams = TargetStreams(
        { _, _ -> "https://home/x" },
        { found -> "http://jellyfin.local/Videos/$found/stream?static=true&api_key=k" },
        SoundFiles(Files.createTempDirectory("sounds").toFile()),
        jellyfinSubtitles = { found ->
            looked += found
            tracks
        },
    )

    @Test
    fun `a Jellyfin item cast to a TV carries the subtitle tracks Jellyfin offers`() = runBlocking {
        val stream = streams.streamFor(film, picture = true)

        assertEquals(tracks, stream?.cast?.subtitles)
        assertEquals(listOf(id), looked)
    }

    @Test
    fun `a speaker is not shown a picture, so Jellyfin is not asked about subtitles`() = runBlocking {
        val stream = streams.streamFor(film, picture = false)

        assertEquals(emptyList<CastSubtitleSource>(), stream?.cast?.subtitles)
        assertEquals(emptyList<String>(), looked)
    }

    @Test
    fun `with no way to ask Jellyfin the item still casts, with no tracks`() = runBlocking {
        val plain = TargetStreams(
            { _, _ -> "https://home/x" },
            { found -> "http://jellyfin.local/Videos/$found/stream?static=true&api_key=k" },
            SoundFiles(Files.createTempDirectory("sounds").toFile()),
        )

        assertEquals(emptyList<CastSubtitleSource>(), plain.streamFor(film, picture = true)?.cast?.subtitles)
    }
}
