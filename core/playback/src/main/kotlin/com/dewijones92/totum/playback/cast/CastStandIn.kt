package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.domain.CastScreen
import com.dewijones92.totum.domain.VideoCodecFamily

internal fun standInScreen(model: String): CastScreen =
    if (model.startsWith("codecs:")) {
        val families = model.removePrefix("codecs:").split('+').mapNotNull { token ->
            VideoCodecFamily.entries.firstOrNull { it.name.equals(token, ignoreCase = true) }
                ?: VideoCodecFamily.of(token)
        }.toSet()
        CastScreen(model, families, STAND_IN_HEIGHT, known = false)
    } else {
        CastScreen.of(model)
    }

private const val STAND_IN_HEIGHT = 1080

public object CastStandIn {
    @Volatile
    public var serveCurrent: ((asModel: String?, url: String?) -> String?)? = null
}
