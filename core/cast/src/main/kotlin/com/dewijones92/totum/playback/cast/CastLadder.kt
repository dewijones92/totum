package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.domain.CastScreen
import com.dewijones92.totum.domain.QualityLabel

public data class CastRung(val url: String, val height: Int, val codec: String?) {
    val quality: QualityLabel get() = QualityLabel.of(height, codec)
    val label: String get() = quality.text
}

internal object CastLadder {

    fun ranked(offered: List<CastRung>, screen: CastScreen?, ceiling: Int? = null): List<CastRung> =
        offered.distinctBy { it.url }
            .filter { screen?.canDecode(it.codec, it.height) ?: true }
            .filter { ceiling == null || it.height <= ceiling }
            .sortedWith(
                compareByDescending<CastRung> { it.height }
                    .thenBy { screen?.preference(it.codec) ?: CastScreen.rank(it.codec) },
            )
}
