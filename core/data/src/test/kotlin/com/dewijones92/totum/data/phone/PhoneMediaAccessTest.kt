package com.dewijones92.totum.data.phone

import com.dewijones92.totum.domain.PhoneMediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMediaAccessTest {

    private fun access(vararg granted: String) = PhoneMediaPermissions.access { it in granted }

    @Test
    fun `nothing granted reads nothing`() {
        val none = access()

        assertEquals(PhoneMediaAccess.NONE, none)
        assertFalse(none.anyReadable)
    }

    @Test
    fun `all three read permissions give full access to every kind`() {
        val all = access(
            PhoneMediaPermissions.READ_VIDEO,
            PhoneMediaPermissions.READ_AUDIO,
            PhoneMediaPermissions.READ_IMAGES
        )

        assertTrue(all.allFull)
        PhoneMediaKind.entries.forEach { assertEquals(KindAccess.FULL, all.of(it)) }
    }

    @Test
    fun `the user-selected permission alone is partial access to video and photos but none to audio`() {
        val partial = access(PhoneMediaPermissions.READ_SELECTED)

        assertEquals(KindAccess.PARTIAL, partial.video)
        assertEquals(KindAccess.PARTIAL, partial.photos)
        assertEquals(KindAccess.NONE, partial.audio)
        assertTrue(partial.partialOnly)
        assertTrue(partial.anyReadable)
    }

    @Test
    fun `full video access wins over the selected permission`() {
        val mixed = access(PhoneMediaPermissions.READ_VIDEO, PhoneMediaPermissions.READ_SELECTED)

        assertEquals(KindAccess.FULL, mixed.video)
        assertEquals(KindAccess.PARTIAL, mixed.photos)
    }

    @Test
    fun `a first visit with nothing granted asks, and a visit after asking points at Settings`() {
        assertEquals(PhoneMediaGate.NeedsAsk, PhoneMediaPermissions.gate(PhoneMediaAccess.NONE, askedBefore = false))
        assertEquals(PhoneMediaGate.Blocked, PhoneMediaPermissions.gate(PhoneMediaAccess.NONE, askedBefore = true))
    }

    @Test
    fun `any readable kind opens the page, asked before or not`() {
        val audioOnly = access(PhoneMediaPermissions.READ_AUDIO)

        assertEquals(PhoneMediaGate.Open(audioOnly), PhoneMediaPermissions.gate(audioOnly, askedBefore = true))
        assertEquals(PhoneMediaGate.Open(audioOnly), PhoneMediaPermissions.gate(audioOnly, askedBefore = false))
    }

    @Test
    fun `the request asks for all four permissions at once so one dialog covers everything`() {
        assertEquals(4, PhoneMediaPermissions.toAsk.size)
        assertTrue(PhoneMediaPermissions.READ_SELECTED in PhoneMediaPermissions.toAsk)
    }
}
