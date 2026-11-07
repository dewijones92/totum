package com.dewijones92.totum.cli.serve.targets

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.backup.toBackup
import com.dewijones92.totum.data.targets.TargetEntry
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeProgressTest {

    private val history = FakeYouTubeWatchHistory()
    private var signedIn = true
    private val progress = YouTubeProgress(history) { signedIn }

    private fun entry(id: String, reports: Boolean, addedBy: String = "Dewi's phone") = TargetEntry(
        PlayableItem(
            MediaItem(MediaItemId(id), SourceId("c"), "Bluey $id", publishedAt = null, duration = null),
            PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=$id")),
        ).toBackup(),
        addedBy = addedBy,
        reportsToYouTube = reports,
    )

    @Test
    fun `an owner's item is reported in seconds, opening the session once`() = runBlocking {
        val owner = entry("aaaaaaaaaaa", reports = true)

        progress.report(owner, 30_000, 120_000, finished = false)
        progress.report(owner, 60_000, 120_000, finished = true)

        assertEquals(listOf("aaaaaaaaaaa"), history.sessions)
        assertEquals(
            listOf(
                FakeYouTubeWatchHistory.Report("aaaaaaaaaaa", 30f, 120f, false),
                FakeYouTubeWatchHistory.Report("aaaaaaaaaaa", 60f, 120f, true),
            ),
            history.reports,
        )
    }

    @Test
    fun `someone else's item, a signed-out Pi and an unknown length report nothing`() = runBlocking {
        progress.report(entry("bbbbbbbbbbb", reports = false, addedBy = "Wife's phone"), 1_000, 60_000, false)
        progress.report(entry("ccccccccccc", reports = true), 1_000, null, false)
        signedIn = false
        progress.report(entry("ddddddddddd", reports = true), 1_000, 60_000, false)

        assertTrue(history.reports.isEmpty())
        assertTrue(history.sessions.isEmpty())
    }
}
