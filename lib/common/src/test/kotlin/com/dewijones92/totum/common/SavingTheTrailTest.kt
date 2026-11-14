package com.dewijones92.totum.common

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SavingTheTrailTest {

    private val saved = mutableListOf<Triple<String, String, List<Breadcrumbs.Entry>>>()
    private val previous = Diag.recorder

    @Before
    fun setUp() {
        Breadcrumbs.clear()
        Diag.recorder = Diag.Recorder { kind, detail, trail -> saved += Triple(kind, detail, trail) }
    }

    @After
    fun tearDown() {
        Diag.recorder = previous
        Breadcrumbs.clear()
    }

    @Test
    fun `saving the trail hands the recorder the kind, the reason and the whole trail so far`() {
        Diag.log("playback", "before")
        Diag.log("cast", "the TV dropped")

        Diag.saveTrail("cast-drop", "the TV left the session")

        val (kind, detail, trail) = saved.single()
        assertEquals("cast-drop", kind)
        assertEquals("the TV left the session", detail)
        assertTrue(trail.any { it.message == "before" })
        assertTrue(trail.any { it.message == "the TV dropped" })
    }

    @Test
    fun `the saving itself is on the trail it saves, so a report says why it exists`() {
        Diag.saveTrail("stall-give-up", "no data for 40 s")

        val trail = saved.single().third
        assertTrue(trail.map { it.message }.toString(), trail.any { "stall-give-up" in it.message })
    }

    @Test
    fun `a recorder that throws is logged and never breaks the caller`() {
        Diag.recorder = Diag.Recorder { _, _, _ -> error("disk full") }

        Diag.saveTrail("cast-drop", "reason")

        assertTrue(Breadcrumbs.snapshot().any { "could not save the trail (cast-drop: reason)" in it.message })
    }

    @Test
    fun `with the default recorder saving is harmless`() {
        Diag.recorder = previous

        Diag.saveTrail("anything", "reason")

        assertEquals(0, saved.size)
    }
}
