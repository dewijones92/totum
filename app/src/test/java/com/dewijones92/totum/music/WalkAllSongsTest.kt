package com.dewijones92.totum.music

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.Page
import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.innertube.music.MusicResult
import com.dewijones92.totum.innertube.music.MusicSong
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkAllSongsTest {

    private fun song(id: String) =
        MusicSong(id, "song $id", "Erik Satie", null, 120, null, HttpUrl.of("https://www.youtube.com/watch?v=$id"))

    private fun pages(
        vararg pages: Pair<String?, MusicResult<Page<MusicSong>>>,
    ): suspend (PageToken?) -> MusicResult<Page<MusicSong>> {
        val byToken = pages.toMap()
        return { after -> byToken[after?.value] ?: MusicResult.Failure("no page ${after?.value}") }
    }

    @Test
    fun `every song of every page lands exactly once`() = runTest {
        val walk = walkAllSongs(
            pages(
                null to MusicResult.Success(Page(listOf(song("a"), song("b")), PageToken("2"))),
                "2" to MusicResult.Success(Page(listOf(song("b"), song("c")), PageToken("3"))),
                "3" to MusicResult.Success(Page.last(listOf(song("d")))),
            ),
        )

        assertEquals(listOf("a", "b", "c", "d"), walk.songs.map { it.videoId })
        assertEquals(3, walk.pages)
        assertEquals("the last page", walk.stopped)
    }

    @Test
    fun `a failed page keeps what was gathered and says where it stopped`() = runTest {
        val walk = walkAllSongs(
            pages(null to MusicResult.Success(Page(listOf(song("a")), PageToken("2")))),
        )

        assertEquals(listOf("a"), walk.songs.map { it.videoId })
        assertTrue(walk.stopped, walk.stopped.startsWith("page 2 failed"))
    }

    @Test
    fun `a page that adds nothing new ends the walk, so an endless token cannot loop`() = runTest {
        val walk = walkAllSongs(
            pages(
                null to MusicResult.Success(Page(listOf(song("a")), PageToken("2"))),
                "2" to MusicResult.Success(Page(listOf(song("a")), PageToken("2"))),
            ),
        )

        assertEquals(listOf("a"), walk.songs.map { it.videoId })
        assertEquals("page 2 added nothing new", walk.stopped)
    }

    @Test
    fun `progress reports the running count`() = runTest {
        val seen = mutableListOf<Int>()
        walkAllSongs(
            pages(
                null to MusicResult.Success(Page(listOf(song("a"), song("b")), PageToken("2"))),
                "2" to MusicResult.Success(Page.last(listOf(song("c")))),
            ),
        ) { seen += it }

        assertEquals(listOf(2, 3), seen)
    }
}
