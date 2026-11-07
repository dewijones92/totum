package com.dewijones92.totum.cli.serve.targets

import com.dewijones92.totum.data.targets.SoundSettings
import com.dewijones92.totum.data.targets.TargetEntry
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.playback.VolumeBoost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DeckStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val state = TargetState(
        speaker("bar"),
        entries = listOf(TargetEntry(podcastItem("a"), "g", "Bluey", "My phone", reportsToYouTube = true)),
        at = 0,
        volume = 33,
        sound = SoundSettings(skipSilence = true, boost = VolumeBoost.AUTO),
    )

    @Test
    fun `a saved queue, its provenance and its sound come back as they were`() {
        val file = File(tmp.root, "target-bar.json")

        DeckStore(file).save(state, "bar")

        assertEquals(state, DeckStore(file).restore("bar"))
    }

    @Test
    fun `no file is no queue, and an unreadable one is no queue rather than a crash`() {
        assertNull(DeckStore(File(tmp.root, "none.json")).restore("bar"))
        val corrupt = tmp.newFile("corrupt.json").apply { writeText("{") }
        assertNull(DeckStore(corrupt).restore("bar"))
    }

    @Test
    fun `a queue that cannot be written does not stop the deck`() {
        DeckStore(File(tmp.root, "no/such/dir/x.json")).save(state, "bar")
    }

    @Test
    fun `saving again replaces the queue, so a restart sees the last one`() {
        val file = File(tmp.root, "target-bar.json")
        val store = DeckStore(file)

        store.save(state, "bar")
        store.save(state.copy(volume = 80, entries = emptyList()), "bar")

        assertEquals(80, store.restore("bar")?.volume)
        assertEquals(0, store.restore("bar")?.entries?.size)
    }
}
