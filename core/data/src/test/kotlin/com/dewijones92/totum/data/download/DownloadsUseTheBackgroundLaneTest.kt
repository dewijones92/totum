package com.dewijones92.totum.data.download

import com.dewijones92.totum.common.PoliteGate
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.asPlayable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsUseTheBackgroundLaneTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private fun item(id: String) = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId("feed"),
        title = id,
        publishedAt = null,
        duration = null,
    ).asPlayable()

    private class GatedStrategy(private val gate: PoliteGate) : DownloadStrategy {
        var atOnce = 0
        var mostAtOnce = 0

        override fun download(item: PlayableItem, target: File, audioOnly: Boolean): Flow<DownloadState> = flow {
            gate.passing("yt-dlp download ${item.item.id.value}") {
                atOnce++
                mostAtOnce = maxOf(mostAtOnce, atOnce)
                delay(EXTRACTION_MS)
                atOnce--
            }
            emit(DownloadState.Downloaded(target.absolutePath, audioOnly = audioOnly))
        }
    }

    @Test
    fun `download extractions are background, so they can never hold more than half the gate`() = runTest {
        val gate = PoliteGate(maxInFlight = 4, perSecond = 1_000.0, burst = 1_000)
        val strategy = GatedStrategy(gate)
        val manager = DefaultDownloadManager(temp.root, InMemoryDownloadStore(), strategy, TestScope(testScheduler))

        listOf("a", "b", "c", "d").forEach { manager.download(item(it)) }
        advanceUntilIdle()

        assertEquals("a play someone taps always has a permit left", 2, strategy.mostAtOnce)
    }

    private companion object {
        const val EXTRACTION_MS = 5_000L
    }
}
