package com.dewijones92.totum.data.targets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TargetStateWireTest {

    private val mic = Target("mic", "Soundmic", TargetKind.SPEAKER)

    @Test
    fun `the speaker's own volume and keep-awake state travel with the target's state`() {
        val state = TargetState(mic, volume = 40, volumeMode = VolumeMode.HARDWARE, keepAwake = KeepAwake.KEPT)

        val back = TargetsJson.decodeFromString(
            TargetState.serializer(),
            TargetsJson.encodeToString(TargetState.serializer(), state)
        )

        assertEquals(VolumeMode.HARDWARE, back.volumeMode)
        assertEquals(KeepAwake.KEPT, back.keepAwake)
    }

    @Test
    fun `a state from a Pi that does not know them reads as neither`() {
        val old = """{"target":{"id":"mic","label":"Soundmic","kind":"SPEAKER"},"volume":40}"""

        val state = TargetsJson.decodeFromString(TargetState.serializer(), old)

        assertNull(state.volumeMode)
        assertNull(state.keepAwake)
        assertEquals(40, state.volume)
    }
}
