package com.dewijones92.totum.settings

import com.dewijones92.totum.data.sponsorblock.SkipCategory
import com.dewijones92.totum.data.sponsorblock.SponsorBlockSegmentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OneTimeDefaultsTest {

    @Test
    fun `endcards and credits are skipped by default`() {
        assertTrue(SkipCategory.OUTRO in SponsorBlockSegmentSource.DEFAULT_CATEGORIES)
    }

    @Test
    fun `a phone that chose its categories before gets endcards added once`() {
        val chosen = setOf(SkipCategory.SPONSOR, SkipCategory.FILLER)

        assertEquals(chosen + SkipCategory.OUTRO, withEndcardsOnce(chosen, alreadyApplied = false))
    }

    @Test
    fun `once applied, switching endcards off again sticks`() {
        assertNull(withEndcardsOnce(setOf(SkipCategory.SPONSOR), alreadyApplied = true))
    }
}
