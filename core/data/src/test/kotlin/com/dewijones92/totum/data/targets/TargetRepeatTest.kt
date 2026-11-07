package com.dewijones92.totum.data.targets

import com.dewijones92.totum.domain.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetRepeatTest {

    private fun next(mode: RepeatMode, index: Int, size: Int, failed: Boolean = false, played: Boolean = true) =
        mode.nextAfterEnd(index, size, failed, played)

    @Test
    fun `with repeat off an item that ends moves on to the next, and past the last is the end`() {
        assertEquals(1, next(RepeatMode.OFF, index = 0, size = 3))
        assertEquals(3, next(RepeatMode.OFF, index = 2, size = 3))
    }

    @Test
    fun `repeat this one plays the same item again`() {
        assertEquals(1, next(RepeatMode.ONE, index = 1, size = 3))
        assertEquals(0, next(RepeatMode.ONE, index = 0, size = 1))
    }

    @Test
    fun `repeat the queue moves on, and from the last item starts again from the top`() {
        assertEquals(2, next(RepeatMode.QUEUE, index = 1, size = 3))
        assertEquals(0, next(RepeatMode.QUEUE, index = 2, size = 3))
        assertEquals(0, next(RepeatMode.QUEUE, index = 0, size = 1))
    }

    @Test
    fun `an item that failed is never repeated, so a broken one cannot loop`() {
        assertEquals(2, next(RepeatMode.ONE, index = 1, size = 3, failed = true))
        assertEquals(3, next(RepeatMode.QUEUE, index = 2, size = 3, failed = true))
    }

    @Test
    fun `an item that ended almost at once is not repeated either`() {
        assertEquals(2, next(RepeatMode.ONE, index = 1, size = 3, played = false))
        assertEquals(3, next(RepeatMode.QUEUE, index = 2, size = 3, played = false))
    }

    @Test
    fun `a target keeps off, this one and the queue, and not the modes it cannot honour yet`() {
        assertTrue(RepeatMode.OFF.onATarget)
        assertTrue(RepeatMode.ONE.onATarget)
        assertTrue(RepeatMode.QUEUE.onATarget)
        assertFalse(RepeatMode.QUEUE_SHUFFLED.onATarget)
        assertFalse(RepeatMode.GROUP.onATarget)
        assertFalse(RepeatMode.GROUP_SHUFFLED.onATarget)
    }

    @Test
    fun `a state says its repeat, and one written before repeat existed reads as off`() {
        val target = Target("bar", "Bar")
        val said = TargetsJson.encodeToString(
            TargetState.serializer(),
            TargetState(target, repeat = RepeatMode.ONE)
        )
        assertTrue(said, said.contains("\"repeat\":\"ONE\""))
        assertEquals(RepeatMode.ONE, TargetsJson.decodeFromString(TargetState.serializer(), said).repeat)

        val old = """{"target":{"id":"bar","label":"Bar"},"entries":[],"at":-1,"playing":false}"""
        assertEquals(RepeatMode.OFF, TargetsJson.decodeFromString(TargetState.serializer(), old).repeat)
    }

    @Test
    fun `a request or a control without a repeat leaves it as it was`() {
        val request = TargetsJson.decodeFromString(
            TargetQueueRequest.serializer(),
            """{"items":[],"mode":"PLAY"}""",
        )
        assertNull(request.repeat)
        val control = TargetsJson.decodeFromString(TargetControl.serializer(), """{"action":"PAUSE"}""")
        assertNull(control.repeat)
        val set = TargetsJson.decodeFromString(
            TargetControl.serializer(),
            """{"action":"REPEAT","repeat":"QUEUE"}""",
        )
        assertEquals(TargetAction.REPEAT, set.action)
        assertEquals(RepeatMode.QUEUE, set.repeat)
    }
}
