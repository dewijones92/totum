package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.data.backup.BackupItem
import com.dewijones92.totum.data.home.HomeJob
import com.dewijones92.totum.data.home.HomeJobState
import com.dewijones92.totum.data.home.HomeSeverity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeHealthTest {
    private var now = 0L
    private var free = 200_000_000_000L
    private var ai = HomeHealth.AiState.SIGNED_IN
    private var jellyfinUp = true
    private val health = HomeHealth(
        HomeHealth.Probes({ free }, { ai }, { jellyfinUp }),
        clock = { now },
    )
    private val film = BackupItem(
        "torrent:abc:1",
        "torrent",
        "Toy Story 5",
        contentKind = "STANDARD",
        playbackType = "VIDEO"
    )
    private fun downloading(bytes: Long) = HomeJob("torrent:abc:1", film, HomeJobState.DOWNLOADING, false, bytes, 1_000)

    private fun keys(jobs: List<HomeJob> = emptyList()) = runBlocking { health.problems(jobs) }.map { it.key }

    @Test
    fun `a healthy home server has no problems`() {
        assertEquals(emptyList<String>(), keys())
    }

    @Test
    fun `low disk, a missing AI service and a silent Jellyfin are each named`() {
        free = 12_000_000_000
        ai = HomeHealth.AiState.UNREACHABLE
        jellyfinUp = false
        val problems = runBlocking { health.problems(emptyList()) }
        assertEquals(listOf("disk", "ai", "jellyfin"), problems.map { it.key })
        assertEquals(HomeSeverity.BROKEN, problems.single { it.key == "ai" }.severity)
        assertTrue(problems.first().text, "12 GB" in problems.first().text)
    }

    @Test
    fun `a home server not signed in to Claude is a warning of its own`() {
        ai = HomeHealth.AiState.NOT_SIGNED_IN
        assertEquals(listOf("ai-sign-in"), keys())
    }

    @Test
    fun `a download that has not moved for ten minutes is called stalled, and clears when it moves`() {
        assertEquals(emptyList<String>(), keys(listOf(downloading(100))))
        now += 9 * 60_000
        assertEquals(emptyList<String>(), keys(listOf(downloading(100))))
        now += 2 * 60_000
        assertEquals(listOf("stalled:torrent:abc:1"), keys(listOf(downloading(100))))
        now += 1_000
        assertEquals(emptyList<String>(), keys(listOf(downloading(200))))
    }
}
