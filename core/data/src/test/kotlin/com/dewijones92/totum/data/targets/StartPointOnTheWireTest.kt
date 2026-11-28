package com.dewijones92.totum.data.targets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartPointOnTheWireTest {

    private fun wire(request: TargetQueueRequest) = TargetsJson.encodeToString(TargetQueueRequest.serializer(), request)

    @Test
    fun `a request that names no start point does not carry the field at all`() {
        val sent = wire(TargetQueueRequest(emptyList(), QueueMode.PLAY))

        assertFalse("an old Pi would read a present field as 'from the start': $sent", sent.contains("startAtMs"))
    }

    @Test
    fun `an explicit zero is sent, so it still means from the start`() {
        val sent = wire(TargetQueueRequest(emptyList(), QueueMode.PLAY, startAtMs = 0))

        assertTrue(sent, sent.contains("\"startAtMs\":0"))
    }

    @Test
    fun `a start point survives the round trip`() {
        val sent = wire(TargetQueueRequest(emptyList(), QueueMode.PLAY, startAtMs = 134_221))

        assertEquals(134_221L, TargetsJson.decodeFromString(TargetQueueRequest.serializer(), sent).startAtMs)
    }

    @Test
    fun `an old phone's request, which always carried a number, is an explicit start`() {
        val old = """{"items":[],"mode":"PLAY","startAtMs":0}"""

        assertEquals(0L, TargetsJson.decodeFromString(TargetQueueRequest.serializer(), old).startAtMs)
    }

    @Test
    fun `a request with no field reads as no start point`() {
        val none = """{"items":[],"mode":"PLAY"}"""

        assertNull(TargetsJson.decodeFromString(TargetQueueRequest.serializer(), none).startAtMs)
    }
}
