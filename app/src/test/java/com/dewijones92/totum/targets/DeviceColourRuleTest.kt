package com.dewijones92.totum.targets

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DeviceColourRuleTest {

    private val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()

    @Test
    fun `the app's sources are found, so the rule below is not vacuous`() {
        assertTrue("found ${sources.size} files", sources.size > 100)
    }

    @Test
    fun `no surface writes its own fallback for a device's colour, they all ask TargetColours of`() {
        val own = sources.filter { file ->
            file.readLines().any { line -> "?: TargetColours.PHONE" in line }
        }

        assertTrue("these files choose a device's colour themselves: ${own.map { it.name }}", own.isEmpty())
    }

    @Test
    fun `a device's colour is converted in one place, so a surface cannot read the bits differently`() {
        val own = sources.filter { file ->
            file.readLines().any { line -> "colour.toInt()" in line || "TargetColours.PHONE).toInt()" in line }
        }

        assertTrue("these files convert a device's colour themselves: ${own.map { it.name }}", own.isEmpty())
    }
}
