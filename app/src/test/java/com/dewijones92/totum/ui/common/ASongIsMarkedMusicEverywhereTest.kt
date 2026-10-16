package com.dewijones92.totum.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OndemandVideo
import com.dewijones92.totum.domain.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ASongIsMarkedMusicEverywhereTest {

    @Test
    fun `a song's glyph is the music note, wherever the pillar glyph is drawn`() {
        assertEquals(Icons.Filled.MusicNote, pillarIcon(MediaKind.VIDEO, music = true))
        assertEquals(Icons.Filled.OndemandVideo, pillarIcon(MediaKind.VIDEO))
    }
}
