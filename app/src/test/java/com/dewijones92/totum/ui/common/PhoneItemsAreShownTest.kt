package com.dewijones92.totum.ui.common

import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.PhoneMediaEntry
import com.dewijones92.totum.domain.PhoneMediaIds
import com.dewijones92.totum.domain.PhoneMediaKind
import com.dewijones92.totum.domain.toPlayableOrNull
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneItemsAreShownTest {

    private fun entry(
        kind: PhoneMediaKind,
        contentKind: MediaContentKind = MediaContentKind.STANDARD,
    ) = PhoneMediaEntry(
        kind = kind,
        key = PhoneMediaIds.keyOf("7", "/storage/emulated/0/x"),
        title = "x",
        path = "/storage/emulated/0/x",
        uri = "content://x",
        contentKind = contentKind,
    ).toPlayableOrNull()!!

    @Test
    fun `a video and spoken audio on the phone are shown as files on this phone`() {
        assertEquals(Shown.PHONE, shownAs(entry(PhoneMediaKind.VIDEO).item, MediaKind.VIDEO))
        assertEquals(Shown.PHONE, shownAs(entry(PhoneMediaKind.AUDIO).item, MediaKind.PODCAST))
    }

    @Test
    fun `music on the phone is shown as music like music anywhere else`() {
        assertEquals(Shown.MUSIC, shownAs(entry(PhoneMediaKind.AUDIO, MediaContentKind.MUSIC).item, MediaKind.PODCAST))
    }
}
