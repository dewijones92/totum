package com.dewijones92.totum.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class QualityLabelTest {

    @Test
    fun `a known family follows the height after a dot`() {
        assertEquals("1080p · VP9", QualityLabel(1080, VideoCodecFamily.VP9).text)
        assertEquals("2160p · AV1", QualityLabel(2160, VideoCodecFamily.AV1).text)
    }

    @Test
    fun `with no family it is the bare height`() {
        assertEquals("720p", QualityLabel(720, null).text)
    }

    @Test
    fun `the label made from a codec string names that codec's family`() {
        assertEquals("1440p · VP9", QualityLabel.of(1440, "vp09.00.50.08").text)
        assertEquals("1080p · AVC", QualityLabel.of(1080, "avc1.640028").text)
        assertEquals("1080p · HEVC", QualityLabel.of(1080, "hvc1.2.4.L120").text)
        assertEquals("2160p · AV1", QualityLabel.of(2160, "av01.0.12M.08").text)
    }

    @Test
    fun `no codec, or one nobody knows, is the bare height`() {
        assertEquals("480p", QualityLabel.of(480, null).text)
        assertEquals("480p", QualityLabel.of(480, "mystery.1").text)
        assertEquals("480p", QualityLabel.of(480, "").text)
    }
}
