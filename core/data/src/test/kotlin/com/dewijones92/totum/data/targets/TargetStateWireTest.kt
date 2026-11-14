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

    @Test
    fun `the address to open in a browser travels with the list, and an older Pi's list has none`() {
        val answer = TargetsAnswer(listOf(TargetState(mic)), browserAddress = "192.168.0.5:8771")

        val back = TargetsJson.decodeFromString(
            TargetsAnswer.serializer(),
            TargetsJson.encodeToString(TargetsAnswer.serializer(), answer)
        )
        val old = TargetsJson.decodeFromString(
            TargetsAnswer.serializer(),
            """{"targets":[{"target":{"id":"mic","label":"Soundmic","kind":"SPEAKER"}}]}"""
        )

        assertEquals("192.168.0.5:8771", back.browserAddress)
        assertNull(old.browserAddress)
        assertEquals(1, old.targets.size)
    }

    @Test
    fun `whether a speaker is switched on by hand travels with its state, and an older Pi reads as not`() {
        val byHand = TargetState(mic, power = TargetPower.ASLEEP, switchedOnByHand = true)

        val back = TargetsJson.decodeFromString(
            TargetState.serializer(),
            TargetsJson.encodeToString(TargetState.serializer(), byHand)
        )
        val old = TargetsJson.decodeFromString(
            TargetState.serializer(),
            """{"target":{"id":"mic","label":"Soundmic","kind":"SPEAKER"},"power":"ASLEEP"}"""
        )

        assertEquals(true, back.switchedOnByHand)
        assertEquals(false, old.switchedOnByHand)
    }
}
