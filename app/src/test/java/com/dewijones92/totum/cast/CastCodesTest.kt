package com.dewijones92.totum.cast

import com.google.android.gms.cast.CastStatusCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastCodesTest {

    private val everyNamedCode = listOf(
        CastStatusCodes.SUCCESS,
        CastStatusCodes.NETWORK_ERROR,
        CastStatusCodes.TIMEOUT,
        CastStatusCodes.INTERRUPTED,
        CastStatusCodes.INTERNAL_ERROR,
        CastStatusCodes.UNKNOWN_ERROR,
        CastStatusCodes.AUTHENTICATION_FAILED,
        CastStatusCodes.INVALID_REQUEST,
        CastStatusCodes.CANCELED,
        CastStatusCodes.NOT_ALLOWED,
        CastStatusCodes.APPLICATION_NOT_FOUND,
        CastStatusCodes.APPLICATION_NOT_RUNNING,
        CastStatusCodes.MESSAGE_TOO_LARGE,
        CastStatusCodes.MESSAGE_SEND_BUFFER_TOO_FULL,
        CastStatusCodes.DEVICE_CONNECTION_SUSPENDED,
        CastStatusCodes.FAILED,
        CastStatusCodes.REPLACED,
        CastStatusCodes.MEDIA_ERROR,
        CastStatusCodes.ERROR_CAST_PLATFORM_INCOMPATIBLE,
        CastStatusCodes.ERROR_URL_INSEURE,
        CastStatusCodes.ERROR_HOST_NOT_ALLOWED,
        CastStatusCodes.ERROR_CAST_PLATFORM_NOT_CONNECTED,
        CastStatusCodes.ERROR_NO_CAST_CONFIGURATION,
        CastStatusCodes.ERROR_DEVICE_ID_FLAGS_NOT_SET,
        CastStatusCodes.ERROR_SERVICE_CREATION_FAILED,
        CastStatusCodes.ERROR_SERVICE_DISCONNECTED,
        CastStatusCodes.ERROR_STOPPING_SERVICE_FAILED,
    )

    @Test
    fun `every code the table knows is named, led by its number, and never called unknown`() {
        everyNamedCode.forEach { code ->
            val text = CastCodes.name(code)
            assertTrue(text, text.startsWith("$code "))
            assertFalse(text, "unknown code" in text)
        }
    }

    @Test
    fun `no two codes in the table share a number, so none overwrote another`() {
        assertEquals(everyNamedCode.size, everyNamedCode.toSet().size)
    }

    @Test
    fun `the codes that explain a drop carry their meaning in words`() {
        assertTrue(CastCodes.name(CastStatusCodes.REPLACED).contains("another sender took the TV"))
        assertTrue(CastCodes.name(CastStatusCodes.APPLICATION_NOT_RUNNING).contains("the receiver app is gone"))
        assertTrue(CastCodes.name(CastStatusCodes.SUCCESS).contains("a normal end, nothing went wrong"))
        assertTrue(CastCodes.name(CastStatusCodes.ERROR_URL_INSEURE).contains("plain http"))
    }

    @Test
    fun `a code the table does not know says it is unknown and keeps its number`() {
        val text = CastCodes.name(2155)

        assertTrue(text, text.startsWith("2155 unknown code (the SDK calls it \""))
        assertTrue(text, text.endsWith("\")"))
    }

    @Test
    fun `the unnamed codes from the reports are reported as unknown, not guessed at`() {
        listOf(2055, 2154, 2155).forEach { code ->
            assertTrue(CastCodes.name(code), "unknown code" in CastCodes.name(code))
        }
    }

    @Test
    fun `a suspension names the two reasons Cast gives, in words`() {
        assertEquals("1 the Cast service disconnected", CastCodes.suspendedReason(1))
        assertEquals("2 the network was lost", CastCodes.suspendedReason(2))
    }

    @Test
    fun `any other suspension reason is named like a status code`() {
        assertEquals(CastCodes.name(CastStatusCodes.TIMEOUT), CastCodes.suspendedReason(CastStatusCodes.TIMEOUT))
        assertTrue(CastCodes.suspendedReason(99_999), "unknown code" in CastCodes.suspendedReason(99_999))
    }
}
