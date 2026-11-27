package com.dewijones92.totum.downloads.home

import com.dewijones92.totum.data.backup.BackupItem
import com.dewijones92.totum.data.home.HomeJob
import com.dewijones92.totum.data.home.HomeJobState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JobsLogTest {

    private fun job(state: HomeJobState, bytes: Long) = HomeJob(
        id = "j1",
        item = BackupItem(
            itemId = "i1",
            sourceId = "s",
            title = "Film",
            contentKind = "STANDARD",
            playbackType = "torrent",
        ),
        state = state,
        audioOnly = false,
        bytes = bytes,
        totalBytes = 1_000,
    )

    @Test
    fun `a change of state is logged and a read where only progress moved is not`() {
        val log = JobsLog(everyQuietReads = 30)

        assertNotNull(log.line("tick", listOf(job(HomeJobState.DOWNLOADING, 10))))
        val progressOnly = (1..29).map { log.line("tick", listOf(job(HomeJobState.DOWNLOADING, 10L + it))) }

        assertTrue(progressOnly.all { it == null })
    }

    @Test
    fun `every thirtieth quiet read says how many there were and where the job has got to`() {
        val log = JobsLog(everyQuietReads = 30)
        log.line("tick", listOf(job(HomeJobState.DOWNLOADING, 10)))

        val lines = (1..60).map { log.line("tick", listOf(job(HomeJobState.DOWNLOADING, 10L + it))) }

        assertEquals(listOf(30, 60), lines.withIndex().filter { it.value != null }.map { it.index + 1 })
        assertTrue(lines[29]!!.contains("30 reads since the last change"))
        assertTrue(lines[29]!!.contains("Film=downloading"))
    }

    @Test
    fun `the next change says how many reads it follows`() {
        val log = JobsLog(everyQuietReads = 30)
        log.line("tick", listOf(job(HomeJobState.DOWNLOADING, 10)))
        repeat(5) { log.line("tick", listOf(job(HomeJobState.DOWNLOADING, 20L + it))) }

        val line = log.line("tick", listOf(job(HomeJobState.DONE, 1_000)))

        assertTrue(line!!.contains("after 5 reads where only progress moved"))
        assertTrue(line.contains("Film=done"))
    }

    @Test
    fun `no jobs and none before is silent`() {
        assertNull(JobsLog().line("tick", emptyList()))
        assertNull(JobsLog().line("tick", emptyList()))
    }
}
