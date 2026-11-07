package com.dewijones92.totum.targets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RequesterIdentityTest {

    @Test
    fun `two phones of the same model get different labels`() {
        val one = RequesterIdentity.labelFor("Pixel 7", "a3f9c1d2-0000-4000-8000-000000000001")
        val two = RequesterIdentity.labelFor("Pixel 7", "77c1e0aa-0000-4000-8000-000000000002")

        assertEquals("Pixel 7 (a3f9)", one)
        assertNotEquals(one, two)
    }

    @Test
    fun `a blank model still reads as a phone`() {
        assertEquals("Phone (a3f9)", RequesterIdentity.labelFor("", "a3f9c1d2"))
    }
}
