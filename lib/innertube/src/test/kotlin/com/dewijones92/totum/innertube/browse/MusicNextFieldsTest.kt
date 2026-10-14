package com.dewijones92.totum.innertube.browse

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicNextFieldsTest {

    @Test
    fun `a station started with params sends them, as the YouTube Music app does`() {
        val body = MusicNextTarget.Radio("xUNqsfFUwhY", "RDAODwfWqCd9jXCuVO7pjkJHTw", "wAEB8gECGAE%3D").fields()

        assertTrue(body, body.contains(""""params":"wAEB8gECGAE%3D""""))
        assertTrue(body, body.contains(""""playlistId":"RDAODwfWqCd9jXCuVO7pjkJHTw""""))
    }

    @Test
    fun `a station with no params sends none`() {
        assertFalse(MusicNextTarget.Radio(null, "RDEMabc").fields().contains("params"))
    }
}
