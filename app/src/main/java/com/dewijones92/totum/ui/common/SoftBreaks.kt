package com.dewijones92.totum.ui.common

import com.dewijones92.totum.domain.ItemFace
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.isTorrent

private const val ZERO_WIDTH_SPACE = "\u200B"
private val BREAK_AFTER = Regex("([._])(?=[^\\s._])")

internal fun String.breakableAtDotsAndUnderscores(): String = replace(BREAK_AFTER, "$1$ZERO_WIDTH_SPACE")

internal fun String.wrappingAs(id: MediaItemId): String = if (id.isTorrent) breakableAtDotsAndUnderscores() else this

internal fun ItemFace.wrappingAs(id: MediaItemId): ItemFace = copy(title = title.wrappingAs(id))
