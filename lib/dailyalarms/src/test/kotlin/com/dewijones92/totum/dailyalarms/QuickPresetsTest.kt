package com.dewijones92.totum.dailyalarms

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickPresetsTest {

    private val presets = listOf("🏃 Stairs ×5", "💧 Drink water", "🧘 Breathe")

    @Test
    fun `the preset used goes first, so the screen starts on it next time`() {
        assertEquals(listOf("🧘 Breathe", "🏃 Stairs ×5", "💧 Drink water"), presets.usedPreset("🧘 Breathe"))
    }

    @Test
    fun `adding puts the new preset first, and an existing one is not doubled`() {
        assertEquals(listOf("🍌 Snack") + presets, presets.addedPreset(" 🍌 Snack "))
        assertEquals(listOf("💧 drink water", "🏃 Stairs ×5", "🧘 Breathe"), presets.addedPreset("💧 drink water"))
        assertEquals(presets, presets.addedPreset("   "))
    }

    @Test
    fun `renaming keeps its place, and a blank name changes nothing`() {
        assertEquals(
            listOf("🏃 Stairs ×10", "💧 Drink water", "🧘 Breathe"),
            presets.renamedPreset("🏃 Stairs ×5", "🏃 Stairs ×10"),
        )
        assertEquals(presets, presets.renamedPreset("🏃 Stairs ×5", " "))
    }

    @Test
    fun `renaming onto another preset merges them, in the renamed one's place`() {
        assertEquals(listOf("🧘 breathe", "💧 Drink water"), presets.renamedPreset("🏃 Stairs ×5", "🧘 breathe"))
    }

    @Test
    fun `deleting removes only that preset`() {
        assertEquals(listOf("🏃 Stairs ×5", "🧘 Breathe"), presets.deletedPreset("💧 Drink water"))
    }

    @Test
    fun `the first install starts with the stairs`() {
        assertEquals(listOf(QuickPresets.STAIRS), QuickPresets.FIRST)
    }
}
