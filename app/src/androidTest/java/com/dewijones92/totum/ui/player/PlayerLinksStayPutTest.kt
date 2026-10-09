package com.dewijones92.totum.ui.player

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.music.fake.FakeYouTubeMusicCatalogue
import com.dewijones92.totum.playback.SleepTimer
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.ui.common.ItemActions
import com.dewijones92.totum.ui.common.LocalItemActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PlayerLinksStayPutTest {

    @get:Rule
    val rule = createComposeRule()

    private val actions = object : ItemActions {
        override fun queue(items: List<MediaItem>, next: Boolean) = Unit
        override fun addToPlaylist(items: List<MediaItem>) = Unit
        override fun peek(item: MediaItem) = Unit
        override fun download(item: MediaItem, audioOnly: Boolean) = Unit
        override fun deleteDownload(id: MediaItemId) = Unit
        override fun setPlayed(id: MediaItemId, played: Boolean) = Unit
        override fun sourceLink(item: MediaItem): (() -> Unit)? = {}
        override fun albumLink(item: MediaItem): (() -> Unit)? = {}
        override val audioMode: Boolean = false
        override fun switchMode(item: MediaItem) = Unit
    }

    @Test
    fun theLinksAreOneObjectWhileNothingTheyDependOnChanges() {
        var tick by mutableIntStateOf(0)
        val seen = mutableListOf<PlayerLinks>()
        val song = MediaItem(
            MediaItemId("Nn_CcTBataE"),
            SourceId("s"),
            "Sevillana",
            publishedAt = null,
            duration = null,
            contentKind = MediaContentKind.MUSIC,
        )
        val catalogue = FakeYouTubeMusicCatalogue()
        val timer = SleepTimer(FakePlaybackController(), CoroutineScope(Dispatchers.Unconfined))
        rule.setContent {
            CompositionLocalProvider(LocalItemActions provides actions) {
                Text("tick $tick")
                seen += playerLinksFor(song, catalogue, emptyList(), 0, timer)
            }
        }

        repeat(TICKS) {
            rule.runOnIdle { tick++ }
            rule.waitForIdle()
        }

        assertTrue("the content should have recomposed on each tick, saw ${seen.size}", seen.size > TICKS)
        assertEquals(
            "a new PlayerLinks per tick recomposes the whole player under it",
            1,
            seen.map { System.identityHashCode(it) }.distinct().size,
        )
    }

    private companion object {
        const val TICKS = 3
    }
}
