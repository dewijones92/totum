package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class DroppedFramesSayWhyTest {

    private val earlier = FrameCounts(
        rendered = 100,
        droppedLate = 10,
        droppedToKeyframe = 1,
        skipped = 4,
        maxConsecutiveDropped = 3
    )
    private val later = FrameCounts(
        rendered = 160,
        droppedLate = 45,
        droppedToKeyframe = 3,
        skipped = 9,
        maxConsecutiveDropped = 12
    )

    @Test
    fun `the counts since the last line are the difference, with the worst run kept as it is`() {
        assertEquals(
            FrameCounts(
                rendered = 60,
                droppedLate = 35,
                droppedToKeyframe = 2,
                skipped = 5,
                maxConsecutiveDropped = 12
            ),
            later - earlier,
        )
    }

    @Test
    fun `a dropped-frames line names the speed, skip-silence and why the frames went`() {
        assertEquals(
            "dropped 50 frames over 400ms [speed=2.0 skipSilence=true since the last line: " +
                "rendered=60 droppedLate=35 droppedToKeyframe=2 skipped=5 maxConsecutiveDropped=12]",
            droppedFramesLine(50, 400, 2.0f, true, later - earlier),
        )
    }

    @Test
    fun `without counters the line still says the speed and skip-silence`() {
        assertEquals(
            "dropped 50 frames over 400ms [speed=1.0 skipSilence=false]",
            droppedFramesLine(50, 400, 1.0f, false, null),
        )
    }
}
