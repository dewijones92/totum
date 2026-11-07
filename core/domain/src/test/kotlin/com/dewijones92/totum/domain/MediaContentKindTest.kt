package com.dewijones92.totum.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaContentKindTest {

    @Test
    fun `silence is skipped for talk when asked, and never for music, live or a book`() {
        MediaContentKind.entries.forEach { kind ->
            val talk = kind in setOf(MediaContentKind.STANDARD, MediaContentKind.SHORT, MediaContentKind.WAS_LIVE)
            assertEquals(kind.name, talk, kind.skipsSilence(asked = true))
            assertEquals(kind.name, false, kind.skipsSilence(asked = false))
        }
    }
}
