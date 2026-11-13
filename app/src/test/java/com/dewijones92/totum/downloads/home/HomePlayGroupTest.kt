package com.dewijones92.totum.downloads.home

import com.dewijones92.totum.data.backup.BackupItem
import com.dewijones92.totum.data.backup.toPlayable
import com.dewijones92.totum.data.home.HomeJob
import com.dewijones92.totum.data.home.HomeJobState
import com.dewijones92.totum.domain.MediaItemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomePlayGroupTest {

    private fun job(hash: String, index: Int, title: String, state: HomeJobState = HomeJobState.DONE): HomeJob {
        val id = "torrent:$hash:$index"
        val item = BackupItem(
            itemId = id,
            sourceId = "torrent",
            title = title,
            author = "Spinal.Tap.II.2025.1080p",
            contentKind = "STANDARD",
            playbackType = "PODCAST",
        )
        return HomeJob(id, item, state, audioOnly = false, bytes = 1, totalBytes = 1)
    }

    private val film = job("abc", 12, "Spinal Tap II: The End Continues (2025)")
    private val featurette = job("abc", 10, "History of the Group")
    private val another = job("abc", 11, "The Legacy Continues")

    @Test
    fun `a row of a torrent plays through the torrent's group, the film first and extras after it`() {
        val play = homePlayOf(
            film.item.toPlayable()!!,
            listOf(featurette, another, film),
            extras = setOf(MediaItemId(featurette.id), MediaItemId(another.id)),
        )!!

        assertEquals("abc", play.group.id)
        assertEquals("Spinal.Tap.II.2025.1080p", play.group.title)
        assertEquals(listOf(film.id, featurette.id, another.id), play.items.map { it.item.id.value })
        assertEquals(MediaItemId(film.id), play.startAt)
    }

    @Test
    fun `only finished files of the same torrent join, and a lone file plays on its own`() {
        val running = job("abc", 11, "Not yet", HomeJobState.DOWNLOADING)
        val elsewhere = job("zzz", 1, "Other torrent")

        assertNull(homePlayOf(film.item.toPlayable()!!, listOf(film, running, elsewhere), emptySet()))
    }

    @Test
    fun `episodes keep their order`() {
        val e2 = job("pack", 1, "S01E02")
        val e1 = job("pack", 2, "S01E01")

        val play = homePlayOf(e2.item.toPlayable()!!, listOf(e2, e1), emptySet())!!

        assertEquals(listOf("S01E01", "S01E02"), play.items.map { it.item.title })
        assertEquals(MediaItemId(e2.id), play.startAt)
    }

    @Test
    fun `a file that is not a torrent's is never grouped`() {
        val video = job("abc", 1, "Video").let { it.copy(id = "abcdefghijk", item = it.item.copy(itemId = "abcdefghijk")) }

        assertNull(homePlayOf(video.item.toPlayable()!!, listOf(video, film), emptySet()))
    }
}
