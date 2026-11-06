package com.dewijones92.totum.domain

public data class QualityLabel(val height: Int, val family: VideoCodecFamily?) {
    public val text: String get() = family?.let { "${height}p · ${it.name}" } ?: "${height}p"

    public companion object {
        public fun of(height: Int, codec: String?): QualityLabel = QualityLabel(height, VideoCodecFamily.of(codec))
    }
}
